#!/bin/sh
# ======================================================================
#  使用守护 · 管理后台  —— 状态脚本
#
#  用法：sh status.sh
#
#  除了 run.sh status 的信息，这里还会报一下数据库体积，
#  方便判断"事件攒了多少、要不要清理"。
# ======================================================================

set -eu
APP_DIR=$(cd "$(dirname "$0")" && pwd)

sh "$APP_DIR/run.sh" status || true

DB="${GUARDIAN_DATA:-$APP_DIR/data/guardian.db}"
if [ -f "$APP_DIR/config.env" ]; then
    # 只取这一项，避免把别的变量也带进当前 shell
    _v=$(grep -E '^GUARDIAN_DATA=' "$APP_DIR/config.env" 2>/dev/null | tail -n 1 | cut -d= -f2- | tr -d '"' || true)
    [ -n "${_v:-}" ] && DB="$_v"
fi

if [ -f "$DB" ]; then
    size=$(wc -c <"$DB" 2>/dev/null | tr -dc '0-9' || echo 0)
    echo "  数据库：$DB（$((size / 1024)) KB）"
else
    echo "  数据库：$DB（还没有生成）"
fi
