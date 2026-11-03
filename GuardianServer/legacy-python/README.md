# 使用守护 · 管理后台（服务端）

平板/手机上的「使用守护」每 20 秒向这里上报一次状态，你在浏览器里就能看到统计、
远程锁定/解锁/改配置。

- 技术栈：FastAPI + SQLite（单文件数据库，不需要额外装数据库）
- 前端：一个静态页面，无框架、无 CDN、不联网取任何资源（内网也能用）
- 部署：一个 `uvicorn` 进程即可，数据量很小（一年也就几 MB）

---

## 一、本地跑起来（30 秒）

```bash
cd GuardianServer
python -m venv .venv
# Windows
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m uvicorn main:app --host 0.0.0.0 --port 8000

# Linux / macOS
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

浏览器打开 <http://127.0.0.1:8000>，口令默认 `guardian2026`。

> 第一次启动时会提示"正在使用默认口令"，页面上也会有一条黄色横幅提醒。

---

## 二、部署到服务器（推荐 systemd + Nginx）

### 1. 放代码、建虚拟环境

```bash
sudo mkdir -p /opt/guardian && sudo chown $USER /opt/guardian
# 把本目录内容传上去：scp -r GuardianServer/* root@你的服务器:/opt/guardian/
cd /opt/guardian
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

### 2. systemd 常驻

`/etc/systemd/system/guardian.service`：

```ini
[Unit]
Description=Guardian admin backend
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/guardian
Environment=GUARDIAN_ADMIN_PASSWORD=换成你自己的强口令
Environment=GUARDIAN_TZ_OFFSET=8
ExecStart=/opt/guardian/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now guardian
sudo systemctl status guardian
```

### 3. Nginx 反代 + HTTPS（强烈建议）

```nginx
server {
    listen 443 ssl http2;
    server_name guardian.example.com;

    ssl_certificate     /etc/letsencrypt/live/guardian.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/guardian.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

```bash
sudo certbot --nginx -d guardian.example.com
```

> **为什么建议上 HTTPS**：设备令牌和管理员口令都放在请求头里，
> 明文 HTTP 在公网上等于公开。如果只在家庭内网用，HTTP 也能接受。

---

## 三、环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GUARDIAN_ADMIN_PASSWORD` | `guardian2026` | 后台登录口令，**务必改掉** |
| `GUARDIAN_DB` | `./guardian.db` | SQLite 文件路径 |
| `GUARDIAN_TZ_OFFSET` | `8` | 统计"今天"用的时区偏移（小时），默认北京时间 |
| `GUARDIAN_SESSION_DAYS` | `7` | 登录有效期（天），每次操作自动续期 |
| `GUARDIAN_HOST` / `GUARDIAN_PORT` | `0.0.0.0` / `8000` | 仅 `python main.py` 启动时生效 |

数据备份就是把 `guardian.db`（以及可能存在的 `guardian.db-wal`）拷走。

---

## 四、平板端怎么接入

1. 后台点右上角 **添加设备** → 生成 6 位配对码（10 分钟有效，一次性）。
2. 平板上打开「使用守护」→ 滚到 **远程管理**：
   - 服务器地址：`https://guardian.example.com`（或 `http://192.168.1.10:8000`）
   - 配对码：刚才那 6 位
   - 点「连接」。
3. 后台设备列表里就会出现这台设备，之后每 20 秒刷新一次状态。

设备侧保存的是一个 `device_token`，和后台登录口令是两套东西：
换管理员口令不需要重新配对。

---

## 五、能做什么

| 功能 | 页面位置 | 说明 |
| --- | --- | --- |
| 看统计 | 首页四张卡 + 近 7 天柱状图 | 今日总使用时长、今日休息次数、在线/离线、守护关闭数 |
| 看单台详情 | 设备卡片「详情 / 配置」 | 今日/本轮使用、14 天趋势、指令历史、事件流 |
| 远程锁定 | 卡片「锁定 N 分钟」 | 立刻全屏遮罩，倒计时结束前孩子无法使用 |
| 解除锁定 | 卡片「解除锁定」 | 跳过本次休息 |
| 暂停 / 开启守护 | 卡片按钮 | 相当于在设备上拨那个总开关 |
| 改配置 | 详情里的表单 | 连续使用时长、休息时长、重新计时阈值、强杀惩罚、休息静音策略 |

指令是**排队下发**的：点完按钮，设备下一次心跳（≤20 秒）取走并执行，
执行结果会回报到事件流（成功/失败都记）。设备离线时指令会一直等着。

---

## 六、API 一览（想自己接别的客户端时看）

管理端（需要 `Authorization: Bearer <登录 token>`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/login` | `{password}` → `{token}` |
| POST | `/api/logout` | 注销当前 token |
| GET | `/api/overview` | 首页全部数据（统计 + 设备 + 7 天趋势 + 事件） |
| GET | `/api/devices/{id}` | 单台详情（含 14 天、指令、事件） |
| POST | `/api/devices/{id}/command` | `{type, params}`，type ∈ lock / unlock / pause / enable / config / sync |
| POST | `/api/pair-codes` | 生成配对码 |

设备端（需要 `Authorization: Bearer <device_token>`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/device/pair` | `{code, name, model, app_version}` → `{device_id, token}` |
| POST | `/api/device/heartbeat` | 上报状态，响应里带待执行指令 |
| POST | `/api/device/result` | `{command_id, ok, message}` 回执 |

在线调试文档：`/api/docs`（由 FastAPI 自动生成，**上公网建议在 Nginx 里屏蔽**）。

---

## 七、安全说明（实话）

1. **口令就是全部防线**。请用 12 位以上的随机串，别用生日。
2. 登录有简单的失败限流（同 IP 5 分钟 8 次失败即锁定），但这挡不住分布式撞库，
   所以口令强度仍然是关键。
3. 设备令牌是随机 32 字节，只能读到自己那台设备的状态；设备接口不能读别人的数据，
   也没有"列出所有设备"的接口。
4. 本项目**没有做**多管理员、审计导出、设备删除（后续需要可以加）。
5. 建议给 Nginx 加一层 `allow/deny` 或 basic auth，尤其当服务暴露在公网时。
