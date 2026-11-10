"""本地接口自测（只在开发机上跑，不进发布包）。

用法：
    python test_api.py [可执行文件路径]

它会把服务在一个临时目录里拉起来（端口 18999），按"家长 + 平板"的真实流程
走一遍所有接口，然后重启进程验证数据落盘是否正常，最后再单独验证一次
「旧版 state.json → SQLite」的自动迁移。

v2.2 起数据存 SQLite（sqlite3.c 静态编进二进制），所以**这个脚本要在目标平台
上跑**：在 Linux 服务器上 `python3 test_api.py ./guardian-server` 即可。
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = 18999
BASE = f"http://127.0.0.1:{PORT}"
PASSWORD = "test-pass-123"
ROOT = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(ROOT, "tmp-test")
DATA = os.path.join(TMP, "guardian.db")

ok_count = 0
fail_count = 0


def check(name, expect, actual, note=""):
    global ok_count, fail_count
    if expect == actual:
        ok_count += 1
        print(f"  ok   {name}")
    else:
        fail_count += 1
        print(f"  FAIL {name}\n       期望: {expect!r}\n       实际: {actual!r}"
              + (f"\n       详情: {note}" if note else ""))


def check_that(name, cond, detail=""):
    """条件断言。`detail` 只是失败时给人看的补充信息，**不参与比较**。

    以前这里写成 `check(name, True, detail)`：一旦传了 detail，
    expect 是 True、actual 是字符串，永远不相等 —— 所有带详情的断言都会
    假失败。现在 detail 走 note，比较的永远是布尔值。
    """
    check(name, True, bool(cond), note=str(detail) if detail != "" else "")


def req(method, path, body=None, token=None, raw=False, headers=None):
    """返回 (status, 解析后的 body 或原始文本)"""
    url = BASE + path
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    if token:
        hdrs["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            payload = resp.read()
            if raw:
                return resp.status, payload.decode("utf-8", "replace")
            return resp.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload.decode("utf-8", "replace")


def wait_ready(proc):
    for _ in range(100):
        if proc.poll() is not None:
            raise RuntimeError("进程提前退出，退出码 %s" % proc.returncode)
        try:
            with socket.create_connection(("127.0.0.1", PORT), timeout=0.3):
                return
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("服务 10 秒内没起来")


def start(binary):
    env = dict(os.environ)
    env.update(
        {
            "GUARDIAN_ADMIN_PASSWORD": PASSWORD,
            "GUARDIAN_PORT": str(PORT),
            "GUARDIAN_HOST": "127.0.0.1",
            "GUARDIAN_DATA": DATA,
        }
    )
    log = open(os.path.join(TMP, "server.log"), "ab")
    proc = subprocess.Popen(
        [binary], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT
    )
    wait_ready(proc)
    return proc


def migration_case(binary):
    """旧版 JSON → SQLite 自动迁移。

    v2.1.x 及以前是单个 state.json；v2.2 启动时会自动导入并改名。
    这里造一份"真实的旧数据"，验证设备、事件、时间线、每日汇总都搬过来了，
    而且原文件被改名（不会每次启动都重复导入）。
    """
    global DATA
    print()
    print("[旧版 JSON 迁移]")

    d = os.path.join(TMP, "migrate")
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    now = time.time()
    yday = time.strftime("%Y-%m-%d", time.localtime(now - 86400))
    # 时间线放在最近 10 分钟，避免刚好跨午夜导致"今天"查不到
    legacy = {
        "version": 1,
        "users": [],
        "devices": [
            {
                "id": "dev_old",
                "name": "老平板",
                "model": "OPPO A9",
                "token": "tok_old_123",
                "created_at": now - 86400 * 30,
                "last_seen": now - 60,
                "enabled": True,
                "use_minutes": 40,
                "rest_minutes": 5,
                "presence": [{"start": now - 600, "end": now - 60}],
                "usage": [
                    {
                        "start": now - 600,
                        "end": now - 300,
                        "pkg": "tv.danmaku.bili",
                        "label": "哔哩哔哩",
                    },
                    {
                        "start": now - 300,
                        "end": now - 60,
                        "pkg": "com.tencent.mm",
                        "label": "微信",
                    },
                ],
            }
        ],
        "commands": [],
        "events": [
            {
                "id": 1,
                "device_id": "dev_old",
                "kind": "pair",
                "message": "老平板 配对完成",
                "created_at": now - 86400,
            },
            {
                "id": 2,
                "device_id": "dev_old",
                "kind": "rest_start",
                "message": "老平板 进入强制休息",
                "created_at": now - 7200,
            },
        ],
        "pair_codes": [],
        "sessions": [],
        "daily": [
            {"device_id": "dev_old", "day": yday, "used_ms": 1_234_000, "rest_count": 3}
        ],
    }
    with open(os.path.join(d, "state.json"), "w", encoding="utf-8") as f:
        json.dump(legacy, f, ensure_ascii=False)

    DATA = os.path.join(d, "guardian.db")
    p = start(binary)
    try:
        check_that("迁移后生成 .db 文件", os.path.isfile(DATA), DATA)
        check_that(
            "原 JSON 已改名（不会二次导入）",
            not os.path.isfile(os.path.join(d, "state.json")),
            os.listdir(d),
        )

        st, body = req("POST", "/api/login", {"username": "admin", "password": PASSWORD})
        check("迁移后能登录 200", 200, st)
        tk = body["token"]

        st, body = req("GET", "/api/overview", token=tk)
        check("迁移后 1 台设备", 1, body["stats"]["devices_total"])
        check("设备名保留", "老平板", body["devices"][0]["name"])

        st, body = req("GET", "/api/events", token=tk)
        check_that("旧事件都搬过来了", body["total"] >= 2, body["total"])
        msgs = [e["message"] for e in body["events"]]
        check_that("能找到旧事件原文", any("配对完成" in m for m in msgs), msgs)

        st, body = req("GET", "/api/devices/dev_old/timeline", token=tk)
        check_that("在线区间搬过来了", len(body["presence"]) >= 1, len(body["presence"]))
        labels = [a["label"] for a in body["apps"]]
        check_that("应用片段搬过来了", "哔哩哔哩" in labels, labels)

        st, body = req("GET", "/api/devices/dev_old", token=tk)
        days = [x["day"] for x in body["daily"]]
        check_that("每日汇总搬过来了", yday in days, days)

        st, body = req("GET", "/api/devices/dev_old/events", token=tk)
        check_that("按天事件接口可用", "total" in body, list(body))
    finally:
        p.terminate()
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            p.kill()


def main():
    binary = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "target", "x86_64-pc-windows-gnu", "release", "guardian-server.exe"
    )
    if not os.path.isfile(binary):
        print(f"找不到可执行文件：{binary}")
        return 2

    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP)
    proc = start(binary)
    try:
        # ---------- 静态页面 ----------
        print("[页面]")
        st, html = req("GET", "/", raw=True)
        check("首页 200", 200, st)
        check_that("首页含标题", "使用守护" in html)
        st, css = req("GET", "/static/app.css", raw=True)
        check("css 200", 200, st)
        check_that("css 是样式表", "{" in css)
        st, js = req("GET", "/static/app.js", raw=True)
        check("js 200", 200, st)
        check_that("js 有 fetch 逻辑", "api/overview" in js)
        st, body = req("GET", "/healthz")
        check("healthz 200", 200, st)
        check("healthz ok", True, body["ok"])

        # ---------- 登录 ----------
        print("[登录]")
        st, body = req("POST", "/api/login", {"password": "wrong"})
        check("错口令 401", 401, st)
        check("错口令提示", "用户名或密码不正确", body["detail"])
        st, body = req("POST", "/api/login", {"username": "nobody", "password": "x"})
        check("不存在的用户 401", 401, st)
        check("不泄漏账号是否存在", "用户名或密码不正确", body["detail"])
        st, body = req("GET", "/api/overview")
        check("未登录 401", 401, st)
        # 不带 username 也要能登（老前端 / 脚本兼容）
        st, body = req("POST", "/api/login", {"password": PASSWORD})
        check("正确口令 200", 200, st)
        token = body["token"]
        check_that("拿到 token", len(token) == 64, len(token))
        check("默认口令标记", False, body["using_default_password"])
        check("登录返回角色", "admin", body["user"]["role"])
        check_that("登录返回 is_admin", body["user"]["is_admin"] is True)

        st, body = req("GET", "/api/me", token=token)
        check("me 200", 200, st)
        check("me 用户名", "admin", body["user"]["username"])

        st, body = req("GET", "/api/overview", token=token)
        check("overview 200", 200, st)
        check("空库设备数 0", 0, body["stats"]["devices_total"])
        check("趋势 7 天", 7, len(body["trend"]))

        # ---------- 配对 ----------
        print("[配对]")
        st, body = req("POST", "/api/pair-codes", {}, token=token)
        check("生成配对码 200", 200, st)
        code = body["code"]
        check_that("配对码 6 位数字", len(code) == 6 and code.isdigit(), code)

        st, body = req(
            "POST",
            "/api/device/pair",
            {"code": code, "name": "客厅平板", "model": "HUAWEI AGS5", "app_version": "1.8"},
        )
        check("设备配对 200", 200, st)
        dev_id = body["device_id"]
        dev_token = body["token"]
        check("心跳间隔 20", 20, body["heartbeat_seconds"])
        check("设备名回显", "客厅平板", body["name"])

        st, body = req(
            "POST", "/api/device/pair", {"code": code, "name": "偷用", "model": "x"}
        )
        check("配对码不能复用", 400, st)
        # 文案后面还可能接着写"请在后台重新生成"，只校验关键片段
        check_that("复用提示", "配对码已被使用" in (body.get("detail") or ""),
                   body.get("detail"))

        st, body = req("POST", "/api/device/pair", {"code": "000000", "name": "x"})
        check("不存在的配对码", 400, st)

        # ---------- 心跳 ----------
        print("[心跳]")
        hb = {
            "app_version": "1.8",
            "enabled": True,
            "resting": False,
            "used_ms": 600_000,
            "today_used_ms": 1_500_000,
            "rest_count": 2,
            "use_minutes": 30,
            "rest_minutes": 5,
            "reset_gap_seconds": 300,
            "tamper_policy": False,
            "pin_set": True,
            "screen_on": True,
            "audio_suppressed": False,
            "pause_media_on_rest": True,
            "mute_on_rest": False,
        }
        st, body = req("POST", "/api/device/heartbeat", hb, token=dev_token)
        check("心跳 200", 200, st)
        check("暂无指令", 0, len(body["commands"]))
        check("设备名", "客厅平板", body["device_name"])

        st, body = req("POST", "/api/device/heartbeat", hb, token="bogus")
        check("错误设备令牌 401", 401, st)
        check("令牌提示", "设备令牌无效，请重新配对", body["detail"])

        # ---------- 时间线：在线区间 + 前台应用 ----------
        print("[时间线]")
        t0 = time.time()
        segs = {
            "segments": [
                {"start": t0 - 1800, "end": t0 - 1200, "pkg": "com.ss.android.ugc.aweme", "label": "抖音"},
                {"start": t0 - 1200, "end": t0 - 600, "pkg": "tv.danmaku.bili", "label": "哔哩哔哩"},
                {"start": t0 - 600, "end": t0 - 60, "pkg": "tv.danmaku.bili", "label": "哔哩哔哩"},
            ]
        }
        st, body = req("POST", "/api/device/usage", segs, token=dev_token)
        check("上报应用片段 200", 200, st)
        check("接收条数 3", 3, body["accepted"])

        # 幂等：同一批再报一次，不应该翻倍
        req("POST", "/api/device/usage", segs, token=dev_token)

        st, body = req("POST", "/api/device/usage", {"segments": []}, token="bogus")
        check("错误设备令牌上报 401", 401, st)

        st, body = req("GET", f"/api/devices/{dev_id}/timeline", token=token)
        check("时间线 200", 200, st)
        check_that("返回当天日期", len(body["day"]) == 10, body["day"])
        check_that("在线区间非空", len(body["presence"]) >= 1, len(body["presence"]))
        # 首尾相接的两段 B 站要合并成一段 —— 这正是"每 20 秒心跳各存一条"
        # 导致时间轴挤成一团、时长也算不准的根因修复
        check("相邻同包合并成 2 段", 2, len(body["usage"]))
        apps = {a["label"]: a for a in body["apps"]}
        check_that("聚合出两个应用", len(apps) == 2, list(apps))
        check("哔哩哔哩合计 19 分钟（毫秒）", 1_140_000, apps.get("哔哩哔哩", {}).get("ms"))
        check("合并后只算 1 次", 1, apps.get("哔哩哔哩", {}).get("count"))
        fg = body["totals"]["foreground_ms"]
        check_that("前台总时长约 29 分钟", abs(fg - 1_740_000) < 5_000, fg)
        check_that("在线总时长按毫秒下发",
                   abs(body["totals"]["online_ms"]
                       - sum(p["end"] - p["start"] for p in body["presence"]) * 1000) < 2000,
                   body["totals"]["online_ms"])
        check_that("时区字段下发", "tz" in body, body.get("tz"))

        st, body = req("GET", f"/api/devices/{dev_id}/timeline?day=2020-01-01", token=token)
        check("历史日期 200", 200, st)
        check("历史日期无应用数据", 0, len(body["usage"]))

        # ---------- v2.3：亮屏区间 + 解锁/开关机 ----------
        print("[屏幕与设备状态]")
        st, tbody = req("GET", f"/api/devices/{dev_id}/timeline", token=token)
        today = tbody["today"]   # 服务器时区的今天，计数必须按它归集
        payload = {
            "segments": [],
            "screen": [
                {"start": t0 - 3600, "end": t0 - 1800},
                {"start": t0 - 1700, "end": t0 - 120},
            ],
            "day": today,
            "unlocks": 7,
            "boots": 1,
            "shutdowns": 0,
            "screen_ms": 3_300_000,
            "sys_ms": 1_800_000,
        }
        st, body = req("POST", "/api/device/usage", payload, token=dev_token)
        check("上报亮屏区间 200", 200, st)
        check("接收亮屏段数 2", 2, body["screen"])
        check_that("计数已保存", body["daystat"] is True, body)

        st, body = req("GET", f"/api/devices/{dev_id}/timeline", token=token)
        check("时间线含屏幕轨道", 200, st)
        check_that("亮屏区间已存下来", len(body["screen"]) >= 1, len(body["screen"]))
        tot = body["totals"]
        check("亮屏已采集", True, tot["screen_tracked"])
        # 亮屏时长必须等于"下发的这些区间之和" —— 不写死数字，
        # 免得测试刚好在半夜跑、区间被当天边界裁掉一半导致误报
        expect_ms = sum(s["end"] - s["start"] for s in body["screen"]) * 1000
        check_that("亮屏时长与区间一致", abs(tot["screen_ms"] - expect_ms) < 2_000,
                   f'{tot["screen_ms"]} vs {expect_ms:.0f}')
        check("当天解锁 7 次", 7, tot["unlocks"])
        check("当天开机 1 次", 1, tot["boots"])
        check("当天关机 0 次", 0, tot["shutdowns"])
        check("系统口径时长", 1_800_000, tot["sys_ms"])

        # 幂等：再报一遍，计数不能翻倍（服务端取最大值合并）
        payload["unlocks"] = 7
        req("POST", "/api/device/usage", payload, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}/timeline", token=token)
        check("重复上报后解锁仍是 7", 7, body["totals"]["unlocks"])

        # 设备详情页首屏也要带上当天计数
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("设备详情 200", 200, st)
        check("详情页带宽屏采集标记", True, body["today"]["screen_tracked"])
        check("详情页带解锁次数", 7, body["today"]["unlocks"])

        # 首页设备卡：今日解锁/开机/亮屏
        st, body = req("GET", "/api/overview", token=token)
        d0 = [x for x in body["devices"] if x["id"] == dev_id][0]
        check("首页设备卡带解锁", 7, d0["today_unlocks"])
        check_that("首页设备卡带亮屏时长", d0["today_screen_ms"] > 0, d0["today_screen_ms"])

        st, body = req("GET", f"/api/devices/{dev_id}/timeline?day=abc", token=token)
        check("非法日期 400", 400, st)

        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("详情带今日应用", 2, len(body["today"]["apps"]))
        check("详情标记已采集", True, body["today"]["usage_tracked"])

        st, body = req("GET", "/api/overview", token=token)
        check("设备总数 1", 1, body["stats"]["devices_total"])
        check("在线 1", 1, body["stats"]["devices_online"])
        check("守护关闭数 0", 0, body["stats"]["devices_off"])
        d = body["devices"][0]
        check("设备在线", True, d["online"])
        check("本轮时长", 600_000, d["used_ms"])
        check("今日时长", 1_500_000, d["today_used_ms"])
        check("已设密码", True, d["pin_set"])
        check_that("列表里没有 token", "token" not in d)

        # ---------- 远程指令 ----------
        print("[远程指令]")
        # ★ v2.3.1：不传 minutes 时必须取「设备自己配置的休息时长」，不能写死 5。
        # 老版本固定补默认值 5：后台按钮上写着"锁定 1 分钟"，孩子实际被锁 5 分钟。
        hb_min1 = dict(hb)
        hb_min1["rest_minutes"] = 1
        req("POST", "/api/device/heartbeat", hb_min1, token=dev_token)

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "lock", "params": {}}, token=token
        )
        check("下发锁定 200", 200, st)
        lock_cid = body["command_id"]

        # 显式传 minutes 时以传入值为准
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "lock", "params": {"minutes": 7}},
            token=token,
        )
        check("显式 minutes 200", 200, st)
        explicit_cid = body["command_id"]

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "reboot", "params": {}}, token=token
        )
        check("非法指令 400", 400, st)
        check_that("非法指令提示", "不支持的指令" in body["detail"])

        st, body = req(
            "POST", "/api/devices/nope/command", {"type": "lock", "params": {}}, token=token
        )
        check("不存在的设备 404", 404, st)

        # 2.3.1 之前的 App 不支持清零违规计数：要在下发前拦住，别让家长白点
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "reset_tamper", "params": {}},
            token=token,
        )
        check("旧版本清零被拒 400", 400, st)
        check_that("旧版本清零提示版本", "2.3.1" in body["detail"], body["detail"])

        st, body = req("POST", "/api/device/heartbeat", hb, token=dev_token)
        check("心跳取到 2 条指令", 2, len(body["commands"]))
        c0 = body["commands"][0]
        check("指令类型 lock", "lock", c0["type"])
        check("不传 minutes 时取设备配置的 1 分钟", 1, c0["params"]["minutes"])
        check("显式 minutes 不被改写", 7, body["commands"][1]["params"]["minutes"])

        # 已取走的指令不会再发一次
        st, body = req("POST", "/api/device/heartbeat", hb, token=dev_token)
        check("不重复下发", 0, len(body["commands"]))

        print("[配置下发]")
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {
                "type": "config",
                "params": {
                    "use_minutes": 45,
                    "rest_minutes": 10,
                    "reset_gap_seconds": 600,
                    "tamper_policy": True,
                    "mute_on_rest": True,
                },
            },
            token=token,
        )
        check("下发配置 200", 200, st)
        cfg_cid = body["command_id"]

        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "config", "params": {"whatever": 1}},
            token=token,
        )
        check("空配置 400", 400, st)

        st, body = req("POST", "/api/device/heartbeat", hb, token=dev_token)
        check("配置指令下发", 1, len(body["commands"]))
        p = body["commands"][0]["params"]
        check("配置 use_minutes", 45, p["use_minutes"])
        check("配置 rest_minutes", 10, p["rest_minutes"])
        check("配置 reset_gap", 600, p["reset_gap_seconds"])
        check("配置 tamper", True, p["tamper_policy"])
        check("配置 mute", True, p["mute_on_rest"])
        check_that("配置过滤多余字段", "whatever" not in p)

        print("[指令回执]")
        st, body = req(
            "POST",
            "/api/device/result",
            {"command_id": lock_cid, "ok": True, "message": "已进入休息"},
            token=dev_token,
        )
        check("回执 200", 200, st)
        st, body = req(
            "POST", "/api/device/result", {"command_id": cfg_cid, "ok": False, "message": "参数非法"},
            token=dev_token,
        )
        check("失败回执 200", 200, st)

        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("详情 200", 200, st)
        check("指令 3 条", 3, len(body["commands"]))
        statuses = sorted(c["status"] for c in body["commands"])
        check("指令状态（显式分钟那条未回执）", ["done", "failed", "sent"], statuses)
        check("近 14 天有数据", 1, len(body["daily"]))
        check("详情有事件", True, len(body["events"]) > 0)

        # ---------- 清零违规计数（v2.3.1） ----------
        #
        # 惩罚休息的时长 = 配置值 × 违规倍数（第 3 次起 ×3），
        # 误判累积上去之后家长必须能远程把它归零，否则只能爬到设备上点。
        print("[清零违规计数]")
        hb_new = dict(hb)
        hb_new.update({"app_version": "2.3.1", "tamper_strikes": 3})
        req("POST", "/api/device/heartbeat", hb_new, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("设备上报的违规次数", 3, body["device"]["tamper_strikes"])

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "reset_tamper", "params": {}},
            token=token,
        )
        check("新版本可清零 200", 200, st)
        reset_cid = body["command_id"]

        st, body = req("POST", "/api/device/heartbeat", hb_new, token=dev_token)
        check("清零指令下发到设备", "reset_tamper", body["commands"][0]["type"])

        st, body = req(
            "POST", "/api/device/result",
            {"command_id": reset_cid, "ok": True, "message": "已清零"}, token=dev_token,
        )
        check("清零回执 200", 200, st)

        hb_new["tamper_strikes"] = 0
        req("POST", "/api/device/heartbeat", hb_new, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("清零后违规次数归零", 0, body["device"]["tamper_strikes"])

        # ---------- 停用时段（v2.4） ----------
        #
        # 和"用一段时间就休息"并列的第二类限制：规定每天几点到几点不能玩。
        # 最容易出事的地方是「规则存了但没生效」—— 家长以为设好了，
        # 到点孩子照样能玩，事后还查不出原因。所以下面重点验：
        #   ① 旧 App 必须被拦住（不能静默失败）；
        #   ② 规则要落库（设备离线也得留着）；
        #   ③ 每次心跳都要把完整规则带下去（设备重装能自愈）。
        print("[停用时段]")
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "quiet", "params": {"rules": [{"from": 1320, "to": 360, "days": 0}]}},
            token=token,
        )
        check("旧版本设置停用被拒 400", 400, st)
        check_that("提示要升级到 2.4.0", "2.4.0" in body["detail"], body["detail"])

        hb_v24 = dict(hb)
        hb_v24["app_version"] = "2.4.0"
        req("POST", "/api/device/heartbeat", hb_v24, token=dev_token)

        # 晚上 22:00 → 次日 06:00，每天
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {
                "type": "quiet",
                "params": {
                    "rules": [
                        {"id": "night", "label": "睡觉", "from": 22 * 60, "to": 6 * 60, "days": 0}
                    ]
                },
            },
            token=token,
        )
        check("设置停用时段 200", 200, st)
        quiet_cid = body["command_id"]

        st, body = req("POST", "/api/device/heartbeat", hb_v24, token=dev_token)
        cmds = body["commands"]
        check_that(
            "停用指令下发到设备",
            any(c["type"] == "quiet" for c in cmds),
            [c["type"] for c in cmds],
        )
        qcmd = next(c for c in cmds if c["type"] == "quiet")
        qr = qcmd["params"]["rules"][0]
        check("规则开始 22:00", 1320, qr["from"])
        check("规则结束 06:00（次日）", 360, qr["to"])
        check("规则 id 保留", "night", qr["id"])
        check("规则标签保留", "睡觉", qr["label"])

        # 心跳每次都带完整规则：设备重装 / 换机之后靠这个自愈
        check("心跳带下停用规则", 1, len(body["quiet_hours"]))
        check("停用功能生效中", True, body["quiet_active"])

        st, body = req("GET", f"/api/devices/{dev_id}/quiet", token=token)
        check("规则查询 200", 200, st)
        check("服务端已落库 1 条", 1, len(body["rules"]))
        check("落库的规则生效中", True, body["active"])

        # --- 校验：这些输入必须被拒绝，不能存进去之后"看起来设好了" ---
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "quiet", "params": {"rules": [{"from": 600, "to": 600, "days": 0}]}},
            token=token,
        )
        check("起止相同 400", 400, st)
        check_that("起止相同的提示", "开始和结束时间相同" in body["detail"], body["detail"])

        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "quiet", "params": {"rules": [{"to": 600, "days": 0}]}},
            token=token,
        )
        check("缺 from 400", 400, st)

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command",
            {"type": "quiet", "params": {"rules": []}}, token=token,
        )
        check("空数组允许（=清空）", 200, st)
        empty_cid = body["command_id"]

        st, body = req("POST", "/api/device/heartbeat", hb_v24, token=dev_token)
        check("清空后没有生效规则", False, body["quiet_active"])
        check("清空后规则为空", 0, len(body["quiet_hours"]))

        st, body = req(
            "POST", f"/api/device/result",
            {"command_id": empty_cid, "ok": True, "message": "已清空"}, token=dev_token,
        )
        check("清空回执 200", 200, st)

        # --- 重新设两条：一条跨夜、一条白天，验证 days 掩码与截断 ---
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {
                "type": "quiet",
                "params": {
                    "rules": [
                        {"id": "a", "from": 1320, "to": 360, "days": 0},
                        {
                            "id": "b",
                            "label": "一二三四五六七八九十一二三四五六七八九十",
                            "from": 360,
                            "to": 480,
                            "days": 999,
                        },
                    ]
                },
            },
            token=token,
        )
        check("两条规则 200", 200, st)

        st, body = req("GET", f"/api/devices/{dev_id}/quiet", token=token)
        check("两条都落库", 2, len(body["rules"]))
        r_b = next(r for r in body["rules"] if r["id"] == "b")
        check("days 被夹到 127", 127, r_b["days"])
        check_that("label 截到 20 字", len(r_b["label"]) <= 20, len(r_b["label"]))

        # --- 整体开关：只带 enabled，服务端要展开成完整规则再下发 ---
        #
        # 以前这里返回的是 {"enabled": false}，而设备端只解析 rules，
        # 于是"整体暂停"在设备上静默失效。现在必须返回完整规则。
        #
        # 先用一次心跳把上一条 quiet 指令取走：否则下面拿到的 commands 里会有
        # 两条 quiet，断言抓到的是旧那条，看起来像"新功能没生效"。
        req("POST", "/api/device/heartbeat", hb_v24, token=dev_token)

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command",
            {"type": "quiet", "params": {"enabled": False}}, token=token,
        )
        check("整体关闭 200", 200, st)
        st, body = req("POST", "/api/device/heartbeat", hb_v24, token=dev_token)
        qcmds = [c for c in body["commands"] if c["type"] == "quiet"]
        check("整体关闭只下发一条", 1, len(qcmds))
        qcmd = qcmds[0]
        check_that(
            "整体关闭下发的是完整规则",
            len(qcmd["params"]["rules"]) == 2
            and all(r["enabled"] is False for r in qcmd["params"]["rules"]),
            qcmd["params"],
        )
        check("整体关闭后不生效", False, body["quiet_active"])

        # --- 设备上报停用状态 + 进出事件 ---
        hb_q = dict(hb_v24)
        hb_q.update({"in_quiet": True, "quiet_left_ms": 3_600_000, "quiet_label": "每天 22:00–06:00"})
        req("POST", "/api/device/heartbeat", hb_q, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("设备在停用中", True, body["device"]["in_quiet"])
        check("停用剩余时长", 3_600_000, body["device"]["quiet_left_ms"])
        check("停用描述", "每天 22:00–06:00", body["device"]["quiet_label"])

        st, body = req("GET", "/api/events", token=token)
        kinds = [e["kind"] for e in body["events"]]
        check_that("有 quiet_start 事件", "quiet_start" in kinds, kinds[:8])

        hb_q2 = dict(hb_v24)
        hb_q2.update({"in_quiet": False})
        req("POST", "/api/device/heartbeat", hb_q2, token=dev_token)
        st, body = req("GET", "/api/events", token=token)
        kinds = [e["kind"] for e in body["events"]]
        check_that("有 quiet_end 事件", "quiet_end" in kinds, kinds[:8])

        # 设备自己上报的规则一律不采信（否则改一下手机就能绕过宵禁）
        hb_fake = dict(hb_v24)
        hb_fake.update({"quiet_hours": [{"id": "x", "from": 0, "to": 60, "days": 0}]})
        req("POST", "/api/device/heartbeat", hb_fake, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}/quiet", token=token)
        check_that(
            "设备上报的规则不影响服务端",
            all(r["id"] != "x" for r in body["rules"]),
            [r["id"] for r in body["rules"]],
        )

        # 别人的设备：配置读取也要 404（不是 403，避免探测设备 id 是否存在）。
        # 这里放在多账号段落里验证 —— 那时才有第二个账号的 token。

        # ---------- 每日总时长（v2.5） ----------
        #
        # 第三个维度：一天累计用满就锁到次日 0 点，**不会自己解除**。
        # 这里重点验四件事：
        #   ① 旧 App 必须被拦住（否则家长以为设好了，孩子照样玩一整天）；
        #   ② 上限要落库（设备离线时设置不能丢）；
        #   ③ 设备上报的 day_locked 要能被后台看见，并写进事件流；
        #   ④ 今日解除 / 恢复限制两条指令确实发得下去。
        print("[每日总时长]")
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "day_limit", "params": {"minutes": 90}},
            token=token,
        )
        check("旧版本设置每日上限被拒 400", 400, st)
        check_that("提示要升级到 2.5.0", "2.5.0" in body["detail"], body["detail"])

        # 升级后再设。注意心跳里**不带** day_limit_minutes：
        # 缺字段时服务端保留旧值，这条规则本身也要被试到。
        hb_v25 = dict(hb)
        hb_v25["app_version"] = "2.5.0"
        req("POST", "/api/device/heartbeat", hb_v25, token=dev_token)

        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "day_limit", "params": {"minutes": 90}},
            token=token,
        )
        check("设置每日上限 200", 200, st)

        st, body = req("POST", "/api/device/heartbeat", hb_v25, token=dev_token)
        cmds = body.get("commands", [])
        check_that(
            "每日上限指令下发到设备",
            any(c["type"] == "day_limit" for c in cmds),
            [c["type"] for c in cmds],
        )
        dcmd = next((c for c in cmds if c["type"] == "day_limit"), None)
        check("下发分钟数 90", 90, dcmd["params"]["minutes"] if dcmd else -1)

        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("上限已落库", 90, body["device"]["day_limit_minutes"])
        check("缺字段心跳没把它抹成 0", 90, body["device"]["day_limit_minutes"])

        # 设备上报"今天已经用完"
        hb_locked = dict(hb_v25)
        hb_locked.update({"day_locked": True, "today_used_ms": 90 * 60 * 1000})
        req("POST", "/api/device/heartbeat", hb_locked, token=dev_token)
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("设备已锁状态被后台看见", True, body["device"]["day_locked"])

        st, body = req("GET", "/api/events", token=token)
        kinds = [e["kind"] for e in body["events"]]
        check_that("有 day_lock 事件", "day_lock" in kinds, kinds[:8])

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "day_unlock"}, token=token
        )
        check("今日解除 200", 200, st)
        st, body = req("POST", "/api/device/heartbeat", hb_locked, token=dev_token)
        check_that(
            "今日解除指令下发",
            any(c["type"] == "day_unlock" for c in body.get("commands", [])),
            [c["type"] for c in body.get("commands", [])],
        )

        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "day_relock"}, token=token
        )
        check("恢复今日限制 200", 200, st)

        # 超范围要被夹住：1440 分钟（24 小时）是语义上的最大值，
        # 再大就等于"把设备锁死"，不能让它进库
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "day_limit", "params": {"minutes": 99999}},
            token=token,
        )
        check("设置超范围上限 200", 200, st)
        st, body = req("POST", "/api/device/heartbeat", hb_v25, token=dev_token)
        dcmd = next((c for c in body.get("commands", []) if c["type"] == "day_limit"), None)
        check("超范围被夹到 1440", 1440, dcmd["params"]["minutes"] if dcmd else -1)

        # 0 = 不限，这个值必须能存下去（家长关掉这个维度的唯一方式）
        st, body = req(
            "POST",
            f"/api/devices/{dev_id}/command",
            {"type": "day_limit", "params": {"minutes": 0}},
            token=token,
        )
        check("关闭每日上限 200", 200, st)
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("上限已改为不限", 0, body["device"]["day_limit_minutes"])

        # ---------- 休息状态变化写事件 ----------
        print("[事件流]")
        hb_rest = dict(hb)
        hb_rest.update({"resting": True, "rest_left_ms": 300_000})
        req("POST", "/api/device/heartbeat", hb_rest, token=dev_token)
        st, body = req("GET", "/api/events", token=token)
        check("事件 200", 200, st)
        kinds = [e["kind"] for e in body["events"]]
        check_that("有 rest_start 事件", "rest_start" in kinds, kinds[:8])
        check_that("有配对事件", "pair" in kinds)

        # ---------- 事件分页 / 按日期查询（v2.2） ----------
        print("[事件分页]")
        st, p1body = req("GET", "/api/events?page=1&size=2", token=token)
        check("分页 200", 200, st)
        check("每页 2 条", 2, len(p1body["events"]))
        check_that("返回总数", p1body["total"] >= 2, p1body["total"])
        check_that(
            "页数按 size 算对了",
            p1body["pages"] == (p1body["total"] + 1) // 2,
            (p1body["total"], p1body["pages"]),
        )
        check("page 原样回传", 1, p1body["page"])

        # 第二页不能和第一页重复 —— 分页最容易错的地方是 offset 写反
        ids1 = [e["id"] for e in p1body["events"]]
        st, p2body = req("GET", "/api/events?page=2&size=2", token=token)
        ids2 = [e["id"] for e in p2body["events"]]
        check_that("第二页与第一页不重复", not (set(ids1) & set(ids2)), (ids1, ids2))

        # size 上限 200，传超了要夹住（防止一次拉爆内存）
        st, big = req("GET", "/api/events?size=9999", token=token)
        check("size 被夹到 200", 200, big["size"])

        today = p1body["today"]
        st, body = req("GET", f"/api/events?day={today}", token=token)
        check("按今天查询 200", 200, st)
        check_that("今天有事件", body["total"] >= 1, body["total"])
        check("day 原样返回", today, body["day"])

        st, body = req("GET", "/api/events?day=2020-01-01", token=token)
        check("2020-01-01 没有事件", 0, body["total"])

        st, body = req("GET", "/api/events?day=abc", token=token)
        check("事件非法日期 400", 400, st)

        # 设备维度：按天查某台设备的事件
        st, body = req("GET", f"/api/devices/{dev_id}/events?day={today}", token=token)
        check("设备按天事件 200", 200, st)
        check_that(
            "设备事件都归属这台设备",
            all(e.get("device_id") == dev_id for e in body["events"]),
            [e.get("device_id") for e in body["events"][:5]],
        )
        check_that("设备事件带分页字段", "pages" in body and "total" in body, list(body))

        st, body = req("GET", f"/api/devices/{dev_id}/events?day=abc", token=token)
        check("设备事件非法日期 400", 400, st)

        st, body = req("GET", "/api/overview", token=token)
        check("休息中计数 1", 1, body["stats"]["devices_resting"])

        # ---------- 多账号与权限隔离 ----------
        print("[多账号]")
        st, body = req(
            "POST",
            "/api/users",
            {"username": "mama", "password": "mama-pass-1", "role": "user", "note": "妈妈"},
            token=token,
        )
        check("创建账号 200", 200, st)
        mama_id = body["id"]
        check("新账号是普通角色", "user", body["user"]["role"])

        st, body = req("POST", "/api/users", {"username": "MAMA", "password": "x123456"}, token=token)
        check("用户名重复（大小写不敏感）400", 400, st)
        st, body = req("POST", "/api/users", {"username": "a", "password": "x123456"}, token=token)
        check("用户名太短 400", 400, st)
        st, body = req("POST", "/api/users", {"username": "bob", "password": "123"}, token=token)
        check("密码太短 400", 400, st)
        st, body = req("POST", "/api/users", {"username": "bob", "password": "123456", "role": "boss"}, token=token)
        check("未知角色 400", 400, st)

        st, body = req("POST", "/api/login", {"username": "mama", "password": "mama-pass-1"})
        check("普通账号登录 200", 200, st)
        mama_tok = body["token"]
        check("普通账号角色", "user", body["user"]["role"])
        check_that("普通账号 is_admin=False", body["user"]["is_admin"] is False)

        st, body = req("GET", "/api/users", token=mama_tok)
        check("普通账号不能管账号 403", 403, st)

        st, body = req("GET", "/api/overview", token=mama_tok)
        check("普通账号看不到别人的设备", 0, body["stats"]["devices_total"])
        st, body = req("GET", f"/api/devices/{dev_id}", token=mama_tok)
        check("越权看设备详情 404", 404, st)
        st, body = req(
            "POST", f"/api/devices/{dev_id}/command", {"type": "lock", "params": {}}, token=mama_tok
        )
        check("越权下发指令 404", 404, st)

        # 普通账号给自己配一台设备
        st, body = req("POST", "/api/pair-codes", {}, token=mama_tok)
        check("普通账号生成配对码 200", 200, st)
        check("配对码归属自己", "mama", body["owner"])
        code2 = body["code"]
        st, body = req("POST", "/api/device/pair", {"code": code2, "name": "妈妈手机", "model": "X"})
        check("第二台设备配对 200", 200, st)
        dev2 = body["device_id"]

        st, body = req("GET", "/api/overview", token=mama_tok)
        check("普通账号只看到自己那台", 1, body["stats"]["devices_total"])
        st, body = req("GET", "/api/overview", token=token)
        check("管理员看到全部 2 台", 2, body["stats"]["devices_total"])
        check_that(
            "设备列表带归属账号",
            {d["owner"] for d in body["devices"]} == {"admin", "mama"},
            sorted(d["owner"] for d in body["devices"]),
        )

        # 管理员可以替指定账号生成配对码
        st, body = req("POST", f"/api/pair-codes?owner={mama_id}", {}, token=token)
        check("管理员代生成配对码 200", 200, st)
        check("代生成码归属 mama", "mama", body["owner"])
        # 普通账号传 owner 试图指给别人 → 被忽略，仍归自己
        st, body = req("POST", "/api/pair-codes?owner=u_admin", {}, token=mama_tok)
        check("普通账号的 owner 参数被忽略", "mama", body["owner"])

        # 事件流隔离
        st, body = req("GET", "/api/events", token=mama_tok)
        check("普通账号事件 200", 200, st)
        check_that(
            "普通账号事件只涉自己设备",
            all(e.get("device_id") == dev2 for e in body["events"]),
            [(e.get("device_id"), e["kind"]) for e in body["events"][:5]],
        )

        # 按天事件接口同样要挡住越权（用 404 而不是 403，避免探测设备 id）
        st, body = req("GET", f"/api/devices/{dev_id}/events", token=mama_tok)
        check("普通账号查别人设备的按天事件 404", 404, st)

        # 停用时段规则同理：这是"孩子几点不能玩手机"的作息安排，
        # 泄露出去等于把家里的作息告诉别人
        st, body = req("GET", f"/api/devices/{dev_id}/quiet", token=mama_tok)
        check("越权读停用规则 404", 404, st)

        # 删除账号必须先处理名下的设备
        st, body = req("POST", f"/api/users/{mama_id}/delete", {}, token=token)
        check("有设备时不能直接删 400", 400, st)
        check_that("提示要指定转移目标", "转给" in body["detail"])
        st, body = req("POST", f"/api/users/{mama_id}/delete?reassign=u_admin", {}, token=token)
        check("指定转移后可删 200", 200, st)
        check("转移了 1 台设备", 1, body["moved_devices"])
        st, body = req("POST", "/api/login", {"username": "mama", "password": "mama-pass-1"})
        check("删除后无法登录 401", 401, st)
        st, body = req("GET", "/api/overview", token=mama_tok)
        check("会话随账号一起失效 401", 401, st)
        st, body = req("GET", "/api/overview", token=token)
        check("设备没丢（已转给管理员）", 2, body["stats"]["devices_total"])

        # 自我保护：不能把自己锁在门外
        st, body = req("POST", "/api/users/u_admin", {"disabled": True}, token=token)
        check("不能停用自己 400", 400, st)
        st, body = req("POST", "/api/users/u_admin/delete", {}, token=token)
        check("不能删除自己 400", 400, st)
        st, body = req("POST", "/api/users/u_admin", {"role": "user"}, token=token)
        check("不能取消自己的管理员 400", 400, st)

        # ---------- 答题解锁：题库与下发（v2.6.0） ----------
        print("[答题解锁 · 题库]")
        st, body = req("GET", "/api/banks", token=token)
        check("题库列表 200", 200, st)
        check_that("内置题库已自动导入", len(body["banks"]) >= 3, len(body["banks"]))
        check_that("带科目字典", len(body["subjects"]) >= 3, body["subjects"])
        builtin = [b for b in body["banks"] if b.get("builtin")]
        check_that("有内置题库", len(builtin) >= 1, len(builtin))
        check_that(
            "内置题库都有题",
            all(b["count"] > 0 for b in builtin),
            [(b["name"], b["count"]) for b in builtin],
        )

        st, body = req("POST", "/api/banks", {"name": "  ", "subject": "math"}, token=token)
        check("空名建题库 400", 400, st)
        st, body = req("POST", "/api/banks", {"name": "测试题库", "subject": "xx"}, token=token)
        check("未知科目 400", 400, st)
        st, body = req(
            "POST", "/api/banks",
            {"name": "测试题库", "subject": "math", "note": "单测用"}, token=token,
        )
        check("建题库 200", 200, st)
        bank_id = body["id"]

        # 答案不在选项里的题必须拒掉：存进去的话设备上永远答不对，
        # 孩子会被一直卡在这道题上 —— 这是最坏的一种数据错误，比报错难查得多
        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions",
            {"type": "choice", "stem": "1+1=?", "options": ["3", "4", "5", "6"], "answer": "2"},
            token=token,
        )
        check("答案不在选项里 400", 400, st)
        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions",
            {"type": "choice", "stem": "1+1=?", "options": ["2", "3", "4", "5"], "answer": "2"},
            token=token,
        )
        check("加选择题 200", 200, st)
        qid = body["id"]
        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions",
            {"type": "spell", "stem": "补全单词：猫", "tpl": "c_t", "answer": "cat"}, token=token,
        )
        check("加拼写题 200", 200, st)
        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions",
            {"type": "spell", "stem": "没有空位", "tpl": "cat", "answer": "cat"}, token=token,
        )
        check("模板没有空位 400", 400, st)
        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions",
            {"type": "spell", "stem": "长度不符", "tpl": "c__t", "answer": "cat"}, token=token,
        )
        check("模板与答案长度不符 400", 400, st)

        st, body = req("GET", f"/api/banks/{bank_id}/questions", token=token)
        check("题目列表 200", 200, st)
        check("题目数 2", 2, body["total"])

        # 文本导入：三行里有一行是注释，应当只进 2 道
        imp = "3 + 4 = ?|7|8|6|5|7\nS|补全单词：狗|d_g|dog|小狗\n# 这行是注释\n"
        st, body = req("POST", f"/api/banks/{bank_id}/import", {"text": imp, "level": 2}, token=token)
        check("导入 200", 200, st)
        check("导入 2 题", 2, body["imported"])
        st, body = req("POST", f"/api/banks/{bank_id}/import", {"text": "乱七八糟没有分隔符"}, token=token)
        check_that("乱格式导不进去", body.get("imported") == 0, body)

        st, body = req(
            "POST", f"/api/banks/{bank_id}/questions/{qid}",
            {"type": "choice", "stem": "2+2=?", "options": ["3", "4", "5", "6"], "answer": "4"},
            token=token,
        )
        check("改题 200", 200, st)
        st, body = req("POST", f"/api/banks/{bank_id}/questions/{qid}/delete", {}, token=token)
        check("删题 200", 200, st)
        st, body = req("GET", f"/api/banks/{bank_id}/questions", token=token)
        check("删完剩 3 题", 3, body["total"])

        # 老版本 App：必须明确拒绝，不能"静默忽略" —— 家长以为开好了最坏
        st, body = req(
            "POST", f"/api/devices/{dev_id}/command",
            {"type": "quiz_banks", "params": {"ids": [bank_id]}}, token=token,
        )
        check("老 App 下发题库 400", 400, st)
        check_that("提示升级到 2.6.0", "2.6.0" in body.get("detail", ""), body.get("detail"))

        # 配一台支持答题的设备
        st, body = req("POST", "/api/pair-codes", {}, token=token)
        code3 = body["code"]
        st, body = req(
            "POST", "/api/device/pair",
            {"code": code3, "name": "答题平板", "app_version": "2.6.0"},
        )
        check("答题设备配对 200", 200, st)
        q_dev = body["device_id"]
        q_tok = body["token"]

        st, body = req(
            "POST", f"/api/devices/{q_dev}/command",
            {"type": "quiz_banks", "params": {"ids": [bank_id]}}, token=token,
        )
        check("下发题库 200", 200, st)
        st, body = req("GET", f"/api/devices/{q_dev}", token=token)
        check("题库已落库", [bank_id], body["device"]["quiz_banks"])

        st, body = req(
            "POST", f"/api/devices/{q_dev}/command",
            {"type": "config", "params": {
                "quiz_enabled": True, "quiz_interval_minutes": 8, "quiz_penalty_minutes": 3}},
            token=token,
        )
        check("下发答题配置 200", 200, st)
        st, body = req("GET", f"/api/devices/{q_dev}", token=token)
        check("间隔已落库", 8, body["device"]["quiz_interval_minutes"])
        check("罚时已落库", 3, body["device"]["quiz_penalty_minutes"])
        check("开关已落库", True, body["device"]["quiz_enabled"])

        st, body = req("POST", f"/api/devices/{q_dev}/command", {"type": "config", "params": {"use_minutes": 25}}, token=token)
        check("没带 quiz_* 的配置不误伤", 200, st)

        # 设备取题（字段名是短键，省流量）
        st, body = req("POST", "/api/device/quiz", {"limit": 50}, token=q_tok)
        check("取题 200", 200, st)
        check_that("取到题目", len(body["questions"]) >= 3, len(body["questions"]))
        check_that("带题库版本号", len(body["version"]) > 0, body["version"])
        check("取题带间隔", 8, body["interval_minutes"])
        check("取题带罚时", 3, body["penalty_minutes"])
        q0 = body["questions"][0]
        check_that("下发用短键", {"q", "a", "t", "o"}.issubset(set(q0)), sorted(q0))
        check_that(
            "选择题答案一定在选项里",
            all(
                qx["a"] in qx["o"] for qx in body["questions"] if qx["t"] == "choice"
            ),
            [(qx["q"], qx["a"], qx["o"]) for qx in body["questions"] if qx["t"] == "choice"][:3],
        )

        st, body = req("POST", "/api/device/heartbeat", {"app_version": "2.6.0"}, token=q_tok)
        check("答题设备心跳 200", 200, st)
        check("心跳带题库名单", [bank_id], body["quiz_banks"])
        check("心跳里答题可用", True, body["quiz_active"])
        check_that("心跳带题库版本", len(body["quiz_version"]) > 0, body["quiz_version"])

        # 删题库 → 设备上的名单要跟着清，否则设备会拿着一个不存在的 id 去取题
        st, body = req("POST", f"/api/banks/{bank_id}/delete", {}, token=token)
        check("删题库 200", 200, st)
        st, body = req("GET", f"/api/devices/{q_dev}", token=token)
        check("设备名单已清理", [], body["device"]["quiz_banks"])

        # ---------- 重启后数据还在 ----------
        print("[重启持久化]")
        # 用"重启前的实际条数"作基准，别写死数字 ——
        # 以前这里是 check(..., 4, ...)，每次给设备多加一条指令测试就假失败一次
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        cmds_before_restart = len(body["commands"])
        check_that("重启前有指令记录", cmds_before_restart > 0, cmds_before_restart)
        # 设备数也取实际值：这一节前面又配对了一台答题设备，写死 2 会假失败
        st, body = req("GET", "/api/overview", token=token)
        devices_before_restart = body["stats"]["devices_total"]
        proc.terminate()
        proc.wait(timeout=10)
        check_that("数据文件已生成", os.path.isfile(DATA))
        proc = start(binary)
        st, body = req("GET", "/api/overview", token=token)
        check("旧 token 仍有效 200", 200, st)
        check("重启后设备还在", devices_before_restart, body["stats"]["devices_total"])
        st, body = req("GET", f"/api/devices/{dev_id}", token=token)
        check("重启后历史仍在", cmds_before_restart, len(body["commands"]))
        st, body = req("GET", "/api/users", token=token)
        check("重启后账号还在", 1, len(body["users"]))
        check("内置管理员还在", "admin", body["users"][0]["username"])

        # ---------- 改自己的密码 ----------
        print("[改密码]")
        st, body = req(
            "POST", "/api/me/password", {"old_password": "wrong", "new_password": "x123456"}, token=token
        )
        check("旧密码不对 400", 400, st)
        st, body = req(
            "POST", "/api/me/password", {"old_password": PASSWORD, "new_password": "123"}, token=token
        )
        check("新密码太短 400", 400, st)
        st, body = req(
            "POST", "/api/me/password", {"old_password": PASSWORD, "new_password": "new-pass-9"}, token=token
        )
        check("改密码 200", 200, st)
        st, body = req("GET", "/api/overview", token=token)
        check("当前会话仍有效 200", 200, st)
        st, body = req("POST", "/api/login", {"username": "admin", "password": "new-pass-9"})
        check("新密码能登录 200", 200, st)
        token2 = body["token"]
        st, body = req("POST", "/api/login", {"username": "admin", "password": PASSWORD})
        check("旧密码不能登录 401", 401, st)

        # ---------- 登出 ----------
        print("[登出]")
        st, body = req("POST", "/api/logout", {}, token=token2)
        check("登出 200", 200, st)
        st, body = req("GET", "/api/overview", token=token2)
        check("登出后 401", 401, st)

        # ---------- 登录限流（放最后，会锁 IP 5 分钟）----------
        # 换一个伪造的转发 IP，免得把前面正常测试用过的 127.0.0.1 也算进去
        print("[登录限流]")
        XIP = {"X-Forwarded-For": "10.9.9.9"}
        codes = []
        for _ in range(8):
            st, _ = req("POST", "/api/login", {"password": "nope"}, headers=XIP)
            codes.append(st)
        check("前 8 次都是 401", [401] * 8, codes)
        st, body = req("POST", "/api/login", {"password": "nope"}, headers=XIP)
        check("第 9 次 429", 429, st)
        st, body = req("POST", "/api/login", {"username": "admin", "password": "new-pass-9"}, headers=XIP)
        check("锁定期间正确口令也 429", 429, st)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()

    migration_case(binary)

    print()
    print(f"通过 {ok_count} 项，失败 {fail_count} 项")
    if fail_count:
        print("日志：" + os.path.join(TMP, "server.log"))
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
