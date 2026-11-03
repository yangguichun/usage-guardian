"""一次性脚本：把 test_api.py 传到服务器，在隔离目录里跑一遍接口自测。

跑完即可删。它不碰生产实例：test_api.py 自己用 18999 端口 + /tmp/gtest/tmp-test
的独立数据库。
"""
import os
import sys

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def load_env():
    cfg = {}
    path = os.path.join(HERE, "deploy.env")
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            cfg[k.strip()] = v.strip().strip('"').strip("'")
    return cfg


def main():
    cfg = load_env()
    host = cfg["GUARDIAN_SSH_HOST"]
    port = int(cfg.get("GUARDIAN_SSH_PORT", "22"))
    user = cfg.get("GUARDIAN_SSH_USER", "root")
    pwd = cfg.get("GUARDIAN_SSH_PASSWORD") or None
    key = cfg.get("GUARDIAN_SSH_KEY") or None
    remote_dir = cfg.get("GUARDIAN_REMOTE_PATH", "/home/child-guard/guardian-server")
    binpath = remote_dir.rstrip("/") + "/guardian-server"

    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(host, port=port, username=user, password=pwd,
                key_filename=key if key else None, timeout=20)
    cli.get_transport().set_keepalive(15)

    sftp = cli.open_sftp()
    for cmd in ["rm -rf /tmp/gtest", "mkdir -p /tmp/gtest"]:
        cli.exec_command(cmd)[1].channel.recv_exit_status()
    sftp.put(os.path.join(ROOT, "test_api.py"), "/tmp/gtest/test_api.py")
    sftp.close()

    cmd = f"cd /tmp/gtest && /usr/bin/python3 test_api.py {binpath} 2>&1 | tail -200"
    stdin, stdout, stderr = cli.exec_command(cmd, timeout=600)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    print(out[-12000:])
    if err.strip():
        print("[stderr]", err[-2000:])
    print("[exit]", code)

    # 收尾：删掉临时目录，别在服务器上留垃圾
    cli.exec_command("rm -rf /tmp/gtest")[1].channel.recv_exit_status()
    cli.close()


if __name__ == "__main__":
    sys.exit(main())
