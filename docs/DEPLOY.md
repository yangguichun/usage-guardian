# 部署速查

完整说明见 [`GuardianServer/deploy/README-deploy.md`](../GuardianServer/deploy/README-deploy.md)。
这里只放最短路径和安全红线。

## 最短路径

```bash
# 1. 下载 Release 里的 guardian-server-2.4.2-linux-x86_64.tar.gz
tar xzf guardian-server-2.4.2-linux-x86_64.tar.gz
cd guardian-server

# 2. 启动（口令必须换掉）
GUARDIAN_ADMIN_PASSWORD='换成强口令' ./run.sh

# 3. 健康检查
./status.sh
curl -s http://127.0.0.1:8000/api/me | head
```

二进制是静态链接的单文件，SQLite 和前端页面都编进去了，目标机不需要装 Rust、SQLite 或 nginx。

## 一键远程部署

```bash
cd GuardianServer
cp deploy/deploy.env.example deploy/deploy.env   # 填自己的 SSH 信息
python deploy/deploy.py --check                  # 先体检，不改任何东西
python deploy/deploy.py                          # 停 → 备份 → 传 → 迁移 → 启 → 健康检查 → 回滚
```

`deploy.env` 含 SSH 口令，已在 `.gitignore` 中排除。

## 安全红线

1. **服务端不内置 TLS**，8000 端口是明文 HTTP。公网部署必须套 HTTPS 反向代理，
   并用防火墙限制 8000 只对本机 / 内网开放。
2. **默认口令 `guardian2026` 必须改**。登录页会一直红字提醒，直到改掉。
3. 服务器没有 systemd 时靠 `run.sh` + nohup 拉起；重启机器后要手动再跑一次，
   建议自行加一个开机自启单元。
4. 升级前一定先 `--check`。`GUARDIAN_REMOTE_PATH` 写错不会报错，会在一个空目录里
   全新装一份，然后因为 8000 被老进程占着起不来。
5. JSON → SQLite 首次升级会把 `state.json` 改名。**回滚必须把它放回**，否则旧版读到空数据。

## 验证

```bash
# 接口回归（231 条断言，不碰生产数据）
python test_api.py

# 前端布局自检（jsdom，92 条断言）
node preview/ui-check.js

# 生产机远程跑接口回归（脚本会上传到 /tmp 跑完自删）
python deploy/_runtests.py
```

## 前端改动上线 checklist

- [ ] 改完 `static/` 后**重新编译二进制**（`include_str!` 是编译期打包）
- [ ] 核对二进制时间戳比 `static/app.js` 新
- [ ] `preview/mock-server.js` 给新面板补演示数据，否则断言看不出空态问题
- [ ] `?measure` + `--dump-dom` 核对 1440 / 1024 / 768 / 390 四种宽度无横向溢出
