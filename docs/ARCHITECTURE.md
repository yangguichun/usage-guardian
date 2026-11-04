# 架构与设计取舍

这篇文档记录的是**为什么这么写**，不是功能清单。改动计时、遮罩、停用时段之前建议先读一遍。

## 1. 双端职责

| | 平板端 | 服务端 |
| --- | --- | --- |
| 权威数据 | 系统使用事件、亮灭屏时间戳 | 规则配置、账号与设备归属 |
| 主要职责 | 判定该不该锁、把遮罩挂上去、幂等算出时长 | 存规则、下发指令、归集整天统计 |
| 断网时 | 按本机最后一份配置继续强制休息 | — |
| 谁能改规则 | **不能**（App 端没有停用规则编辑入口） | 只有后台 |

「规则只认服务端下发」是刻意设计：设备上报的 `quiet_hours` 服务端一律不采信，否则改一下手机就能绕过宵禁。

## 2. 心跳

App 每 20 秒上报一次状态，服务端在同一响应里带回**全量规则**与待执行指令。指令取走后从队列移除，重复上报不会重复执行。

- 服务端计数类字段一律用 `MAX()` 合并：设备重复上报整天数据不会让数字翻倍。
- 「今天」按**设备本地的 `day`** 归集，不是服务器时区；跨时区不错一天。
- 心跳里 `today_used_ms` 缺字段时保留旧值，不能默认 0（否则一个旧版 App 上线就把数字打回 0）。

## 3. 计时模型（最容易出 bug 的地方）

### 只准「整天重扫」

`Usage.snapshot()` 每次从**设备本地今天 0 点**扫到现在，片段 `start` 直接取系统事件时间戳。
`Usage.usedMs(from, to, offRanges)` 从这些事件算「本轮已用」「今日使用」。

**不许退回滑动窗口 / 游标**。任何一次心跳丢失（OPPO 速冻、Doze、进程被杀）都会让那段时间永久丢失 ——
用户看到的「手机说 3 分钟、后台说 1 分钟」就是这么来的。

同样，**窗口起点绝不能随 `now` 滑动**（试过 `now-6min` 补扫）：亮屏区间 start 每次不同，
服务端按 `(device_id, start)` 存，每次心跳插新行，亮屏时长翻几倍。

也**不许在进程内累加**（`todayBufferMs += dt` 且 `dt > 5s` 整段丢弃）：速冻一次丢几分钟，
进程被杀丢一整段；更糟的是上限判定同源，导致用超了也不锁。

### 本轮起点 `roundStartWall`

持久化保存，只有这几种情况才能前移：休息自然/提前结束、停用结束、空闲超过阈值、家长手动清零。
`onUseStart` **不许无条件覆盖**它，否则一次亮灭屏就把本轮重置了。

### 派生缓存失效

`refreshUsage` 有 20 秒扫描缓存（`roundFromEvents` / `usageScanAt`）。
凡是新增「把起点推到此刻」的分支，都要想一遍两道保险：

1. 短路条件带 `roundStartWall <= usageScanAt`；
2. `usedMsNow()` 对「起点比上次扫描新」返回 0，并 clamp 到 `now - roundStartWall`。

漏掉就会重现「家长点了跳过，1 秒内遮罩又弹回来」。

### 扣掉被锁的时间

遮罩是**悬浮窗，前台应用没变** —— 休息 / 停用期间系统照样把时间记给微信 / 抖音。
算使用时长必须扣 `Prefs.offRanges()`（进遮罩 `pushOffStart`、退出 `closeOff`），
并在重算前对账：进程在遮罩中被杀时会留着未闭合区间，不收尾会把之后正常使用全扣光。

### 熄屏不是「不采集」而是「切开」

`clipToScreen()` 拿亮屏区间与应用片段求交集并切段。老的「熄屏清游标」会永久丢时间。

### 采集事件用数值常量

`FOREGROUND=1 / SCREEN_ON=15 / SCREEN_OFF=16 / KEYGUARD_HIDDEN=18 /
DEVICE_SHUTDOWN=26 / DEVICE_STARTUP=27`。minSdk 26，这些常量名 API 28 才有，
引用名字会 `NoSuchFieldError`。

## 4. 三种限制：休息 / 每日总量 / 停用

三者语义不同，**优先级：停用 > 每日总量 > 休息**。混在一起会互相清状态：

- 休息：看「本轮用了多久」，到上限弹遮罩，倒计时结束后前移 `roundStartWall`；
- 每日总量：看「今天累计用了多久」，达到 `day_limit_minutes` 后锁到次日 0 点，**没有倒计时**；
- 停用（宵禁）：看「现在几点」，规则 `{enabled, label, from, to, days}`，`from/to` 是 0 点起算的分钟数（0–1440），`days` 是 7 位掩码；跨夜按「时段开始那天」判星期。

每日总量**不能用 `lockedUntilWall` 表达**：那是一个「结束时刻」，写进去就等于给了它倒计时，
孩子熬到那一刻就自由了。它只由 `todayUsedMs >= dayLimitMinutes * 60000` 实时判定，
豁免只有三种：`dayClearedDay == 今天`（后台「今日解除」）、`dayUnlockUntilWall > now`
（密码临时放行 15 分钟）、`day_limit_minutes <= 0`（没开）。

进入停用必须**清空本轮已用时长**并停掉时长闹钟，否则熬完宵禁一解锁就被判超时、立刻又锁。

- 到点靠 `setAlarmClock`（独立 request code **和独立 action**；`cancelAll` 里按 requestCode 取消不掉）。
- 家长「提前结束本次停用」= 记 `quietSkipUntilWall = 本段结束时刻`，**只放行这一轮**；做成永久关闭等于一次放行就废掉宵禁。
- 远程 `lock` 的默认分钟数取**设备自己配置的 `rest_minutes`**，前端按钮要带 `data-min`；写死默认 5 会造成「按钮写 1 分钟、实际锁 5 分钟」。
- 遮罩必须说明「这一轮为什么这么久」（`prefs.restPlan`），否则家长会把「5 分钟 ×3 = 15 分钟」当成计时 bug。

## 5. 防绕过

| 手段 | 作用 | 边界 |
| --- | --- | --- |
| 前台常驻服务 + 常驻通知 | 常规保活 | **前台服务不等于进程在跑**：OPPO「应用速冻」会冻结进程，通知还挂着但 tick 停了 |
| `setAlarmClock` 每 15 秒自检 | 不依赖任何回调 | 用户「强行停止」会清空全部闹钟与广播，救不回来 |
| 系统 Chronometer + 停滞检测 | 进程被冻结时倒计时照样走 | — |
| 设备管理员 | 增加卸载成本 | **不能**禁用强行停止（只对部分 ROM 成立） |
| 惩罚欠账 | 下次打开时补兑现 | 只对「确凿证据」累加 |

★ **违规计数只能由确凿证据累加**：惩罚按次数放大（1 次 ×1、2 次 ×2、3 次起 ×3），
误判一次就是「设 5 分钟休 15 分钟」。禁止再用这两个判据：

1. `KeepAlive.isWatchdogArmed` 反推强停 —— 重启本来就清空所有闹钟，每次开机误判一次；
2. `uncleanExit` / `heartbeat gap` —— ROM 清理、速冻、Doze 全都命中，一天十几次。

唯一可信证据是 `ApplicationExitInfo` 的 `REASON_USER_REQUESTED / USER_STOPPED`，且要用 `lastExitTs` 去重；其余只 `markKill` 留痕。

★ **同一语义只准一份实现**：`skipRest()` 曾同时存在于 `GuardianService` 和 `LockActivity`，
改计时模型时只改了前者。现在统一走 `GuardianService.skipFromMask(ctx)`。

## 6. 遮罩（悬浮窗）的坑

- **上锁期间不能碰任何控件**：设了密码时 `onCreate` 不跑 `setupUi()`，`lateinit` 全空，
  `uiTick` 立刻跑 → 每次打开必崩。render 函数一律 `if (!::x.isInitialized) return`，
  `uiTick` 用 `if (unlocked && uiReady)` 整跳，`uiReady = true` 放 `setupUi()` 结尾。
- **startForeground 失败必须立刻 `stopSelf`**，否则 5 秒后被 `RemoteServiceException` 杀进程（看起来像闪退）。
- **遮罩里绝不放 EditText**：悬浮窗层级 2038 高于输入法 2011，键盘一闪就没；一律自绘 `PinPad`。
- **不要拆第二个 Activity 做设置页**：密码门禁绑在 MainActivity 生命周期，拆页会把状态拆散。

## 7. 服务端存储

Rust + axum，SQLite（`rusqlite` + `bundled`），单文件 `data/guardian.db`。

- 旧 JSON（`state.json`）迁移是**自动的**：`Store::open` 见到就导入并改名，升级不用手敲命令。
- **新表一律 `CREATE TABLE IF NOT EXISTS`，不给老表加列**：老库升级不用 `ALTER TABLE`，少一类线上事故。
- 前端 `static/` 用 `include_str!` 编进二进制 → **改了 `static/` 必须重建二进制**，靠时间戳核对（二进制比 `app.js` 旧就是没编进去），cargo 不会报错。
- 多账号：PBKDF2 手写实现，设备归属锚定配对码，越权返回 **404 而非 403**。
- 设备页路由用 hash（`#/device/<id>` + pushState/popstate），不用 path：后台可能挂在反向代理子目录下，`pushState('/device/x')` 会把子目录抹掉、刷新 404。

## 8. 面板布局约定

- 面板内距统一走 `--pad-x`（`.panel > :not(.panel-head)` 统一加左右 padding），
  面板之间靠 `.page-body { display:grid; gap:14px }`。别在单个块上另写 margin/padding。
- 量布局别用 Edge headless 截图下结论：`--window-size=390` 的实际 `innerWidth` 是 496，
  截图只截 390 宽，右侧看着像溢出，其实是裁图。判断横向溢出要用 `--dump-dom` 读 `scrollWidth`
  （`preview/mock-server.js` 的 `?measure` 会把各面板 L/R/W 写进 title）。
- 前端改动用 `preview/` 验证：`mock-server.js` 造假数据 + `ui-check.js`（jsdom）跑断言，别靠肉眼扫。

---

## 版本兼容速查

| 版本 | 关键变化 |
| --- | --- |
| v2.5.2 | 每日额度剩余时间改「N 小时 M 分」+ 44sp（原 76sp 秒表格式在窄屏折行） |
| v2.5.1 | 每日额度遮罩补显式家长解锁入口（`syncParentEntry`），未设密码也给出说明 |
| v2.5.0 | 每日总时长（`day_limit` / `day_unlock` / `day_relock`，要求 `app_version >= 2.5.0`） |
| v2.4.2 | 跳过保护缓存失效修复、hash 路由、应用过滤、面板内距统一 |
| v2.4.1 | 计时口径统一为整天重扫、`roundStartWall`、`offRanges` |
| v2.4.0 | 停用时段（宵禁）、`quiet` 指令（要求 `app_version >= 2.4.0`） |
| v2.3.x | SQLite 存储、屏幕/日统计表、违规计数只认 `ApplicationExitInfo` |
| v2.2 | JSON → SQLite 自动迁移、多账号 |
| v2.0 | 服务端 + App 远程管理 |
