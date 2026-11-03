#!/usr/bin/env bash
# ======================================================================
#  一键构建 Linux 版后台（在 Windows 上也能出 Linux 二进制）
#
#  用法：  bash build.sh
#
#  它做三件事：
#    1. 交叉编译出 x86_64-unknown-linux-musl 的**静态**可执行文件
#       （musl + crt 静态链接 = 目标机不需要 glibc、不需要装任何东西）
#    2. 检查产物确实是 Linux ELF
#    3. 打包成 dist/guardian-server-<版本>-linux-x86_64.tar.gz
#
#  为什么不需要 Docker / WSL / gcc：
#    Rust 官方工具链自带 rust-lld（LLVM 链接器）和 musl 的启动目标文件
#    （lib/rustlib/<target>/lib/self-contained/），指定它做链接器即可。
#    唯一的要求是宿主编译器本身能工作 —— 如果机器上没装 MSVC 的 link.exe，
#    本脚本会自动改用 rustup 的 GNU 工具链（它自带 mingw 的 ld）。
#
#  为什么需要 zig：
#    v2.2 起用 SQLite 存事件，`sqlite3.c` 是 C 代码，得有一个能产 Linux ELF 的
#    C 编译器。zig 自带 musl 的 C 库和头文件，`zig cc -target x86_64-linux-musl`
#    正好干这件事，而且**只是构建期工具**，不进产物。装法：`pip install ziglang`
#    （也可以用 ZIG=/path/to/zig 指定已有的）。
#
#  可覆盖的变量：
#    HOSTTOOLCHAIN=stable-x86_64-pc-windows-gnu   指定宿主编译器
#    ZIG=/path/to/zig                              指定 zig 位置
#    OFFLINE=1                                     只用本地已缓存的依赖
# ======================================================================

set -euo pipefail
cd "$(dirname "$0")"

TARGET="x86_64-unknown-linux-musl"
VERSION=$(awk -F'"' '/^version *=/{print $2; exit}' Cargo.toml)

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) ON_WINDOWS=1 ;;
    *) ON_WINDOWS=0 ;;
esac

# ---------- 1. 挑宿主编译器 ----------
TOOLCHAIN="${HOSTTOOLCHAIN:-}"
if [ -z "$TOOLCHAIN" ]; then
    if [ "$ON_WINDOWS" = "1" ]; then
        # MSVC 工具链需要 VS 的 link.exe；PATH 里没有就退回 GNU 工具链
        if command -v cl.exe >/dev/null 2>&1; then
            TOOLCHAIN="stable-x86_64-pc-windows-msvc"
        else
            TOOLCHAIN="stable-x86_64-pc-windows-gnu"
        fi
    fi
fi
[ -n "$TOOLCHAIN" ] || TOOLCHAIN="$(rustup show active-toolchain | awk '{print $1}')"
echo "→ 宿主编译器：$TOOLCHAIN"

# ---------- 2. 准备交叉链接参数 ----------
SYSROOT=$(rustc "+$TOOLCHAIN" --print sysroot)
HOSTTRIPLE=$(rustc "+$TOOLCHAIN" -vV | awk '/^host:/{print $2}')

if [ "$ON_WINDOWS" = "1" ]; then
    LLD="$SYSROOT/lib/rustlib/$HOSTTRIPLE/bin/rust-lld.exe"
    if [ ! -f "$LLD" ]; then
        echo "✘ 找不到 rust-lld：$LLD"
        echo "  试试：rustup component add rust-lld --toolchain $TOOLCHAIN"
        exit 1
    fi
    # 注意：只给 musl 目标加 rustflags，宿主编译（过程宏、build script）走原样
    export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="-C linker=$LLD -C link-self-contained=yes"
    echo "→ 交叉链接器：rust-lld（自包含 musl CRT，无需 gcc / Docker / WSL）"
else
    echo "→ 非 Windows 宿主：使用系统链接器（需已安装 musl 工具链）"
fi

# ---------- 3. 找 zig（把 sqlite3.c 编成 Linux 目标文件） ----------
find_zig() {
    if [ -n "${ZIG:-}" ] && [ -x "$ZIG" ]; then echo "$ZIG"; return 0; fi
    if command -v zig >/dev/null 2>&1; then command -v zig; return 0; fi
    # pip 装的 ziglang 包：zig 在 site-packages/ziglang/ 里
    for py in python3 python py; do
        command -v "$py" >/dev/null 2>&1 || continue
        local p
        p=$("$py" -c "import ziglang,os,sys;print(os.path.join(os.path.dirname(ziglang.__file__),'zig.exe' if sys.platform=='win32' else 'zig'))" 2>/dev/null) || true
        if [ -n "${p:-}" ] && [ -x "$p" ]; then echo "$p"; return 0; fi
    done
    return 1
}

ZIG_BIN="$(find_zig || true)"
if [ -z "$ZIG_BIN" ]; then
    echo "✘ 找不到 zig —— 编译 SQLite 的 C 代码需要它"
    echo
    echo "  装法（只需要本机有，不进产物、服务器上不用装）："
    echo "    pip install ziglang"
    echo
    echo "  已经有 zig 的话，用环境变量指过来："
    echo "    ZIG=/path/to/zig bash build.sh"
    exit 1
fi
echo "→ C 交叉编译器：$ZIG_BIN（zig cc → $TARGET）"

# zig 只认 "x86_64-linux-musl"，不认 Rust 的 "x86_64-unknown-linux-musl"；
# 而 cc-rs 一旦发现 CC 是 clang 系就会自己补一个 --target=<rust triple>，zig 会报错。
# CRATE_CC_NO_DEFAULTS=1 关掉 cc-rs 的自动参数，target 由 CFLAGS 自己给。
ZIG_TARGET="x86_64-linux-musl"
export CRATE_CC_NO_DEFAULTS=1
export CC_x86_64_unknown_linux_musl="$ZIG_BIN cc"
export AR_x86_64_unknown_linux_musl="$ZIG_BIN ar"
export CFLAGS_x86_64_unknown_linux_musl="-target $ZIG_TARGET -O2 -fPIC"

# ---------- 4. 编译 ----------
if [ ! -d "$SYSROOT/lib/rustlib/$TARGET" ]; then
    echo "→ 补装目标标准库 $TARGET"
    rustup target add --toolchain "$TOOLCHAIN" "$TARGET"
fi

ARGS=(build --release --target "$TARGET")
[ "${OFFLINE:-0}" = "1" ] && ARGS+=(--offline)

echo "→ 编译中（首次需要几分钟）…"
cargo "+$TOOLCHAIN" "${ARGS[@]}"

BIN="target/$TARGET/release/guardian-server"
if [ ! -f "$BIN" ]; then
    echo "✘ 编译产物不存在：$BIN"
    exit 1
fi

# ---------- 5. 校验产物 ----------
MAGIC=$(od -An -tx1 -N4 "$BIN" | tr -d ' \n')
if [ "$MAGIC" != "7f454c46" ]; then
    echo "✘ 产物不是 Linux ELF（文件头 $MAGIC），交叉编译没生效"
    exit 1
fi

# 静态链接的 ELF 不会有 .interp（动态加载器路径）
if grep -qa '/lib/ld-musl\|/lib64/ld-linux' "$BIN"; then
    echo "⚠ 产物里出现了动态加载器路径，可能不是完全静态链接"
else
    echo "✔ 已确认：Linux x86_64 静态 ELF"
fi

# ---------- 6. 打包 ----------
PKG="dist/guardian-server"
rm -rf "$PKG"
mkdir -p "$PKG"
cp "$BIN" "$PKG/guardian-server"
cp deploy/run.sh deploy/stop.sh deploy/start.sh deploy/status.sh deploy/config.env.example "$PKG/"
cp deploy/README-deploy.md "$PKG/README.md"
chmod 0644 "$PKG/config.env.example" "$PKG/README.md" 2>/dev/null || true

TARBALL="dist/guardian-server-$VERSION-linux-x86_64.tar.gz"
PLAIN="dist/.guardian-server.tar"
rm -f "$TARBALL" "$PLAIN"
# 分两步打：可执行文件 755，文档/配置样例 644。
# Windows 上没有真实的权限位，只能靠 tar 的 --mode 写进归档，否则解包后
# run.sh 和二进制都不可执行，用户还得手动 chmod。
tar -cf "$PLAIN" --mode='u+rwx,go+rx' -C dist \
    guardian-server/run.sh guardian-server/stop.sh guardian-server/start.sh \
    guardian-server/status.sh guardian-server/guardian-server
tar -rf "$PLAIN" --mode='u+rw,go+r' -C dist \
    guardian-server/README.md guardian-server/config.env.example
gzip -f "$PLAIN"
mv "$PLAIN.gz" "$TARBALL"

SIZE=$(wc -c <"$PKG/guardian-server" | tr -dc '0-9')
echo
echo "==================== 构建完成 ===================="
echo "  版本：$VERSION"
echo "  二进制：$PKG/guardian-server（$((SIZE / 1024)) KB，静态链接）"
echo "  发布包：$TARBALL"
if command -v sha256sum >/dev/null 2>&1; then
    echo "  SHA256：$(sha256sum "$TARBALL" | awk '{print $1}')"
fi
echo
echo "  上传到服务器后："
echo "    tar xzf $(basename "$TARBALL")"
echo "    cd guardian-server && sh run.sh"
echo "=================================================="
