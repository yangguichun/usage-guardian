"""
「使用守护」管理后台 —— 单文件 FastAPI 服务。

它做两件事：
  1. 让每台平板/手机**主动上报**状态（心跳），家长的浏览器就能看到统计；
  2. 家长在网页上点按钮 → 生成一条**待执行指令** → 设备下一次心跳时取走并执行。

为什么用"设备主动拉"而不是"服务端推"：
  平板在家里、服务器在公网，设备没有公网 IP，也拿不到推送通道（FCM 在国内不可用）。
  让设备每 20 秒问一句"有活儿吗"，是最稳、最省电、零依赖的做法。

启动：
    pip install -r requirements.txt
    GUARDIAN_ADMIN_PASSWORD=你的口令 uvicorn main:app --host 0.0.0.0 --port 8000

环境变量：
    GUARDIAN_ADMIN_PASSWORD  后台登录口令（不设则用 guardian2026，启动时会警告）
    GUARDIAN_DB              数据库文件路径（默认 ./guardian.db）
    GUARDIAN_TZ_OFFSET       统计"今天"用的时区偏移小时数（默认 8，即北京时间）
    GUARDIAN_SESSION_DAYS    登录有效期天数（默认 7）
"""

from __future__ import annotations

import hmac
import json
import os
import secrets
import sqlite3
import time
from contextlib import closing
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

# ======================================================================
# 基础配置
# ======================================================================

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
DB_PATH = Path(os.getenv("GUARDIAN_DB", str(BASE_DIR / "guardian.db")))

ADMIN_PASSWORD = os.getenv("GUARDIAN_ADMIN_PASSWORD", "guardian2026")
USING_DEFAULT_PASSWORD = "GUARDIAN_ADMIN_PASSWORD" not in os.environ

TZ_OFFSET_HOURS = float(os.getenv("GUARDIAN_TZ_OFFSET", "8"))
SESSION_DAYS = int(os.getenv("GUARDIAN_SESSION_DAYS", "7"))

# 在线判定：最近一次心跳在这个时间内
ONLINE_WINDOW = 90.0
# 指令被发出后多久没回执，就当作没送到，重新排队
COMMAND_RESEND_AFTER = 120.0
# 配对码有效期
PAIR_CODE_TTL = 600.0

app = FastAPI(title="使用守护 · 管理后台", docs_url="/api/docs", redoc_url=None)


def now_ts() -> float:
    return time.time()


def local_day(ts: Optional[float] = None) -> str:
    """把时间戳换成"北京时间的那一天"（yyyy-MM-dd）。

    不用 zoneinfo 是为了不依赖服务器上的 tzdata（有些精简镜像里没有）。
    """
    ts = now_ts() if ts is None else ts
    return datetime.utcfromtimestamp(ts + TZ_OFFSET_HOURS * 3600).strftime("%Y-%m-%d")


def clamp(v: Any, lo: int, hi: int, default: int) -> int:
    try:
        n = int(v)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


# ======================================================================
# 数据库
# ======================================================================

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices(
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    model             TEXT,
    token             TEXT NOT NULL UNIQUE,
    created_at        REAL NOT NULL,
    last_seen         REAL DEFAULT 0,
    last_ip           TEXT,
    app_version       TEXT,
    enabled           INTEGER DEFAULT 0,
    resting           INTEGER DEFAULT 0,
    rest_left_ms      INTEGER DEFAULT 0,
    used_ms           INTEGER DEFAULT 0,
    today_used_ms     INTEGER DEFAULT 0,
    rest_count        INTEGER DEFAULT 0,
    use_minutes       INTEGER DEFAULT 30,
    rest_minutes      INTEGER DEFAULT 5,
    reset_gap_seconds INTEGER DEFAULT 300,
    tamper_policy     INTEGER DEFAULT 0,
    pin_set           INTEGER DEFAULT 0,
    screen_on         INTEGER DEFAULT 0,
    audio_suppressed  INTEGER DEFAULT 0,
    pause_media_on_rest INTEGER DEFAULT 1,
    mute_on_rest      INTEGER DEFAULT 0,
    note              TEXT
);

CREATE TABLE IF NOT EXISTS commands(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   TEXT NOT NULL,
    type        TEXT NOT NULL,
    params      TEXT,
    status      TEXT NOT NULL DEFAULT 'pending',   -- pending / sent / done / failed
    created_at  REAL NOT NULL,
    sent_at     REAL DEFAULT 0,
    done_at     REAL DEFAULT 0,
    result      TEXT
);
CREATE INDEX IF NOT EXISTS idx_cmd_device ON commands(device_id, status);

CREATE TABLE IF NOT EXISTS events(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   TEXT,
    kind        TEXT NOT NULL,
    message     TEXT NOT NULL,
    created_at  REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_time ON events(created_at DESC);

CREATE TABLE IF NOT EXISTS pair_codes(
    code        TEXT PRIMARY KEY,
    created_at  REAL NOT NULL,
    expires_at  REAL NOT NULL,
    used_by     TEXT
);

CREATE TABLE IF NOT EXISTS admin_sessions(
    token       TEXT PRIMARY KEY,
    created_at  REAL NOT NULL,
    expires_at  REAL NOT NULL,
    ip          TEXT
);

CREATE TABLE IF NOT EXISTS device_daily(
    device_id   TEXT NOT NULL,
    day         TEXT NOT NULL,
    used_ms     INTEGER DEFAULT 0,
    rest_count  INTEGER DEFAULT 0,
    PRIMARY KEY(device_id, day)
);
"""


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with closing(db()) as conn:
        conn.executescript(SCHEMA)
        # 轻量迁移：老库缺列就补上（避免升级服务端时还要手动改库）
        have = {r["name"] for r in conn.execute("PRAGMA table_info(devices)").fetchall()}
        for col, ddl in (
            ("pause_media_on_rest", "ALTER TABLE devices ADD COLUMN pause_media_on_rest INTEGER DEFAULT 1"),
            ("mute_on_rest", "ALTER TABLE devices ADD COLUMN mute_on_rest INTEGER DEFAULT 0"),
        ):
            if col not in have:
                conn.execute(ddl)
        conn.commit()


init_db()


def add_event(conn: sqlite3.Connection, device_id: Optional[str], kind: str, message: str) -> None:
    conn.execute(
        "INSERT INTO events(device_id, kind, message, created_at) VALUES (?,?,?,?)",
        (device_id, kind, message, now_ts()),
    )


# ======================================================================
# 认证
# ======================================================================

# 登录失败计数（进程内存即可，重启清零无所谓）
_login_fails: Dict[str, List[float]] = {}


def client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else ""


def require_admin(authorization: Optional[str] = Header(None)) -> str:
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not token:
        raise HTTPException(401, "未登录")
    with closing(db()) as conn:
        row = conn.execute(
            "SELECT token, expires_at FROM admin_sessions WHERE token=?", (token,)
        ).fetchone()
        if not row or row["expires_at"] < now_ts():
            raise HTTPException(401, "登录已过期，请重新登录")
        # 滑动续期：每次操作把有效期往后推，长期不用才掉线
        conn.execute(
            "UPDATE admin_sessions SET expires_at=? WHERE token=?",
            (now_ts() + SESSION_DAYS * 86400, token),
        )
        conn.commit()
    return token


def require_device(authorization: Optional[str] = Header(None)) -> sqlite3.Row:
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not token:
        raise HTTPException(401, "缺少设备令牌")
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM devices WHERE token=?", (token,)).fetchone()
    if not row:
        raise HTTPException(401, "设备令牌无效，请重新配对")
    return row


# ======================================================================
# 数据模型
# ======================================================================


class LoginBody(BaseModel):
    password: str


class CommandBody(BaseModel):
    type: str
    params: Optional[Dict[str, Any]] = None


class PairBody(BaseModel):
    code: str
    name: str
    model: Optional[str] = None
    app_version: Optional[str] = None


class ResultBody(BaseModel):
    command_id: int
    ok: bool
    message: Optional[str] = None


# ======================================================================
# 页面
# ======================================================================


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/healthz")
def healthz() -> Dict[str, Any]:
    return {"ok": True, "time": now_ts()}


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


# ======================================================================
# 管理端：登录
# ======================================================================


@app.post("/api/login")
def login(body: LoginBody, request: Request) -> Dict[str, Any]:
    ip = client_ip(request)
    # 简单限流：同一 IP 5 分钟内失败 8 次就锁一会儿，防止被撞库
    fails = [t for t in _login_fails.get(ip, []) if now_ts() - t < 300]
    if len(fails) >= 8:
        raise HTTPException(429, "尝试次数过多，请 5 分钟后再试")

    if not hmac.compare_digest(body.password.encode("utf-8"), ADMIN_PASSWORD.encode("utf-8")):
        fails.append(now_ts())
        _login_fails[ip] = fails
        raise HTTPException(401, "口令不正确")

    _login_fails.pop(ip, None)
    token = secrets.token_urlsafe(32)
    exp = now_ts() + SESSION_DAYS * 86400
    with closing(db()) as conn:
        conn.execute(
            "INSERT INTO admin_sessions(token, created_at, expires_at, ip) VALUES (?,?,?,?)",
            (token, now_ts(), exp, ip),
        )
        add_event(conn, None, "admin_login", f"管理后台登录（{ip or '未知 IP'}）")
        conn.commit()
    return {
        "token": token,
        "expires_at": exp,
        "using_default_password": USING_DEFAULT_PASSWORD,
    }


@app.post("/api/logout")
def logout(token: str = Depends(require_admin)) -> Dict[str, Any]:
    with closing(db()) as conn:
        conn.execute("DELETE FROM admin_sessions WHERE token=?", (token,))
        conn.commit()
    return {"ok": True}


# ======================================================================
# 管理端：统计与设备
# ======================================================================


def device_row_to_dict(row: sqlite3.Row, with_secret: bool = False) -> Dict[str, Any]:
    d = dict(row)
    if not with_secret:
        d.pop("token", None)
    ts = now_ts()
    seen = d.get("last_seen") or 0
    d["online"] = (ts - seen) <= ONLINE_WINDOW if seen else False
    d["offline_seconds"] = int(ts - seen) if seen else None
    return d


@app.get("/api/overview")
def overview(admin: str = Depends(require_admin)) -> Dict[str, Any]:
    with closing(db()) as conn:
        devices = [device_row_to_dict(r) for r in conn.execute(
            "SELECT * FROM devices ORDER BY created_at ASC"
        ).fetchall()]

        today = local_day()
        week_ago = local_day(now_ts() - 6 * 86400)
        trend_rows = conn.execute(
            "SELECT day, SUM(used_ms) ms, SUM(rest_count) rc FROM device_daily "
            "WHERE day >= ? GROUP BY day ORDER BY day ASC",
            (week_ago,),
        ).fetchall()
        events = [dict(r) for r in conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT 40"
        ).fetchall()]

    online = [d for d in devices if d["online"]]
    resting = [d for d in online if d["resting"]]
    total_today = sum(d["today_used_ms"] or 0 for d in devices)
    rest_today = sum(d["rest_count"] or 0 for d in devices if d.get("id"))

    trend_map = {r["day"]: {"used_ms": r["ms"] or 0, "rest_count": r["rc"] or 0} for r in trend_rows}
    trend = []
    for i in range(6, -1, -1):
        day = local_day(now_ts() - i * 86400)
        v = trend_map.get(day, {"used_ms": 0, "rest_count": 0})
        trend.append({"day": day, **v})

    return {
        "now": now_ts(),
        "today": today,
        "stats": {
            "devices_total": len(devices),
            "devices_online": len(online),
            "devices_resting": len(resting),
            "devices_off": len([d for d in devices if not d["enabled"]]),
            "today_used_ms": total_today,
            "today_rest_count": rest_today,
        },
        "trend": trend,
        "devices": devices,
        "events": events,
        "using_default_password": USING_DEFAULT_PASSWORD,
    }


@app.get("/api/devices")
def list_devices(admin: str = Depends(require_admin)) -> Dict[str, Any]:
    with closing(db()) as conn:
        rows = conn.execute("SELECT * FROM devices ORDER BY created_at ASC").fetchall()
    return {"devices": [device_row_to_dict(r) for r in rows]}


@app.get("/api/devices/{device_id}")
def device_detail(device_id: str, admin: str = Depends(require_admin)) -> Dict[str, Any]:
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        if not row:
            raise HTTPException(404, "设备不存在")
        days = [dict(r) for r in conn.execute(
            "SELECT day, used_ms, rest_count FROM device_daily WHERE device_id=? "
            "ORDER BY day DESC LIMIT 14",
            (device_id,),
        ).fetchall()]
        cmds = [dict(r) for r in conn.execute(
            "SELECT * FROM commands WHERE device_id=? ORDER BY id DESC LIMIT 20",
            (device_id,),
        ).fetchall()]
        evs = [dict(r) for r in conn.execute(
            "SELECT * FROM events WHERE device_id=? ORDER BY id DESC LIMIT 30",
            (device_id,),
        ).fetchall()]
    d = device_row_to_dict(row)
    for c in cmds:
        try:
            c["params"] = json.loads(c["params"]) if c["params"] else None
        except (ValueError, TypeError):
            pass
    return {"device": d, "daily": days, "commands": cmds, "events": evs}


# ======================================================================
# 管理端：下发指令
# ======================================================================

ALLOWED_COMMANDS = {
    "lock": "远程锁定（立即进入强制休息）",
    "unlock": "解除锁定（跳过本次休息）",
    "pause": "暂停守护（不再限制时间）",
    "enable": "开启守护",
    "config": "修改配置参数",
    "sync": "让设备立刻同步一次",
}


@app.post("/api/devices/{device_id}/command")
def push_command(
    device_id: str,
    body: CommandBody,
    request: Request,
    admin: str = Depends(require_admin),
) -> Dict[str, Any]:
    if body.type not in ALLOWED_COMMANDS:
        raise HTTPException(400, f"不支持的指令：{body.type}")

    params: Dict[str, Any] = dict(body.params or {})
    if body.type == "lock":
        params["minutes"] = clamp(params.get("minutes", 5), 1, 240, 5)
    if body.type == "config":
        clean: Dict[str, Any] = {}
        if "use_minutes" in params:
            clean["use_minutes"] = clamp(params["use_minutes"], 1, 600, 30)
        if "rest_minutes" in params:
            clean["rest_minutes"] = clamp(params["rest_minutes"], 1, 240, 5)
        if "reset_gap_seconds" in params:
            clean["reset_gap_seconds"] = clamp(params["reset_gap_seconds"], 30, 3600, 300)
        if "tamper_policy" in params:
            clean["tamper_policy"] = 1 if int(params["tamper_policy"] or 0) else 0
        for k in ("mute_on_rest", "pause_media_on_rest"):
            if k in params:
                clean[k] = bool(params[k])
        if not clean:
            raise HTTPException(400, "没有可修改的参数")
        params = clean

    with closing(db()) as conn:
        row = conn.execute("SELECT name FROM devices WHERE id=?", (device_id,)).fetchone()
        if not row:
            raise HTTPException(404, "设备不存在")
        cur = conn.execute(
            "INSERT INTO commands(device_id, type, params, status, created_at) VALUES (?,?,?,?,?)",
            (device_id, body.type, json.dumps(params, ensure_ascii=False), "pending", now_ts()),
        )
        cid = cur.lastrowid
        add_event(
            conn, device_id, "command",
            f"下发指令：{ALLOWED_COMMANDS[body.type]}（来自 {client_ip(request) or '后台'}）",
        )
        conn.commit()
    return {
        "ok": True,
        "command_id": cid,
        "queued": True,
        # 设备 20 秒一次心跳，最迟这么久就会取走
        "hint": "指令已排队，设备下次心跳时执行（通常 20 秒内）",
    }


@app.get("/api/events")
def events(limit: int = 80, admin: str = Depends(require_admin)) -> Dict[str, Any]:
    limit = clamp(limit, 1, 500, 80)
    with closing(db()) as conn:
        rows = conn.execute(
            "SELECT * FROM events ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
    return {"events": [dict(r) for r in rows]}


# ======================================================================
# 管理端：配对码
# ======================================================================


@app.post("/api/pair-codes")
def create_pair_code(request: Request, admin: str = Depends(require_admin)) -> Dict[str, Any]:
    code = "".join(secrets.choice("0123456789") for _ in range(6))
    exp = now_ts() + PAIR_CODE_TTL
    with closing(db()) as conn:
        # 顺手清掉过期的
        conn.execute("DELETE FROM pair_codes WHERE expires_at < ?", (now_ts(),))
        conn.execute(
            "INSERT INTO pair_codes(code, created_at, expires_at) VALUES (?,?,?)",
            (code, now_ts(), exp),
        )
        add_event(conn, None, "pair_code", f"生成配对码 {code}（10 分钟内有效）")
        conn.commit()
    return {"code": code, "expires_at": exp, "ttl": PAIR_CODE_TTL}


# ======================================================================
# 设备端 API
# ======================================================================


@app.post("/api/device/pair")
def device_pair(body: PairBody, request: Request) -> Dict[str, Any]:
    code = (body.code or "").strip()
    name = (body.name or "").strip() or "未命名设备"
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM pair_codes WHERE code=?", (code,)).fetchone()
        if not row:
            raise HTTPException(400, "配对码不存在")
        if row["used_by"]:
            raise HTTPException(400, "配对码已被使用，请在后台重新生成")
        if row["expires_at"] < now_ts():
            raise HTTPException(400, "配对码已过期，请在后台重新生成")

        device_id = "dev_" + secrets.token_hex(6)
        token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO devices(id, name, model, token, created_at, last_seen, last_ip, app_version) "
            "VALUES (?,?,?,?,?,?,?,?)",
            (device_id, name, body.model or "", token, now_ts(), now_ts(),
             client_ip(request), body.app_version or ""),
        )
        conn.execute("UPDATE pair_codes SET used_by=? WHERE code=?", (device_id, code))
        add_event(conn, device_id, "pair", f"新设备配对成功：{name}（{body.model or '未知型号'}）")
        conn.commit()
    return {
        "device_id": device_id,
        "token": token,
        "name": name,
        "heartbeat_seconds": 20,
    }


@app.post("/api/device/heartbeat")
def device_heartbeat(
    payload: Dict[str, Any],
    request: Request,
    authorization: Optional[str] = Header(None),
) -> Dict[str, Any]:
    dev = require_device(authorization)
    did = dev["id"]
    ts = now_ts()

    # ---- 更新状态快照 ----
    enabled = 1 if payload.get("enabled") else 0
    resting = 1 if payload.get("resting") else 0
    today_used = clamp(payload.get("today_used_ms"), 0, 24 * 3600 * 1000, 0)
    rest_count = clamp(payload.get("rest_count"), 0, 999, 0)

    with closing(db()) as conn:
        prev = conn.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        conn.execute(
            """UPDATE devices SET
                 last_seen=?, last_ip=?, app_version=?, enabled=?, resting=?, rest_left_ms=?,
                 used_ms=?, today_used_ms=?, rest_count=?, use_minutes=?, rest_minutes=?,
                 reset_gap_seconds=?, tamper_policy=?, pin_set=?, screen_on=?, audio_suppressed=?,
                 pause_media_on_rest=?, mute_on_rest=?
               WHERE id=?""",
            (
                ts, client_ip(request), payload.get("app_version") or dev["app_version"],
                enabled, resting,
                clamp(payload.get("rest_left_ms"), 0, 24 * 3600 * 1000, 0),
                clamp(payload.get("used_ms"), 0, 24 * 3600 * 1000, 0),
                today_used, rest_count,
                clamp(payload.get("use_minutes"), 1, 600, dev["use_minutes"]),
                clamp(payload.get("rest_minutes"), 1, 240, dev["rest_minutes"]),
                clamp(payload.get("reset_gap_seconds"), 30, 3600, dev["reset_gap_seconds"]),
                1 if payload.get("tamper_policy") else 0,
                1 if payload.get("pin_set") else 0,
                1 if payload.get("screen_on") else 0,
                1 if payload.get("audio_suppressed") else 0,
                1 if payload.get("pause_media_on_rest", True) else 0,
                1 if payload.get("mute_on_rest") else 0,
                did,
            ),
        )

        # ---- 状态变化记一笔（家长能在事件流里看到孩子什么时候开始/结束休息）----
        if prev:
            if prev["resting"] and not resting:
                add_event(conn, did, "rest_end", f"{prev['name']} 休息结束")
            elif not prev["resting"] and resting:
                add_event(conn, did, "rest_start", f"{prev['name']} 进入强制休息")
            if prev["enabled"] and not enabled:
                add_event(conn, did, "guard_off", f"{prev['name']} 的守护被关闭")
            elif not prev["enabled"] and enabled:
                add_event(conn, did, "guard_on", f"{prev['name']} 的守护已开启")

        # ---- 每日统计（取最大值，避免心跳抖动导致回退）----
        day = local_day(ts)
        conn.execute(
            """INSERT INTO device_daily(device_id, day, used_ms, rest_count) VALUES (?,?,?,?)
               ON CONFLICT(device_id, day) DO UPDATE SET
                 used_ms = MAX(device_daily.used_ms, excluded.used_ms),
                 rest_count = MAX(device_daily.rest_count, excluded.rest_count)""",
            (did, day, today_used, rest_count),
        )

        # ---- 取待执行指令 ----
        pending = conn.execute(
            "SELECT * FROM commands WHERE device_id=? AND status='pending' ORDER BY id ASC LIMIT 20",
            (did,),
        ).fetchall()
        # 发出去 2 分钟还没回执的，重新排队（可能设备当时正在关机/断网）
        conn.execute(
            "UPDATE commands SET status='pending' WHERE device_id=? AND status='sent' AND sent_at<?",
            (did, ts - COMMAND_RESEND_AFTER),
        )
        for r in pending:
            conn.execute(
                "UPDATE commands SET status='sent', sent_at=? WHERE id=?", (ts, r["id"])
            )
        conn.commit()

    cmds = []
    for r in pending:
        try:
            params = json.loads(r["params"]) if r["params"] else {}
        except (ValueError, TypeError):
            params = {}
        cmds.append({"id": r["id"], "type": r["type"], "params": params})

    return {
        "ok": True,
        "server_time": ts,
        "device_name": dev["name"],
        "commands": cmds,
        "heartbeat_seconds": 20,
    }


@app.post("/api/device/result")
def device_result(
    body: ResultBody,
    authorization: Optional[str] = Header(None),
) -> Dict[str, Any]:
    dev = require_device(authorization)
    with closing(db()) as conn:
        row = conn.execute(
            "SELECT * FROM commands WHERE id=? AND device_id=?", (body.command_id, dev["id"])
        ).fetchone()
        if not row:
            raise HTTPException(404, "指令不存在")
        status = "done" if body.ok else "failed"
        conn.execute(
            "UPDATE commands SET status=?, done_at=?, result=? WHERE id=?",
            (status, now_ts(), (body.message or "")[:500], body.command_id),
        )
        label = ALLOWED_COMMANDS.get(row["type"], row["type"])
        add_event(
            conn, dev["id"], "command_result",
            f"{label} → {'成功' if body.ok else '失败'}" +
            (f"（{body.message}）" if body.message else ""),
        )
        conn.commit()
    return {"ok": True}


# ======================================================================
# 启动提示
# ======================================================================


@app.on_event("startup")
def _startup() -> None:
    print("=" * 62)
    print("  使用守护 · 管理后台已启动")
    print(f"  数据库：{DB_PATH}")
    print(f"  时区偏移：UTC{'+' if TZ_OFFSET_HOURS >= 0 else ''}{TZ_OFFSET_HOURS:g}（用于统计“今天”）")
    if USING_DEFAULT_PASSWORD:
        print("  ⚠ 正在使用默认口令 guardian2026，请务必设置环境变量 GUARDIAN_ADMIN_PASSWORD！")
    print("=" * 62)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=os.getenv("GUARDIAN_HOST", "0.0.0.0"),
        port=int(os.getenv("GUARDIAN_PORT", "8000")),
    )
