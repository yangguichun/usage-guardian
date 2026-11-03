#!/usr/bin/env python3
"""部署前诊断：把服务器上"现在到底跑着什么、装在哪、数据在哪"一次列清楚。

只读，不改任何东西。常用于这几种情况：

  * 不确定 GUARDIAN_REMOTE_PATH 该填什么（写错会在空目录里重装一份）；
  * 服务起不来 / 端口被占，想看是谁占着；
  * 想知道数据是 JSON 还是 SQLite、有多大。

用法：
    .venv/Scripts/python.exe deploy/diag.py

配置从同目录的 deploy.env 读（只需要 HOST / PORT / USER / PASSWORD）。
"""
from __future__ import annotations

import os
import posixpath
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))


def load_env() -> dict:
    cfg = {}
    path = os.path.join(HERE, "deploy.env")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    return cfg


def build_cmds(p: str):
    q = posixpath  # 只是为了让下面读起来短一点
    return [
        ("监听 8000 / 8080",
         "ss -ltnp 2>/dev/null | grep -E ':8000|:8080' "
         "|| netstat -tlnp 2>/dev/null | grep -E ':8000|:8080' || echo '(无)'"),
        ("相关进程", "ps -ef | grep -iE 'guardian' | grep -v grep || echo '(无)'"),
        ("进程工作目录",
         "for pid in $(pgrep -f guardian-server); do "
         "echo \"PID $pid -> $(readlink /proc/$pid/cwd 2>/dev/null)\"; done || echo '(无)'"),
        ("systemd 单元",
         "systemctl list-units --type=service --all 2>/dev/null | grep -i guardian || echo '(无)'"),
        (f"配置里的目录 {p}", f"ls -la {q.join(p, '')} 2>/dev/null || echo '(不存在)'"),
        ("该目录下的 data/", f"ls -la {q.join(p, 'data')} 2>/dev/null || echo '(不存在)'"),
        ("该目录下的 config.env", f"cat {q.join(p, 'config.env')} 2>/dev/null || echo '(不存在)'"),
        ("全盘找二进制",
         "find /home /opt /root /usr/local -maxdepth 4 -name 'guardian-server' -type f "
         "2>/dev/null || echo '(没找到)'"),
        ("本地健康检查", "curl -s -m 3 http://127.0.0.1:8000/healthz || echo '(无响应)'"),
        ("系统信息", "uname -sm; echo; head -2 /etc/os-release 2>/dev/null; echo; "
                     "free -m | head -2; echo; df -Ph /home 2>/dev/null | tail -1"),
    ]


def main() -> int:
    env = load_env()
    host = env.get("GUARDIAN_SSH_HOST", "")
    if not host:
        print("deploy.env 里没有 GUARDIAN_SSH_HOST", file=sys.stderr)
        return 2
    user = env.get("GUARDIAN_SSH_USER", "root")
    pwd = env.get("GUARDIAN_SSH_PASSWORD", "")
    port = int(env.get("GUARDIAN_SSH_PORT", "22"))
    path = env.get("GUARDIAN_REMOTE_PATH", "/opt/guardian-server")

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(hostname=host, port=port, username=user, password=pwd,
              timeout=15, banner_timeout=20, auth_timeout=20, look_for_keys=False,
              allow_agent=False)
    try:
        print(f"诊断 {user}@{host}:{port}  配置里的目录：{path}\n")
        for title, cmd in build_cmds(path):
            _, stdout, stderr = c.exec_command(cmd, timeout=25)
            out = (stdout.read() + stderr.read()).decode("utf-8", "replace").strip()
            print(f"===== {title} =====")
            print(out if out else "(空)")
            print()
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
