#!/bin/sh
# ======================================================================
#  使用守护 · 管理后台  —— 停止脚本
#
#  用法：sh stop.sh
#
#  为什么要单独一个 stop.sh：
#    升级时把新的 guardian-server 传上去会提示「正在使用 / Text file busy」——
#    因为老的进程还在跑，而 Linux 不允许覆盖正在执行的二进制文件。
#    先跑这个停掉，再传文件就好了。
#
#  它做的事：
#    1. 读 data/server.pid 发 SIGTERM（程序会自己把数据 flush 到 SQLite 再退出）；
#    2. 等最多 5 秒，还在就 SIGKILL；
#    3. 兜底清理端口上残留的同名进程（pid 文件丢了的情况）。
# ======================================================================

set -eu

APP_DIR=$(cd "$(dirname "$0")" && pwd)
PID_FILE="$APP_DIR/data/server.pid"
BIN="$APP_DIR/guardian-server"

read_pid() {
    [ -f "$PID_FILE" ] && cat "$PID_FILE" 2>/dev/null || true
}

alive() {
    [ -n "${1:-}" ] || return 1
    kill -0 "$1" 2>/dev/null
}

stopped=0

pid=$(read_pid)
if alive "$pid"; then
    echo "→ 停止进程 PID $pid"
    kill "$pid" 2>/dev/null || true
    n=0
    while alive "$pid" && [ "$n" -lt 50 ]; do
        sleep 0.1
        n=$((n + 1))
    done
    if alive "$pid"; then
        echo "  5 秒内没退出，强制结束"
        kill -9 "$pid" 2>/dev/null || true
        sleep 0.3
    fi
    stopped=1
fi
rm -f "$PID_FILE"

# pid 文件可能丢了（上次 kill -9、或者机器重启过），按进程名兜底
if command -v pgrep >/dev/null 2>&1; then
    for p in $(pgrep -f "$BIN" 2>/dev/null || true); do
        [ "$p" = "$$" ] && continue
        echo "→ 清理残留进程 PID $p"
        kill "$p" 2>/dev/null || true
        stopped=1
    done
    sleep 0.3
    for p in $(pgrep -f "$BIN" 2>/dev/null || true); do
        [ "$p" = "$$" ] && continue
        kill -9 "$p" 2>/dev/null || true
    done
fi

if [ "$stopped" = "1" ]; then
    echo "✔ 已停止（现在可以安全地替换 guardian-server 文件）"
else
    echo "○ 本来就没在运行"
fi
