# 使用守护 · 管理后台（服务端，Rust）

家长管控 App「使用守护」的配套后台：接收平板心跳、展示统计、下发远程指令
（锁定 / 解锁 / 暂停守护 / 改配置）。

一份**静态链接的单文件二进制** + 一个启动脚本，拷到 Linux 服务器上就能跑。
数据存 SQLite，但**服务器上不需要装 sqlite3**——它也被编进了同一个二进制。

---

## 一、为什么用 Rust，以及"要不要 Linux 环境"

**不需要。** 在 Windows 上就能直接编出 Linux 二进制，不装 Docker、不装 WSL、不装 gcc。

原理：Rust 官方工具链自带
* `rust-lld`（LLVM 链接器，能直接产出 ELF）
* 目标 `x86_64-unknown-linux-musl` 的 `lib/rustlib/.../lib/self-contained/`
  里已经带了 musl 的 `libc.a` 和启动目标文件（`crt1.o` 等）

只要把链接器指定成 `rust-lld` 并打开 `link-self-contained` 就够了：

```bash
SYSROOT=$(rustc --print sysroot)
# 宿主编译器没有 MSVC 的 link.exe 时，用 rustup 自带的 GNU 工具链（自带 mingw ld）
LLD="$SYSROOT/lib/rustlib/<host-triple>/bin/rust-lld.exe"

export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="-C linker=$LLD -C link-self-contained=yes"
cargo build --release --target x86_64-unknown-linux-musl
```

`build.sh` 就是这套流程的封装，会自己挑工具链、自己找 `rust-lld`。

### 唯一的例外：SQLite 的 C 代码

v2.2 起数据存 SQLite，`sqlite3.c` 是 C 代码，光靠 Rust 工具链编不了。
用 **zig** 当 C 交叉编译器（它自带 musl 的头文件和 libc）：

```bash
pip install ziglang          # 约 80MB，只要装一次
bash build.sh                # build.sh 会自动找到它
```

zig **只是构建期的工具**，不进产物、服务器上完全不需要。
如果你想用自己的 zig：`ZIG=/path/to/zig bash build.sh`。

> 顺带说一句：正因为要保住"不需要交叉 C 工具链"这条，服务端**没有用 SQLite**
> （SQLite 是 C 代码，得有 C 交叉编译器），改成了一个 JSON 文件全量读写。
> 数据量本来就极小（几台设备 + 几千条事件，几百 KB），完全够用。
> 同理也没内置 TLS —— 理由见 `deploy/README-deploy.md` 第六节。

---

## 二、构建

```bash
bash build.sh
```

产物：

```
dist/guardian-server/                     ← 可直接 scp 的目录
├── guardian-server                       ← Linux x86_64 静态 ELF，约 3.4 MB（含 SQLite）
├── run.sh                                ← 启动脚本（可反复执行 = 重启）
├── stop.sh                               ← 停止（升级前必须先停，见第五·五节）
├── start.sh / status.sh                  ← 启动 / 看状态
├── config.env.example
└── README.md                             ← 部署说明

dist/guardian-server-<版本>-linux-x86_64.tar.gz   ← 打包好的发布包
```

首次构建要编译 axum/tokio/sqlite3，几分钟；之后增量十几秒。

---

## 三、本地自测

```bash
# 1) 纯逻辑单元测试（日期换算、常量时间比较）
cargo test

# 2) 接口联调：把服务拉起来跑一遍"家长 + 平板"的完整流程
python test_api.py <可执行文件路径>
```

> v2.2 起 `test_api.py` 要在**目标平台**上跑（SQLite 已经在二进制里，不能跨平台）。
> 在服务器上：`python3 test_api.py ./guardian-server`。
> 它会自己起一个独立实例（端口 18999、数据放 `tmp-test/`），不碰生产数据和 8000 端口。
>
> **已实测**：2026-10-24 在 CentOS 7.9 x86_64 上跑完 **186 项断言，0 失败**。

`test_api.py` 覆盖了：静态页面、登录（错口令 / 不存在的账号 / 未登录 / 限流 429 /
改密码后旧密码失效）、**多账号权限隔离**（普通账号看不到别人的设备、越权访问返回 404、
不能进账号管理、事件流只含自己设备）、账号管理（创建 / 重名 / 弱密码 / 自我保护 /
删除前必须转移设备）、配对码（生成 / 一次性 / 过期 / 归属）、心跳（状态上报 / 取指令 / 去重）、
六种指令的下发与参数清洗、指令回执、**事件分页与按天查询**、
**重启后数据是否还在**、**旧版 JSON 自动迁移**。

### 不装 Linux 也能看界面：`preview/`

后端编译出来是 Linux 静态 ELF，Windows 上跑不起来，所以改了前端（日期选择器、
事件分页、按天联动）没法直接看效果。`preview/` 里补了两个只在开发机上用的脚本，
**不会打进发布包**：

```bash
# 1) 桩服务：把 static/ 发出去，并按真实的响应结构造假数据
node preview/mock-server.js 8711

# 2) 页面级自检：用 jsdom 真跑一遍 index.html + app.js，连到上面的桩服务
cd ~/.workbuddy/binaries/node/workspace && npm i jsdom   # 只需一次
NODE_PATH=~/.workbuddy/binaries/node/workspace/node_modules \
  node preview/ui-check.js 8711        # 第三个参数可传视口宽度，如 400
```

浏览器打开 `http://127.0.0.1:8711/` 就能点界面看效果（账号 `admin`，密码随便填，
桩服务不校验）。`ui-check.js` 现在有 36 条断言：首页事件带日期、设备页日期控件
默认今天且不能翻未来、点 ‹ 后时间轴和事件一起换、事件分页、全部事件弹窗的
按天/全部时间切换与翻页。**换了前端先跑一遍这个，比肉眼扫一遍可靠。**

> 桩数据是"设备 + 日期"确定的确定性伪随机 —— 同一天刷新永远是同一份，
> 这样翻页、切日期时能看出数据真的换了，而不是每次刷新都变。

---

## 四、部署

见 `deploy/README-deploy.md`（打好的包里就是 `README.md`）。两种方式：

```bash
# 方式一：一键远程部署（在本地电脑上跑，推荐）
# 依赖装进项目自己的 .venv，别污染系统 Python
python -m venv .venv && .venv/Scripts/python.exe -m pip install paramiko
.venv/Scripts/python.exe deploy/deploy.py --check   # 先体检
.venv/Scripts/python.exe deploy/deploy.py           # 正式部署

# 配置写在 deploy/deploy.env（host / user / password / path）
```

```bash
# 方式二：手动
scp guardian-server-*.tar.gz root@服务器:/opt/
ssh root@服务器 'cd /opt && tar xzf guardian-server-*.tar.gz && sh stop.sh 2>/dev/null; cd guardian-server && sh run.sh'
```

---

## 四·五、账号与权限（v2.0）

后台不是"一个总口令"，而是**多账号**，因为实际使用里往往不只一个人管：

| 角色 | 能看到什么 | 能做什么 |
| --- | --- | --- |
| `admin` | **全部设备**、全部事件 | 上面所有操作 + 新建/停用/删除账号、替别人生成配对码 |
| `user` | **只有归属自己的设备** | 给自己名下设备下发指令、自己生成配对码 |

几条关键规则：

* **设备归属写在配对码上**：谁生成配对码，配出来的平板就归谁。管理员可以在
  「添加设备」里选归属账号，替家里其他人先配好。
* **越权一律返回 404 而不是 403**：否则普通账号可以靠状态码探测出"系统里还有哪些设备 id"。
* **账号都是普通账号时**：管理员被降级/停用会先被拦住 —— 系统里永远至少留一个可用管理员。
* **删除账号前必须处理名下设备**：要么先转移（`?reassign=<账号id>`），否则会留下
  "谁都看不见、谁都不能操作"的孤儿设备，只能重新配对才能恢复。
* **停用账号 / 重设密码会踢掉该账号所有在线会话**；改自己的密码则由自己操作，
  只保留当前这个浏览器。

### 首次启动与改密码

1. 第一次运行时用环境变量 `GUARDIAN_ADMIN_PASSWORD`（默认 `guardian2026`）
   **创建**内置管理员 `admin`；
2. 之后这个环境变量**不再生效** —— 改密码请登录后台，点右上角「我的账号」；
3. 登录页会一直警告"还在用默认口令"，直到你把它改掉。

密码存储是 `pbkdf2$60000$盐$哈希`（PBKDF2-HMAC-SHA256，每个账号独立随机盐），
明文密码不落盘。SHA-256 / HMAC / PBKDF2 全部自己实现（约 150 行），
为的是不引入会破坏"Windows 交叉编译出静态包"这条前提的 C 依赖；
正确性用 FIPS 180-4 与 RFC 4231 的公开测试向量、以及 Python `hashlib` 逐项核对过。

---

## 四·六、时间线：在线时段、亮屏与应用使用（v2.1，v2.3 加屏幕轨）

设备列表点任意一台，进的就是它的**当日时间线**：三行分别是
「在线」（有心跳）、「屏幕」（亮着）、「应用」（前台色块）。
同一应用颜色固定，点色块看具体时段。

| 数据 | 来源 | 说明 |
| --- | --- | --- |
| 在线 / 离线 | 心跳 | 20 秒一次；两次间隔超过 90 秒就断成两段，避免网络抖一下切出碎片 |
| 屏幕亮 / 灭 | 设备上报 | `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE` 系统事件；亮着画绿线，熄屏留空白 |
| 前台应用 | 设备上报 | App 用 `UsageStatsManager` 采集，需要「使用情况访问」权限 |
| 应用名 | 设备上报 | 服务端没有 APK，只能靠设备把 `getApplicationLabel` 的结果带上来 |
| 解锁次数 | 设备上报 | `KEYGUARD_HIDDEN` 事件计数（亮屏后解锁才算） |
| 开关机次数 | 设备上报 | `DEVICE_STARTUP` / `DEVICE_SHUTDOWN` 事件计数 |
| 亮屏时长 | 设备上报 | 当天亮屏区间之和 |

几个刻意的设计：

- **片段按 `(start, pkg)` 幂等覆盖**：App 每 20 秒把"当前这一段"重报一次（`end` 往后推），
  所以不需要"我要切换了"这种结束通知，重复上报也不会攒出重复记录。
  前提是 **App 侧同一段的 `start` 必须恒定**（保存在 `usage_seg_start_ms`），
  每次心跳都换成"本轮窗口起点"的话，一小时能攒出 180 条碎片。
- **读到时就地合并**（`store::merge_adjacent`）：同包且间隔 ≤ 60 秒的相邻片段并成一段，
  时间重叠的以更晚开始的为准截断。这样即使库里有历史碎片，
  时间轴画出来仍是"连续用了多久"，`count` 也才是真正的"打开次数"。
- **时长字段一律毫秒**：`apps[].ms`、`totals.online_ms`、`totals.foreground_ms` 都是毫秒。
  （v2.1 早期版本这里混用了 Unix 秒，导致页面上所有时长都显示 0，v2.1.1 修正。）
- **时区**：按 `GUARDIAN_TZ_OFFSET`（默认 8）切天。返回的 `from` / `to`
  是当天本地 0 点与 24 点的 Unix 秒，前端据此换算成本地小时。

时间轴默认用**智能范围**：取当天第一次活动前后各留 15 分钟（跨度小时留得也少），
最少给 1 小时 —— 不再像早期那样一律补到 6 小时。孩子只活跃了 20 分钟的时候，
6 小时视野会把细节压扁 18 倍，每段不到 1 像素，色块只能靠最小宽度撑着，
看起来就是"一堆应用叠在一起"。右上角「全天」可切成完整 24 小时。
移动端横向滚动，每小时 46px（桌面 62px）；窄色块不显示文字，
点一下在下方显示"10:23–10:41 · 抖音 · 18m"。时长不到 1 分钟显示秒（`42s`）。

---

## 四·六·五、统计准不准（v2.3 重做了采集）

### 之前为什么会少

老版本（App ≤ 2.1.1）的采集是"上次上报到现在"的滑动窗口，游标存在
`usage_cursor_ms` 里。三个地方会让时间**永久丢失**：

| 场景 | 老行为 | 后果 |
| --- | --- | --- |
| 心跳断了几次（OPPO 速冻 / Doze / 网络断） | 间隔超过 30 分钟就只补最近 30 秒 | 中间整段丢弃 |
| 熄屏再亮屏 | 游标清零、当前段清零，亮屏后只补 30 秒 | 熄屏前那一段丢失 |
| 进程被杀后重启 | 起点重新算 | 死之前那一段丢失 |

表现就是"手机自带统计说 WorkBuddy 用了 3 分钟，后台只显示 1 分钟"。
总时长也总是**偏少**，而且越用越不准 —— 因为丢的都是真时间，不会多算。

### 现在怎么保证准

v2.3 把采集改成**无状态整日重扫**：

1. 每次心跳都从**设备本地的今天 0 点**扫到此刻（`UsageStatsManager.queryEvents`）；
2. 片段起点直接取系统事件的时间戳 —— 系统事件是系统自己记的，
   我们进程死没死都不影响，所以扫多少遍结果**完全一样**；
3. 服务端按 `(device_id, start)` 幂等覆盖，重复上报不会翻倍；
4. 漏了心跳、被杀过、跨过熄屏，**下一次扫描自动补齐**，不需要任何补偿逻辑。

代价是一天几百到几千条事件读一遍（毫秒级，跑在 App 的 IO 线程），换来的是
"随便你什么时候看，数字都对"。

### 熄屏不再"丢时间"，而是"切开"

熄屏后系统并不会把应用踢到后台，最后那个应用会一直挂着。老办法是熄屏就不采集，
代价是丢时间。现在的做法是**照常采集，再用亮屏区间去切**：

- App 从系统事件里取 `SCREEN_INTERACTIVE(15)` / `SCREEN_NON_INTERACTIVE(16)`，
  得到当天的亮屏区间，单独上报；
- 应用片段与亮屏区间求交集，跨熄屏的片段被**切成两段**，
  中间那段（熄屏时间）不属于任何应用；
- 服务端把亮屏区间存进 `screen` 表，时间轴上单独画一行绿线。

### 和手机自带统计对不上怎么办

设备端还会用 `UsageStatsManager.queryAndAggregateUsageStats` 取一次
**系统口径**的当日前台总时长（和手机「数字健康」是同一个 API、同一份数据），
作为 `totals.sys_ms` 上报。设备页的「前台」卡片会直接标出来：

- 差 < 15%：`与系统口径一致（3 小时 12 分）`
- 差 ≥ 15%：`系统口径 4 小时 0 分，差 -20%`（橙色，说明采集还有漏）

这样就不用再靠"我觉得不准"来讨论了 —— 页面上直接给出两个口径的差距。

> App 要升到 **2.3.0** 才有这些；**2.3.1** 另外修掉了"惩罚倍数误判"。旧 App 连上来照样能用，只是
> `screen_tracked=false`、解锁/开关机显示 `—`，不会出错。

---

## 四·七、存储：SQLite 与升级（v2.2）

### 为什么从 JSON 换过来

v2.1.x 及以前整库是一个 JSON 文件，每次改动都要**重写整个文件**。事件是只增不减的流水，
用久了必然出问题：

* 文件线性变大，一次心跳要序列化 + 写几十 MB，延迟从毫秒涨到几百毫秒；
* 分页、按天查询只能先把全部事件读进内存再 `filter`，数据一多就卡；
* 写到一半断电，整个文件都可能坏掉。

SQLite 补上这几点：单文件、无服务进程、有索引、事务写入。

**服务器上不需要装 sqlite3。** `sqlite3.c` 用 `bundled` 特性编进了二进制，
产物仍然是静态 ELF，`ldd` 看不到任何依赖。

### 数据怎么分

| 数据 | 位置 | 原因 |
| --- | --- | --- |
| 设备 / 账号 / 会话 / 配对码 / 指令 | 内存 + `kv` 表（整行 JSON） | 数量极少，热数据必须常驻内存 |
| 事件 / 在线区间 / 应用片段 / 每日汇总 | 独立表 + 索引 | 只增不减，需要按时间范围查询和分页 |

默认库文件 `data/guardian.db`（WAL 模式，会多出 `-wal` / `-shm` 两个文件，正常现象）。

### 保留多久

| 数据 | 保留 | 说明 |
| --- | --- | --- |
| 事件 | 365 天 | 后台每小时清一次超期数据 |
| 在线区间 / 应用片段 | 90 天 | 片段是 20 秒粒度，一天几千条 |
| 每日汇总 | 不清理 | 一天一台设备一行，十年也就几千行 |

要改的话改 `src/db.rs` 顶部的 `EVENT_KEEP_DAYS` / `TIMELINE_KEEP_DAYS`。

### 从 JSON 升级

**不用做任何事。** 新版本第一次启动时：

1. 发现同目录有 `state.json` → 读进来，把设备、账号、事件、时间线、每日汇总写进 SQLite；
2. 把原文件改名成 `state.json.migrated-<时间戳>`，避免下次重复导入；
3. 打印导入了多少条。

想先确认数据都在、再启动服务，可以单独跑一次迁移：

```bash
sh run.sh migrate      # 会先停服，再迁移，然后停在那儿（不自动启动）
```

> 迁移是**幂等**的：库里已经有数据时不会再导；解析失败会保留原文件并跳过，
> 不会出现"导一半把老数据弄丢"。

### 备份

```bash
sh run.sh backup       # 存到 backup/guardian-<时间>.db
```

装了 `sqlite3` 命令就是热备份（不停服）；没装会先停服再拷文件，拷完记得 `sh run.sh`。
WAL 模式下**运行中直接 `cp` 数据库文件会缺数据**，所以才需要这个命令。

---

## 五、接口一览

管理端（要 `Authorization: Bearer <登录 token>`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/login` | `{username, password}` 换 token（同 IP 5 分钟错 8 次锁 5 分钟） |
| POST | `/api/logout` | 注销当前 token |
| GET | `/api/me` | 当前账号信息与可见设备数 |
| POST | `/api/me/password` | 改自己的密码（要验证旧密码） |
| GET | `/api/overview` | 页面主数据：统计、7 天趋势、设备列表、最近事件（**已按归属过滤**） |
| GET | `/api/devices` | 设备列表（同上） |
| GET | `/api/devices/{id}` | 单台详情：近 14 天、最近指令、事件、今日应用排行 |
| GET | `/api/devices/{id}/timeline?day=YYYY-MM-DD` | **某一天的在线区间 + 前台应用片段**（缺省今天） |
| GET | `/api/devices/{id}/events?day=&page=&size=` | **某一天的事件，分页**（设备主页用，缺省今天） |
| POST | `/api/devices/{id}/command` | 下发指令 |
| GET | `/api/events?day=&hours=&device=&page=&size=` | **事件分页 / 按天查询**（普通账号只看自己设备的） |
| POST | `/api/pair-codes` | 生成 6 位配对码（10 分钟、一次性）；管理员可加 `?owner=<账号id>` |
| GET | `/api/users` | 账号列表（仅管理员） |
| POST | `/api/users` | 新建账号（仅管理员） |
| POST | `/api/users/{id}` | 改密码 / 角色 / 停用 / 备注（仅管理员） |
| POST | `/api/users/{id}/delete` | 删除账号，设备需要 `?reassign=<账号id>`（仅管理员） |
| POST | `/api/devices/{id}/delete` | 移除设备（令牌立即失效，流水一起清掉） |

**事件查询参数**（`/api/events` 与 `/api/devices/{id}/events` 共用）：

| 参数 | 说明 |
| --- | --- |
| `day=2026-10-24` | 查这一天（本地时区 0:00–24:00）；给了它就忽略 `hours` |
| `hours=48` | 查最近 N 小时（首页默认 48） |
| `device=<id>` | 只看某台设备（越权 → 404） |
| `page=1` `size=50` | 分页，`size` 上限 200 |

返回 `{events, total, page, size, pages, from, to, day, today}`。

设备端（要 `Authorization: Bearer <设备 token>`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/device/pair` | 用配对码换设备 token |
| POST | `/api/device/heartbeat` | 上报状态 + 取走待执行指令 |
| POST | `/api/device/usage` | 上报前台应用片段 `{segments:[{start,end,pkg,label}]}`（幂等） |
| POST | `/api/device/result` | 回报指令执行结果 |

指令类型：`lock`（锁定 N 分钟）、`unlock`、`pause`（暂停守护）、`enable`、
`config`（改 `use_minutes` / `rest_minutes` / `reset_gap_seconds` / `tamper_policy` /
`pause_media_on_rest` / `mute_on_rest`）、`sync`、`reset_tamper`（清零违规计数，需 App ≥ 2.3.1）。

> **`lock` 不传 `minutes` 时取这台设备自己的 `rest_minutes`，不是写死 5。**
> v2.3.0 及以前这里固定补默认值 5：后台按钮上写着"锁定 1 分钟"（那是设备的
> `rest_minutes`），请求里却没带分钟数，于是孩子实际被锁 5 分钟。
> 现在前端的按钮会把 `data-min` 一起发上来，服务端也以设备配置为默认值，双保险。

> **休息时长 ≠ 配置值的两个原因，后台都会标出来。**
> ① `tamper_policy` 打开时，惩罚按违规次数放大：第 1 次原长、第 2 次 ×2、
> 第 3 次起 ×3（App 端 `Prefs.penaltyMsFor`，上限 180 分钟）。所以"设 5 分钟、
> 实际休 15 分钟"通常是 ×3 而不是计时 bug —— 设备卡上会写「违规 3 次 · 休息 ×3」，
> 配置卡直接算出 `5×3 = 15 分钟`。
> ② 违规次数以前会把"开机（重启清空闹钟）""被系统清理"都算进去，
> 国产 ROM 上一天能误判十几次，倍数很快顶到 ×3。v2.3.1 起只有系统记录的
> `REASON_USER_REQUESTED`（用户主动强行停止）才计违规，其余只留痕。
> 已经涨上去的计数用设备页的「清零违规计数」一键归零。

> 为什么是"设备来取"而不是"服务端推送"：平板在家里的 NAT 后面，没有公网地址，
> 国内也收不到 FCM。设备每 20 秒问一句"有活儿吗"是最可靠、最省电的做法，
> 代价是后台点完按钮最久要等 20 秒。

---

## 六、代码结构

```
src/
├── main.rs       配置（环境变量）、路由表、启动横幅、后台维护任务、优雅退出
├── handlers.rs   全部接口 + 统一错误类型 + 内嵌静态资源
├── store.rs      数据模型；小数据（设备/账号/会话/指令）常驻内存并同步到 kv 表；
│                 旧版 JSON 的自动迁移也在这里
├── db.rs         SQLite 封装：建表、事件分页、在线区间、应用片段、每日汇总、清理
└── util.rs       时间戳 / 时区日期换算 / 随机串 / PBKDF2 / 常量时间比较
static/           前端（index.html / app.css / app.js），用 include_str! 编进二进制
deploy/           要随二进制一起发布的文件（run.sh / stop.sh / start.sh / status.sh /
                  config.env.example / deploy.py / 部署说明）
legacy-python/    第一版 FastAPI 实现，仅留作对照，已不再维护
```

---

## 七、和 Python 版的关系

`legacy-python/main.py` 是同一套接口的 FastAPI 实现，功能等价。
现在用 Rust 版是因为：

1. 服务器上不用装 Python、不用 pip、不用担心 venv 和依赖冲突；
2. 一个 1.5 MB 的静态文件，拷贝即部署；
3. 常驻进程内存小、启动快，跑在 1 核 1G 的小机器上更轻松。

前端的 API 契约完全没有变，所以两个版本的页面（`static/`）是通用的。
