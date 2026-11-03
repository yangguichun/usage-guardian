#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「使用守护」服务端 —— 一键远程部署

在你**自己的电脑上**运行，通过 SSH 连到服务器完成整套升级：

    停服 → 备份 → 上传 → 解包 → 数据迁移 → 启动 → 健康检查

任何一步失败都会自动回滚到上一版（二进制 + 数据库一起还原）。
全程不需要你手动传文件，也不会出现「正在使用 / Text file busy」。

------------------------------------------------------------
准备
------------------------------------------------------------
    python -m venv .venv
    .venv/Scripts/python.exe -m pip install paramiko     # Windows
    .venv/bin/python -m pip install paramiko             # Linux / macOS

------------------------------------------------------------
配置（优先级：命令行 > 环境变量 > deploy.env 文件）
------------------------------------------------------------
先在 deploy/deploy.env 里填好（推荐，填一次以后直接跑）：

    GUARDIAN_SSH_HOST=1.2.3.4
    GUARDIAN_SSH_PORT=22
    GUARDIAN_SSH_USER=root
    GUARDIAN_SSH_PASSWORD=你的密码
    GUARDIAN_REMOTE_PATH=/opt/guardian-server
    GUARDIAN_HTTP_PORT=8000

或者全部用命令行：

    python deploy.py --host 1.2.3.4 --user root --password 'xxx' --path /opt/guardian-server

用密钥登录：

    python deploy.py --host 1.2.3.4 --user root --key ~/.ssh/id_rsa

只要体检、不动任何东西：

    python deploy.py --check

------------------------------------------------------------
常用参数
------------------------------------------------------------
    --tarball PATH   指定要部署的包（默认取 dist/ 下版本号最大的那个）
    --http-port N    服务端口，用于健康检查（默认 8000）
    --keep 3         保留最近几份备份（默认 3）
    --no-rollback    失败时不回滚（排障用）
"""

from __future__ import annotations

import argparse
import glob
import os
import posixpath
import re
import shlex
import sys
import time
from datetime import datetime

try:
    import paramiko
except ImportError:  # pragma: no cover
    sys.exit(
        "缺少 paramiko。装一下：\n"
        "    pip install paramiko\n"
        "（国内网络慢可以加：-i https://mirrors.aliyun.com/pypi/simple/）"
    )

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)          # GuardianServer/


# ======================================================================
# 小工具
# ======================================================================

def say(msg=""):
    print(msg, flush=True)


def step(msg):
    say()
    say("▶ " + msg)


def ok(msg):
    say("  ✔ " + msg)


def warn(msg):
    say("  ! " + msg)


# ======================================================================
# 配置
# ======================================================================

def load_env_file(path):
    data = {}
    if not path or not os.path.isfile(path):
        return data
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            data[k.strip()] = v.strip().strip('"').strip("'")
    return data


def build_config(args):
    env = load_env_file(os.path.join(HERE, "deploy.env"))

    def pick(arg_val, env_key, default=""):
        if arg_val not in (None, ""):
            return arg_val
        if env_key in os.environ and os.environ[env_key] != "":
            return os.environ[env_key]
        if env.get(env_key, "") != "":
            return env[env_key]
        return default

    cfg = {
        "host": pick(args.host, "GUARDIAN_SSH_HOST"),
        "port": int(pick(args.port, "GUARDIAN_SSH_PORT", "22")),
        "user": pick(args.user, "GUARDIAN_SSH_USER", "root"),
        "password": pick(args.password, "GUARDIAN_SSH_PASSWORD"),
        "key": pick(args.key, "GUARDIAN_SSH_KEY"),
        "path": normalize_remote_path(pick(args.path, "GUARDIAN_REMOTE_PATH", "/opt/guardian-server")),
        "http_port": int(pick(args.http_port, "GUARDIAN_HTTP_PORT", "8000")),
    }
    return cfg


def normalize_remote_path(p):
    """把安装目录规整成干净的 Linux 绝对路径。

    坑：在 Git Bash / MSYS2 里用 `--path /opt/x` 传参时，**Windows 会把以 / 开头的
    参数当成路径自动改写成 `C:/Program Files/Git/opt/x`**。这会让脚本往一个
    根本不存在的目录里传文件，报错还很难看懂。这里识别出来直接说清楚。

    最省事的办法是别用命令行传路径，写进 deploy/deploy.env —— 从文件里读不会
    经过这层改写。
    """
    p = (p or "").strip()
    if not p:
        sys.exit("服务器上的安装目录不能为空")

    if re.match(r"^[A-Za-z]:[\\/]", p) or p.lower().startswith(("c:/program files/git", "/cygdrive/")):
        sys.exit(
            "安装目录被 Windows/Git Bash 改写成了本机路径：\n"
            f"    {p}\n"
            "以 / 开头的参数会被 MSYS 自动转换。请改成写进 deploy/deploy.env：\n"
            "    GUARDIAN_REMOTE_PATH=/opt/guardian-server\n"
            "或者在传参前关掉转换：MSYS_NO_PATHCONV=1 python deploy.py --path /opt/guardian-server"
        )

    if not p.startswith("/"):
        p = "/" + p
    # 去掉结尾的斜杠（保留根目录）
    return p.rstrip("/") or "/"


def find_tarball(explicit):
    if explicit:
        if not os.path.isfile(explicit):
            sys.exit(f"找不到发布包：{explicit}")
        return os.path.abspath(explicit)

    pat = os.path.join(ROOT, "dist", "guardian-server-*-linux-x86_64.tar.gz")

    def ver(p):
        m = re.search(r"guardian-server-([\d.]+)-linux", os.path.basename(p))
        return tuple(int(x) for x in m.group(1).split(".")) if m else (0,)

    files = sorted(glob.glob(pat), key=ver)
    if not files:
        sys.exit("dist/ 里没有发布包，先跑一次 bash build.sh")
    return files[-1]


# ======================================================================
# SSH
# ======================================================================

class Remote:
    def __init__(self, cfg, verbose=False):
        self.cfg = cfg
        self.verbose = verbose
        self.client = paramiko.SSHClient()
        # 内网服务器第一次连通常还没有 known_hosts 记录。
        # 这里接受新主机，但会把指纹打出来，方便你核对。
        self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self._connect()

    def _connect(self):
        c = self.cfg
        kw = dict(
            hostname=c["host"],
            port=c["port"],
            username=c["user"],
            timeout=20,
            banner_timeout=20,
            auth_timeout=20,
            allow_agent=False,
            look_for_keys=False,
        )
        if c["key"]:
            kw["key_filename"] = os.path.expanduser(c["key"])
            if c["password"]:
                kw["passphrase"] = c["password"]
        else:
            kw["password"] = c["password"]
        self.client.connect(**kw)
        tr = self.client.get_transport()
        if tr:
            # 跨公网上传时，中间设备常把"静默超过 N 秒"的连接掐掉
            # （实测传 1.7MB 中途被重置过一次）。每 15 秒发个心跳包，
            # 让链路一直有流量，比事后重试更有效。
            tr.set_keepalive(15)
        self.sftp = self.client.open_sftp()

    def reconnect(self):
        try:
            self.sftp.close()
        except Exception:
            pass
        try:
            self.client.close()
        except Exception:
            pass
        self.client = paramiko.SSHClient()
        self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self._connect()

    def fingerprint(self):
        t = self.client.get_transport()
        if not t:
            return "?"
        key = t.get_remote_server_key()
        return f"{key.get_name()} {key.get_base64()[:24]}…"

    def run(self, cmd, timeout=300, check=True, _retry=True):
        if self.verbose:
            say("    $ " + cmd)
        try:
            _, stdout, stderr = self.client.exec_command(cmd, timeout=timeout)
            out = stdout.read().decode("utf-8", "replace")
            err = stderr.read().decode("utf-8", "replace")
            code = stdout.channel.recv_exit_status()
        except (paramiko.SSHException, OSError) as e:
            if not _retry:
                raise
            warn(f"连接断了（{type(e).__name__}: {e}），重连后重试这条命令…")
            self.reconnect()
            return self.run(cmd, timeout=timeout, check=check, _retry=False)
        if self.verbose and (out.strip() or err.strip()):
            for line in (out + err).strip().splitlines():
                say("      " + line)
        if check and code != 0:
            raise RuntimeError(f"远端命令失败（exit {code}）：\n  {cmd}\n{out}\n{err}")
        return code, out, err

    def cd(self, path, cmd, **kw):
        return self.run(f"cd {shlex.quote(path)} && {cmd}", **kw)

    def put(self, local, remote, attempts=3):
        last = None
        for i in range(1, attempts + 1):
            try:
                self.sftp.put(local, remote)
                return
            except Exception as e:  # noqa: BLE001 - 网络层的异常五花八门
                last = e
                if i >= attempts:
                    break
                warn(f"上传中断（{type(e).__name__}: {e}），第 {i}/{attempts} 次，重连后重试…")
                time.sleep(2)
                try:
                    self.reconnect()
                except Exception as e2:  # noqa: BLE001
                    warn(f"重连也失败了：{type(e2).__name__}: {e2}")
                    time.sleep(3)
        raise RuntimeError(
            f"上传失败（{attempts} 次都没成功）：{type(last).__name__}: {last}\n"
            f"  本地文件：{local}（{os.path.getsize(local)} 字节）\n"
            f"  网络实在太差的话，可以先 scp 到服务器 /tmp 确认能传，再重跑本脚本。"
        )

    def close(self):
        try:
            self.sftp.close()
        except Exception:
            pass
        try:
            self.client.close()
        except Exception:
            pass


# ======================================================================
# 体检
# ======================================================================

def precheck(r, cfg):
    step("检查服务器环境")

    _, out, _ = r.run("uname -s && uname -m")
    lines = [x.strip() for x in out.strip().splitlines() if x.strip()]
    sysname = lines[0] if lines else "?"
    arch = lines[1] if len(lines) > 1 else "?"
    if sysname != "Linux":
        raise RuntimeError(f"目标机不是 Linux（{sysname}）")
    if arch not in ("x86_64", "amd64"):
        raise RuntimeError(f"目标机架构是 {arch}，这个包只支持 x86_64")
    ok(f"{sysname} / {arch}")

    # 磁盘剩余
    p = cfg["path"]
    probe = p
    while probe and not os.path.dirname(probe) == probe:
        code, _, _ = r.run(f"test -d {shlex.quote(probe)}", check=False)
        if code == 0:
            break
        probe = posixpath.dirname(probe)
    probe = probe or "/"
    _, out, _ = r.run(f"df -Pk {shlex.quote(probe)} 2>/dev/null | tail -n 1", check=False)
    parts = out.split()
    if len(parts) >= 4:
        try:
            free_mb = int(parts[3]) // 1024
            ok(f"{probe} 剩余空间 {free_mb} MB")
            if free_mb < 50:
                warn("剩余空间不足 50MB，备份可能失败")
        except ValueError:
            pass

    # 旧版本情况
    existed = r.run(f"test -f {shlex.quote(posixpath.join(p, 'guardian-server'))}",
                    check=False)[0] == 0
    if existed:
        # v2.2 之前的二进制没有 --version 子命令，直接执行会**真的启动一次**
        # （占住端口、还可能把内存状态写回数据文件）。所以这里只从二进制里
        # 找版本串，绝不运行它。
        _, out, _ = r.run(
            f"strings -a {shlex.quote(posixpath.join(p, 'guardian-server'))} 2>/dev/null "
            f"| grep -oE '(^|[^0-9.])[0-9]+\\.[0-9]+\\.[0-9]+([^0-9.]|$)' "
            f"| grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+' | sort | uniq -c | sort -rn | head -n 1",
            check=False)
        guess = out.split()[-1] if out.strip() else ""
        ok(f"已安装{('（二进制里的版本串：' + guess + '，可能是依赖版本）') if guess else ''}"
           "，将原地升级")
        db = posixpath.join(p, "data", "guardian.db")
        dbj = posixpath.join(p, "data", "state.json")
        if r.run(f"test -f {shlex.quote(db)}", check=False)[0] == 0:
            _, out, _ = r.run(f"du -h {shlex.quote(db)} 2>/dev/null | cut -f1", check=False)
            ok(f"现有数据库：{db}（{out.strip()}）")
        elif r.run(f"test -f {shlex.quote(dbj)}", check=False)[0] == 0:
            _, out, _ = r.run(f"du -h {shlex.quote(dbj)} 2>/dev/null | cut -f1", check=False)
            ok(f"发现旧版 JSON：{dbj}（{out.strip()}），启动时会自动导入 SQLite")
    else:
        ok(f"{p} 还没有安装过，将做首次部署")

    return existed


def show_status(r, cfg):
    step("当前状态")
    p = cfg["path"]
    if r.run(f"test -f {shlex.quote(posixpath.join(p, 'run.sh'))}", check=False)[0] == 0:
        _, out, _ = r.cd(p, "sh run.sh status 2>&1 | head -n 8", check=False)
        for line in out.strip().splitlines():
            say("  " + line)
    code, out, _ = r.run(
        f"curl -fsS http://127.0.0.1:{cfg['http_port']}/healthz 2>/dev/null || echo __down__",
        check=False)
    if "__down__" in out:
        warn("健康检查接口无响应（服务可能没在跑，或端口不是 "
             f"{cfg['http_port']}）")
    else:
        ok("健康检查通过：" + out.strip()[:80])


# ======================================================================
# 部署
# ======================================================================

def deploy(r, cfg, tarball, existed, keep_backups, rollback=True):
    p = cfg["path"]
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    remote_tgz = f"/tmp/guardian-server-{ts}.tar.gz"
    stage = f"/tmp/guardian-stage-{ts}"
    backup = posixpath.join(p, "backup", ts)
    did_stop = False
    replaced = False

    try:
        # ---- 1. 上传 ----
        step("上传发布包")
        size_mb = os.path.getsize(tarball) / 1024 / 1024
        r.put(tarball, remote_tgz)
        ok(f"{os.path.basename(tarball)}（{size_mb:.1f} MB）→ {remote_tgz}")

        # ---- 2. 远端解包到临时目录 ----
        step("解包到临时目录")
        r.run(f"rm -rf {shlex.quote(stage)} && mkdir -p {shlex.quote(stage)} && "
              f"tar xzf {shlex.quote(remote_tgz)} -C {shlex.quote(stage)}")
        r.run(f"chmod +x {shlex.quote(stage)}/guardian-server/guardian-server "
              f"{shlex.quote(stage)}/guardian-server/*.sh 2>/dev/null || true")
        ok("解包完成")

        # ---- 3. 停服 ----
        step("停止旧服务")
        r.run(f"mkdir -p {shlex.quote(p)}")
        if existed:
            code, _, _ = r.cd(p, "test -f stop.sh && sh stop.sh", check=False)
            if code != 0:
                r.cd(p, "test -f run.sh && sh run.sh stop", check=False)
            did_stop = True
            ok("已停止")
        else:
            ok("首次部署，无需停止")

        # ---- 4. 备份 ----
        if existed:
            step("备份旧版本")
            r.run(f"mkdir -p {shlex.quote(backup)}")
            r.run(f"cd {shlex.quote(p)} && "
                  f"cp -a guardian-server {shlex.quote(backup)}/ 2>/dev/null || true")
            # 整个 data/ 一起带走：只拷 db 的话，JSON → SQLite 首次升级后
            # 迁移过程把 state.json 改了名，回滚时旧二进制会找不到任何设备。
            r.run(f"cd {shlex.quote(p)} && "
                  f"cp -a data {shlex.quote(backup)}/ 2>/dev/null || true")
            r.run(f"cd {shlex.quote(backup)} && rm -f data/server.pid && exit 0", check=False)
            r.run(f"cd {shlex.quote(p)} && "
                  f"cp -a run.sh stop.sh start.sh status.sh config.env "
                  f"{shlex.quote(backup)}/ 2>/dev/null || true")
            ok(f"已备份到 {backup}")

        # ---- 5. 替换文件（data/ 与 config.env 不动）----
        step("安装新版本")
        src = f"{stage}/guardian-server"
        for f in ("guardian-server", "run.sh", "stop.sh", "start.sh", "status.sh",
                  "README.md"):
            r.run(f"cp -f {shlex.quote(src)}/{f} {shlex.quote(p)}/")
        replaced = True
        r.run(f"chmod 755 {shlex.quote(p)}/guardian-server {shlex.quote(p)}/*.sh")
        if r.run(f"test -f {shlex.quote(p + '/config.env')}", check=False)[0] != 0:
            r.run(f"cp -f {shlex.quote(src)}/config.env.example {shlex.quote(p)}/config.env "
                  f"&& chmod 644 {shlex.quote(p)}/config.env")
            warn("已生成 config.env：里面的 GUARDIAN_ADMIN_PASSWORD 只是占位符，")
            warn("只有库里一个账号都没有时才会用到它，记得先改成真正的口令。")
        ok("文件已就位")

        # ---- 6. 数据迁移（幂等，没旧数据就什么都不做）----
        step("数据迁移（JSON → SQLite）")
        _, out, err = r.cd(p, "sh run.sh migrate 2>&1", check=False)
        for line in (out + err).strip().splitlines():
            say("  " + line)
        if "导入失败" in out:
            raise RuntimeError("迁移失败，见上面的输出")

        # ---- 7. 启动 ----
        step("启动服务")
        _, out, err = r.cd(p, "sh run.sh start 2>&1", check=False)
        for line in (out + err).strip().splitlines():
            say("  " + line)
        if "✘" in out:
            raise RuntimeError("启动失败，见上面的输出")

        # ---- 8. 健康检查 ----
        step("健康检查")
        healthy = False
        for i in range(10):
            code, out, _ = r.run(
                f"curl -fsS http://127.0.0.1:{cfg['http_port']}/healthz 2>/dev/null",
                check=False)
            if code == 0 and out.strip():
                ok("通过：" + out.strip()[:100])
                healthy = True
                break
            time.sleep(1)
        if not healthy:
            warn("健康检查没通过，看日志：sh run.sh logs 80")
            raise RuntimeError("服务起来了但健康检查失败")

        # ---- 9. 清理旧备份 ----
        if keep_backups > 0:
            r.run(f"cd {shlex.quote(p)}/backup 2>/dev/null && "
                  f"ls -1dt */ 2>/dev/null | tail -n +{keep_backups + 1} | "
                  f"xargs -r rm -rf; exit 0", check=False)

        say()
        say("=" * 60)
        say("  部署完成")
        say(f"  版本：{os.path.basename(tarball).split('-')[2]}")
        say(f"  目录：{p}")
        say(f"  后台：http://{cfg['host']}:{cfg['http_port']}/")
        if existed:
            say(f"  回滚（整目录一起还原，最稳）：")
            say(f"    cd {p} && sh stop.sh && cp -a {backup}/guardian-server . "
                f"&& cp -a {backup}/data/. data/ && sh run.sh start")
        say("=" * 60)

    except Exception as e:
        say()
        say("✘ 出错了：" + str(e))
        if existed and replaced and rollback:
            say()
            step("回滚到上一版")
            try:
                r.cd(p, "sh stop.sh 2>/dev/null || sh run.sh stop 2>/dev/null", check=False)
                r.run(f"cp -f {shlex.quote(backup)}/guardian-server {shlex.quote(p)}/guardian-server",
                      check=False)
                r.run(f"chmod 755 {shlex.quote(p)}/guardian-server", check=False)
                if r.run(f"test -f {shlex.quote(backup + '/guardian.db')}", check=False)[0] == 0:
                    # 升级时已经把库升级过的话，回滚必须连库一起还原，
                    # 否则旧二进制读不了新表结构。
                    r.run(f"cp -f {shlex.quote(backup)}/guardian.db {shlex.quote(p)}/data/guardian.db",
                          check=False)
                if r.run(f"test -f {shlex.quote(backup + '/data/state.json')}", check=False)[0] == 0:
                    # 这次是 JSON → SQLite 的首次升级：迁移时原文件被改名成
                    # state.json.migrated-<ts>。回滚到旧二进制就得把它找回来，
                    # 顺便删掉新生成的空库，否则旧版看不到任何设备。
                    r.cd(p, f"rm -f data/guardian.db data/guardian.db-wal data/guardian.db-shm; "
                            f"cp -f {shlex.quote(backup)}/data/state.json data/state.json; "
                            f"rm -f data/state.json.migrated-*; exit 0", check=False)
                r.cd(p, "sh run.sh start 2>&1 | tail -n 5", check=False)
                ok("已回滚并重新启动，服务应和升级前一致")
            except Exception as e2:
                say("  ! 回滚也失败了：" + str(e2))
                say(f"    手动处理：把 {backup}/ 里的文件拷回 {p}/ 再 sh run.sh start")
        raise
    finally:
        r.run(f"rm -rf {shlex.quote(stage)} {shlex.quote(remote_tgz)}", check=False)


# ======================================================================
# 入口
# ======================================================================

def main():
    ap = argparse.ArgumentParser(
        description="「使用守护」服务端一键远程部署（SSH）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="配置也可以写在 deploy/deploy.env 里，见脚本顶部说明。",
    )
    ap.add_argument("--host", help="服务器地址")
    ap.add_argument("--port", help="SSH 端口（默认 22）")
    ap.add_argument("--user", help="SSH 用户名（默认 root）")
    ap.add_argument("--password", help="SSH 密码")
    ap.add_argument("--key", help="SSH 私钥路径")
    ap.add_argument("--path", help="服务器上的安装目录")
    ap.add_argument("--http-port", dest="http_port", help="服务端口（健康检查用）")
    ap.add_argument("--tarball", help="指定发布包")
    ap.add_argument("--keep", type=int, default=3, help="保留几份备份（默认 3）")
    ap.add_argument("--check", action="store_true", help="只体检，不做任何改动")
    ap.add_argument("--no-rollback", action="store_true", help="失败时不回滚")
    ap.add_argument("-v", "--verbose", action="store_true", help="打印每条远端命令")
    args = ap.parse_args()

    cfg = build_config(args)
    if not cfg["host"]:
        sys.exit(
            "还没有配置服务器地址。\n"
            "  方式一：在 deploy/deploy.env 里填 GUARDIAN_SSH_HOST 等\n"
            "  方式二：python deploy.py --host 1.2.3.4 --user root --password xxx --path /opt/guardian-server"
        )
    if not cfg["password"] and not cfg["key"]:
        sys.exit("需要 SSH 密码或私钥：--password xxx / --key ~/.ssh/id_rsa")

    tarball = None if args.check else find_tarball(args.tarball)

    say("=" * 60)
    say("  使用守护 · 远程部署")
    say(f"  服务器：{cfg['user']}@{cfg['host']}:{cfg['port']}")
    say(f"  目录：  {cfg['path']}")
    if tarball:
        say(f"  发布包：{os.path.basename(tarball)}")
    say("=" * 60)

    r = None
    try:
        step("连接服务器")
        r = Remote(cfg, verbose=args.verbose)
        ok("已连接，主机指纹 " + r.fingerprint())

        existed = precheck(r, cfg)
        show_status(r, cfg)

        if args.check:
            say()
            say("体检完成（没有做任何改动）")
            return

        deploy(r, cfg, tarball, existed, args.keep, rollback=not args.no_rollback)

    except paramiko.AuthenticationException:
        say()
        sys.exit("✘ SSH 认证失败：用户名/密码（或私钥）不对")
    except paramiko.SSHException as e:
        say()
        sys.exit(f"✘ SSH 连接异常：{e}")
    finally:
        if r:
            r.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        say("\n已中断")
        sys.exit(130)
    except Exception as e:
        say("\n✘ " + str(e))
        sys.exit(1)
