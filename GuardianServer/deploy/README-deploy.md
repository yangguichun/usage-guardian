# 使用守护 · 管理后台（服务端）

一个 Java 版的"家长控制后台"——不，它是**单个静态二进制**：
把 `guardian-server` 拷到 Linux 服务器上，`sh run.sh` 就能跑，
服务器上**不需要装 Python / 数据库 / Node / Docker / 任何依赖**。

前端管理页面（HTML/CSS/JS）已经编进了这个二进制文件里，
所以不用额外传 `static/` 目录，也不怕路径搞错。

数据存 **SQLite**，但 `sqlite3.c` 也编进了同一个二进制 ——
**不需要在服务器上装 sqlite3**，`ldd` 看不到任何依赖。

---

## 零、一键部署（推荐，v2.2 起，v2.3 通用）

不用手动 scp 了。在**你自己的电脑**上准备一次独立环境（别装进系统 Python）：

```bash
cd GuardianServer
python -m venv .venv                       # C 盘紧张时用 D 盘的 python 建
.venv/Scripts/python.exe -m pip install paramiko     # Windows
# .venv/bin/python -m pip install paramiko           # Linux / macOS
```

以后只要一条命令：

```bash
.venv/Scripts/python.exe deploy/deploy.py            # 正式部署
.venv/Scripts/python.exe deploy/deploy.py --check    # 只体检，不动任何东西
```

配置全部写在 `deploy/deploy.env`（模板见 `deploy.env.example`）：

```
GUARDIAN_SSH_HOST=1.2.3.4
GUARDIAN_SSH_USER=root
GUARDIAN_SSH_PASSWORD=...
GUARDIAN_REMOTE_PATH=/home/child-guard/guardian-server   # ★ 必须和服务器上的实际目录一致
GUARDIAN_HTTP_PORT=8000
```

它会自动完成：停服 → 备份 → 上传 → 解包 → 数据迁移 → 启动 → 健康检查，
**任何一步失败都会把二进制和 `data/` 一起回滚**。

`--check` 只体检：报架构、磁盘、现有安装、数据库大小、健康状态，不做任何改动。

> ⚠️ **`GUARDIAN_REMOTE_PATH` 写错会变成"在空目录里全新装一份"**，
> 而且新实例会因为 8000 端口被老进程占着起不来。
> 不确定就先用 `--check` 看一眼它报的"现有数据库/旧版 JSON"路径对不对。
> 实在拿不准，跑 `python deploy/diag.py`，它会把服务器上现有进程、安装目录、
> 数据文件、端口占用一次列出来（只读）。

> ⚠️ **用 Git Bash 传 `--path` 会被改坏。** MSYS 会把以 `/` 开头的参数当成 Windows
> 路径，`--path /opt/guardian-server` 会变成 `D:/Program Files/Git/opt/...`，
> 脚本就会往一个不存在的目录里传文件。deploy.py 认得出这种情况并直接报错。
> 两种绕法：① 把路径写进 `deploy/deploy.env`（推荐，从文件读不经过这层转换）；
> ② 传参前加 `MSYS_NO_PATHCONV=1`。PowerShell / cmd / Linux 下没有这个问题。

**跨公网上传会偶发断连**（`WinError 10054` / `Socket exception`）。
脚本已经做了两层防护：每 15 秒发一次 SSH 心跳保持链路，命令和上传失败都自动
重连重试（上传最多 3 次）。真遇到时你只会看到一行 `! 连接断了…重连后重试`，
流程不会中断。

### 首次实测记录（2026-10-24）

在 CentOS 7.9 / x86_64 / 2.1 上从 v2.1.1（JSON）原地升到 v2.3.0（SQLite）：

```
[迁移] 发现旧版数据 data/state.json，开始导入 SQLite：
       3 台设备 / 1 个账号 / 51 条事件 / 204 条片段
[迁移] 完成。原文件已改名为 data/state.json.migrated-1792813934
[存储] SQLite 已就绪 · 3 台设备 · 1 个账号 · 51 条事件 · 204 条应用片段 · 17 段在线
✔ 通过：{"ok":true,...}
```

升级后 `test_api.py` 在同一台机器上 **186 项断言全过、0 失败**（含事件分页、
亮屏/解锁/开关机、JSON → SQLite 迁移）。备份在 `<安装目录>/backup/<时间戳>/`
（里面是旧二进制 + 整个 `data/`）。

---

## 一、手工上传并启动

```bash
# 1. 上传（在你本地执行；把 IP 和路径换成你的）
scp guardian-server-2.3.1-linux-x86_64.tar.gz root@你的服务器:/opt/

# 2. 解包
ssh root@你的服务器
cd /opt && tar xzf guardian-server-2.3.1-linux-x86_64.tar.gz
cd /opt/guardian-server

# 3. 启动
sh run.sh
```

看到 `✔ 已启动` 后，浏览器打开 `http://服务器IP:8000/`。

> **第二次、第三次执行 `sh run.sh` 都是安全的**：脚本会先按 PID 文件停掉旧进程
> （等它自己退出，5 秒不退才 `kill -9`），确认端口腾出来后再启动新的。
> 也就是"重复执行 = 重启"，不会出现两个进程抢端口。

### 改口令（强烈建议）

**第一次启动之前**把口令改掉：

```bash
cd /opt/guardian-server
cp config.env.example config.env
chmod 600 config.env          # 里面是口令，别让别的用户读
vi config.env                 # 改 GUARDIAN_ADMIN_PASSWORD
sh run.sh
```

这个口令只在**第一次启动创建内置管理员 `admin` 时**用到。之后：

* 想改密码 → 登录后台，点右上角「我的账号」；
* 再改 `config.env` 里的这一行**不会生效**（账号已经存在，密码存在数据文件里）。

不设置的话初始口令是 `guardian2026`，登录页会一直红字提醒，直到你改掉。

### 多账号（v2.0 起）

后台支持多账号，登录页填「用户名 + 密码」：

| 角色 | 能看到什么 | 能做什么 |
| --- | --- | --- |
| `admin` | 全部设备 | 所有操作 + 新建/停用/删除账号、替别人生成配对码 |
| 普通账号 | 只有归属自己的设备 | 操作自己的设备、生成自己的配对码 |

设备归属由**配对码**决定：谁生成的码，配出来的平板就归谁。所以给家里另一个人的
平板配设备时，管理员可以在「添加设备」里先选好归属账号。

管理入口：右上角「账号管理」（只有管理员看得到）。注意删除账号前要先决定名下设备转给谁，
否则系统会拒绝删除并提示。

---

## 二、常用命令

| 命令 | 作用 |
| --- | --- |
| `sh run.sh` | 启动；已在运行则重启 |
| `sh stop.sh` | **停止**（升级前必须先跑这个，见第三节） |
| `sh start.sh` | 启动（等价 `sh run.sh start`） |
| `sh status.sh` | 是否在跑、PID、数据库位置与体积 |
| `sh run.sh status` | 同上（只是不带数据库体积） |
| `sh run.sh logs` | 跟踪日志（Ctrl+C 只退出查看，不影响服务） |
| `sh run.sh logs 100` | 只看最后 100 行 |
| `sh run.sh migrate` | 把旧版 `data/state.json` 导入 SQLite（会先停服） |
| `sh run.sh backup` | 备份数据库到 `backup/guardian-<时间>.db` |

---

## 二·五、升级（v2.2 起重点看，v2.3 同样适用）
以前你遇到过的那个「传不上去，提示正在使用」，是因为**进程还在跑**——
Linux 不允许覆盖正在执行的二进制文件（`Text file busy`）。所以升级顺序是：

```bash
# 1. 先停
cd /opt/guardian-server && sh stop.sh

# 2. 再传（这时文件没人占用了）
scp guardian-server-2.3.1-linux-x86_64.tar.gz root@服务器:/opt/

# 3. 解包覆盖（data/ 和 config.env 不在包里，不会被覆盖）
cd /opt && tar xzf guardian-server-2.3.1-linux-x86_64.tar.gz

# 4. 起
cd /opt/guardian-server && sh run.sh
```

第 4 步会自动做**数据迁移**：如果发现旧的 `data/state.json`，
先把它导进 `data/guardian.db`，再把原文件改名成 `state.json.migrated-<时间戳>`。

想先确认数据都在、再启动服务，第 4 步换成：

```bash
sh run.sh migrate     # 停服 → 迁移 → 打印导入了多少
sh run.sh             # 确认无误后再启动
```

> 或者直接用 `python deploy/deploy.py`，它把这四步连同备份、回滚都做完了。

### v2.3 的额外一步：平板上的 App 也要跟着升

服务器端 v2.3 只是**多存了几样东西**（亮屏区间、解锁/开关机计数），
旧 App 连上来不会报错，只是这些数字显示成 `—`。

但"统计时长偏少"那个问题是**App 侧的采集逻辑**造成的，必须把平板上的
App 换成 **2.3.1** 才修得掉。装完新 App 后，当天的数字会在几分钟内自动补齐
—— 新的采集每次心跳都会从"今天 0 点"重扫系统事件，不需要额外操作。

---

## 三、开机自启（可选）

`run.sh` 本身不管开机自启，因为它要能在任何环境直接跑。
想开机自动拉起来，用 systemd 一行就够：

```bash
cat >/etc/systemd/system/guardian.service <<'EOF'
[Unit]
Description=Guardian admin server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/guardian-server
ExecStart=/opt/guardian-server/guardian-server
Restart=always
RestartSec=3
Environment=GUARDIAN_ADMIN_PASSWORD=换成你的口令

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now guardian
```

注意：用 systemd 管理时**不要再执行 `sh run.sh`**，两者会互抢端口。
用 systemd 就用 `systemctl restart guardian`。

---

## 四、目录结构

```
guardian-server/
├── guardian-server          静态可执行文件（约 3.4 MB，musl 静态链接，含 SQLite）
├── run.sh                   启动脚本（start / stop / status / logs / migrate / backup）
├── stop.sh                  停止（升级前先跑这个）
├── start.sh                 启动
├── status.sh                状态 + 数据库体积
├── config.env.example       配置样例
├── README.md                本文件
├── backup/                  升级前自动/手动备份放这里
└── data/                    运行后自动生成
    ├── guardian.db          SQLite 主库（设备、账号、事件、时间线、每日统计）
    ├── guardian.db-wal      WAL 日志（正常现象，别单独删）
    ├── guardian.db-shm      WAL 共享内存（同上）
    ├── state.json           旧版数据，只在从 v2.1.x 升级时出现，迁移后会被改名
    ├── server.log           运行日志（超 5 MB 自动转存 server.log.1）
    └── server.pid           进程号，run.sh 靠它做"先停后启"
```

**备份**：`sh run.sh backup`（运行中直接 `cp` 数据库文件会缺数据，因为用了 WAL）。
**升级**：`sh stop.sh` → 覆盖文件 → `sh run.sh`，数据在 `data/` 里不受影响。

### 数据保留多久

| 数据 | 保留 |
| --- | --- |
| 事件 | 365 天 |
| 在线区间 / 前台应用片段 | 90 天 |
| 每日汇总 | 永久（一天一台设备一行） |

清理是后台每小时跑一次，不影响请求。

---

## 五、设备怎么连上来

1. 后台点「添加设备」，得到 **6 位配对码**（10 分钟内有效，只能用一次）；
2. 平板上打开「使用守护」→ 用家长密码进入设置 → 往下找到「**远程管理（可选）**」；
3. 在该区块填三样东西：
   * **服务器地址**：`http://服务器IP:8000`（不带 `/api`，程序会自动去掉多余路径）
   * **配对码**：上面那串 6 位数字
   * **设备名**：显示在后台的名字，例如「孩子平板」
4. 点「配对 / 换一个配对码」。成功后会显示 `已连接 http://…`、设备 ID 和最近同步时间。
5. 之后这台平板每 20 秒心跳一次，顺带取走你在后台点的指令。

> 地址框允许写 `192.168.1.10:8000`，会自动补 `http://`；
> 用了 HTTPS 反代就写 `https://你的域名`。
> App 端允许明文 HTTP（`usesCleartextTraffic`），否则局域网部署根本连不上。

指令是**设备主动来取**的，不是服务端推送 —— 家里平板在 NAT 后面，没有公网地址，
拿不到推送通道（国内也收不到 FCM），所以只有"设备来问"这一条路可靠。
代价是：你在后台点完按钮，最久要等 20 秒设备才执行。

### 一个特意做的设计

在 App 里「关闭守护」之后，如果已经配对了后台，服务**不会真的退出**，
而是进入"远程待命"：通知栏显示「守护已暂停 · 远程待命中」，心跳继续发。

原因：服务一停就再没有心跳，你在后台点「开启守护」永远送不到设备上，
只能人跑过去手动开。代价是这种情况下前台通知还在，但遮罩和计时确实都停了。
没配后台的机器不受影响，关闭即完全停止。

---

## 六、安全（重要）

- **这个二进制只提供 HTTP，不带 TLS**。它使用的 TLS 库要么依赖 C 代码（
  会破坏"Windows 上一条命令交叉编译"这个前提），要么还在试验阶段，所以没有内置。
- 如果只是家里局域网用，直接跑没问题。
- **如果要暴露到公网**，请在前面挂一层 HTTPS 反代（Caddy 一个文件就能跑，
  而且自动申请证书）：

  ```caddy
  # Caddyfile
  guardian.你的域名.com {
      reverse_proxy 127.0.0.1:8000
  }
  ```

  然后把 `config.env` 里的 `GUARDIAN_HOST` 改成 `127.0.0.1`，
  只让反代能访问 —— 否则别人可以绕过 HTTPS 直连 8000 端口抓明文口令。

- 后台登录有频率限制：同一 IP 5 分钟内错 8 次就锁 5 分钟；
  登录态默认 7 天，每次操作自动续期。
- 口令比较是常量时间的，不会因为"前几位对了"而提前返回。

---

## 七、常见问题

**启动失败：`监听 0.0.0.0:8000 失败：Address already in use`**
已经有进程占着 8000。`sh run.sh stop` 再启动；或者改 `config.env` 里的端口。
也可能是别的程序占了，用 `ss -lntp | grep 8000` 看是谁。

**浏览器打不开，但服务在跑**
服务器防火墙/安全组没放行端口。云服务器要去控制台的安全组里加一条入站规则（TCP 8000）。
`sh run.sh status` 显示运行中 + `curl 127.0.0.1:8000/healthz` 在本机通，就是防火墙问题。

**`Permission denied` 或提示不是可执行文件**
从 Windows 拷过来的文件没有可执行位。`chmod +x guardian-server run.sh`，
或者直接用 `sh run.sh`（脚本会自己补可执行位）。

**日志里出现 `[存储] 打不开数据库 …` 然后退出**
目录不存在或没有写权限。`mkdir -p /opt/guardian-server/data && chmod 755` 后再启动。

**数据库文件损坏（极少见，通常是磁盘故障）**
`sh stop.sh` 停服，从 `backup/` 里挑一份最近的备份覆盖回 `data/guardian.db`，
再 `sh run.sh`。所以建议升级前都跑一次 `sh run.sh backup`（`deploy.py` 会自动备份）。

**升级时提示"正在使用 / Text file busy"，文件传不上去**
老进程还在跑，Linux 不允许覆盖正在执行的二进制。先 `sh stop.sh`，再传。
（`python deploy/deploy.py` 会自动处理这一步，不用记。）

**升级后想知道旧数据有没有搬过来**
看启动日志，会有这样几行：

```
[迁移] 发现旧版数据 /opt/guardian-server/data/state.json，开始导入 SQLite：1 台设备 / 2 个账号 / 1832 条事件 / 9640 条片段
[迁移] 完成。原文件已改名为 data/state.json.migrated-1761xxxxxx
[存储] SQLite 已就绪 · 1 台设备 · 2 个账号 · 1832 条事件 · 9640 条应用片段 · 214 段在线
```

确认没问题后，那个 `.migrated-*` 文件可以删掉，它不会再被读取。

**`data/` 里多出 `guardian.db-wal` 和 `guardian.db-shm`**
正常。WAL 模式的预写日志，程序退出时会自己合并回主库。
**别单独删这两个文件**，也别在服务运行时直接 `cp` 主库 —— 用 `sh run.sh backup`。

**事件会不会越积越多把磁盘占满**
不会。事件保留 365 天、时间线 90 天，后台每小时自动清理一次。
按一天 2000 条事件估算，一年约 70 万条，库文件大概几百 MB 到 1 GB 量级，
比原来 JSON 全量重写要省得多（JSON 版本几十万条就会卡）。

**`state.json` 解析失败 / 迁移失败**
迁移失败时**原文件会保留、不会删**，服务照常启动（用空库）。
日志里会写明原因。修好 JSON 后再 `sh run.sh migrate` 重试。

**我的服务器是 ARM（比如某些云厂商的 arm 实例）**
当前包是 x86_64。需要的话在本地加一个目标重新编译即可：
`rustup target add aarch64-unknown-linux-musl`，然后改 `build.sh` 里的 `TARGET`
和 `ZIG_TARGET`（`aarch64-linux-musl`）。

**会不会把平板数据传到外网？**
只传这些：守护开关、是否在休息、本轮/今日使用时长、休息次数、
`use_minutes` 等配置、屏幕是否亮着、是否有音频被压制、App 版本，
以及**在你授权「使用情况访问」后**的前台应用包名和名称（用于时间线）。
**不传任何屏幕内容、不传密码**（只传"是否设过密码"这个布尔值）。
这些数据全部留在你自己的服务器上。
