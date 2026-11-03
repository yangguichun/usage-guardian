#!/bin/sh
# ======================================================================
#  使用守护 · 管理后台  —— 启动脚本
#
#  用法（在服务器上）：
#      sh run.sh            启动（如果已经在跑，就先停掉再重启）
#      sh run.sh restart    同上
#      sh run.sh stop       停止
#      sh run.sh status     看状态
#      sh run.sh logs       跟踪日志（Ctrl+C 退出查看，不影响服务）
#      sh run.sh migrate    把旧版 data/state.json 导入 SQLite 后退出
#      sh run.sh backup     在线备份 SQLite（不停服）
#
#  设计要点：
#    * 可以反复执行，第二次执行 = 重启，不会出现两个进程抢端口；
#    * 不需要 root，不需要安装任何依赖，不需要 systemd；
#    * 配置写在同目录 config.env 里（没有就用默认值）。
# ======================================================================

set -eu

APP_DIR=$(cd "$(dirname "$0")" && pwd)
BIN="$APP_DIR/guardian-server"
DATA_DIR="$APP_DIR/data"
PID_FILE="$DATA_DIR/server.pid"
LOG_FILE="$DATA_DIR/server.log"

# ---------- 读配置 ----------
if [ -f "$APP_DIR/config.env" ]; then
    set -a
    . "$APP_DIR/config.env"
    set +a
fi

: "${GUARDIAN_ADMIN_PASSWORD:=guardian2026}"
: "${GUARDIAN_PORT:=8000}"
: "${GUARDIAN_HOST:=0.0.0.0}"
# v2.2 起数据存 SQLite。老配置里如果还写着 state.json，程序会自动改用同目录的 .db，
# 并把那个 json 当作迁移源读一次（不会覆盖它）。
: "${GUARDIAN_DATA:=$DATA_DIR/guardian.db}"
export GUARDIAN_ADMIN_PASSWORD GUARDIAN_PORT GUARDIAN_HOST GUARDIAN_DATA

mkdir -p "$DATA_DIR"

# 从 Windows 拷过来的文件常常没有可执行位，这里补一下
if [ -f "$BIN" ] && [ ! -x "$BIN" ]; then
    chmod +x "$BIN" 2>/dev/null || true
fi

read_pid() {
    [ -f "$PID_FILE" ] && cat "$PID_FILE" 2>/dev/null || true
}

# $1 = pid；活着返回 0
alive() {
    [ -n "${1:-}" ] || return 1
    kill -0 "$1" 2>/dev/null
}

stop_server() {
    pid=$(read_pid)
    if alive "$pid"; then
        echo "→ 停止正在运行的进程（PID $pid）"
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
    fi
    rm -f "$PID_FILE"

    # 兜底：pid 文件丢了（上次可能是 kill -9 或者机器重启）但进程还占着端口
    if command -v pgrep >/dev/null 2>&1; then
        for p in $(pgrep -f "$BIN" 2>/dev/null || true); do
            [ "$p" = "$$" ] && continue
            echo "→ 清理残留进程（PID $p）"
            kill "$p" 2>/dev/null || true
        done
    fi
}

start_server() {
    if [ ! -f "$BIN" ]; then
        echo "✘ 找不到可执行文件：$BIN"
        echo "  请确认 guardian-server 和 run.sh 在同一个目录里"
        exit 1
    fi

    # 日志超过 5MB 就转存一份，避免无限增长把磁盘写满
    if [ -f "$LOG_FILE" ]; then
        size=$(wc -c <"$LOG_FILE" 2>/dev/null | tr -dc '0-9' || true)
        if [ "${size:-0}" -gt 5242880 ]; then
            mv -f "$LOG_FILE" "$LOG_FILE.1"
        fi
    fi

    echo "→ 启动服务（端口 $GUARDIAN_PORT，日志 data/server.log）"
    cd "$APP_DIR"
    nohup "$BIN" >>"$LOG_FILE" 2>&1 &
    pid=$!
    echo "$pid" >"$PID_FILE"

    sleep 1
    if alive "$pid"; then
        echo "✔ 已启动，PID $pid"
        if command -v curl >/dev/null 2>&1; then
            if curl -fsS "http://127.0.0.1:$GUARDIAN_PORT/healthz" >/dev/null 2>&1; then
                echo "  健康检查通过"
            else
                echo "  健康检查未通过，看日志确认：sh run.sh logs"
            fi
        fi
        echo "  访问地址：http://<服务器IP>:$GUARDIAN_PORT/"
        return 0
    fi

    echo "✘ 启动失败，最近日志："
    tail -n 20 "$LOG_FILE" 2>/dev/null || true
    rm -f "$PID_FILE"
    exit 1
}

status_server() {
    pid=$(read_pid)
    if alive "$pid"; then
        echo "● 运行中（PID $pid）"
        echo "  访问：http://<服务器IP>:$GUARDIAN_PORT/"
        echo "  数据：$GUARDIAN_DATA"
        echo "  日志：$LOG_FILE"
        return 0
    fi
    echo "○ 未运行"
    return 1
}

# ---------- 迁移 / 备份 ----------

# 把旧版 data/state.json 导入 SQLite。
# 其实首次启动时程序会自动做一次（见 Store::open），这个命令是给
# "我想先确认数据都在，再启动服务"的场景用的。
do_migrate() {
    if [ ! -f "$BIN" ]; then
        echo "✘ 找不到可执行文件：$BIN"
        exit 1
    fi
    echo "→ 迁移期间需要独占数据库，先停止服务"
    stop_server
    cd "$APP_DIR"
    "$BIN" migrate
}

do_backup() {
    if [ ! -f "$GUARDIAN_DATA" ]; then
        echo "✘ 数据库不存在：$GUARDIAN_DATA"
        exit 1
    fi
    mkdir -p "$APP_DIR/backup"
    out="$APP_DIR/backup/guardian-$(date +%Y%m%d-%H%M%S).db"
    if command -v sqlite3 >/dev/null 2>&1; then
        # .backup 是热备份，不用停服
        sqlite3 "$GUARDIAN_DATA" ".backup '$out'" && echo "✔ 已热备份到 $out"
    else
        # 没有 sqlite3 命令：只能停服后直接拷（WAL 模式下运行中拷会缺数据）
        stop_server
        cp "$GUARDIAN_DATA" "$out" && echo "✔ 已备份到 $out（服务已停止，请用 sh run.sh 重新启动）"
    fi
}

print_help() {
    cat <<EOF
使用守护 · 管理后台

  sh run.sh              启动（已在运行时先停后启 = 重启）
  sh run.sh restart      同上
  sh run.sh stop         停止
  sh run.sh status       查看状态
  sh run.sh logs [n]     查看日志（默认跟踪，n = 只看最后 n 行）
  sh run.sh migrate      把旧版 data/state.json 导入 SQLite（会先停服）
  sh run.sh backup       备份数据库到 backup/

配置文件：$APP_DIR/config.env（可省略，默认值见 config.env.example）
EOF
}

cmd="${1:-restart}"
case "$cmd" in
    start | restart | "")
        stop_server
        start_server
        ;;
    stop)
        stop_server
        echo "✔ 已停止"
        ;;
    status)
        status_server || true
        ;;
    migrate)
        do_migrate
        ;;
    backup)
        do_backup
        ;;
    logs)
        if [ -n "${2:-}" ]; then
            tail -n "$2" "$LOG_FILE"
        else
            echo "（Ctrl+C 只退出查看，不影响服务）"
            tail -n 50 -f "$LOG_FILE"
        fi
        ;;
    -h | --help | help)
        print_help
        ;;
    *)
        echo "未知参数：$cmd"
        echo
        print_help
        exit 1
        ;;
esac
