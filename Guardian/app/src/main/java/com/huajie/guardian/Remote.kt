package com.huajie.guardian

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 远程管理通道（和 GuardianServer 通信）。
 *
 * 协议只有两个动作，都是设备主动发起，服务器**永远不会**主动连设备：
 *
 *   1. `POST /api/device/heartbeat`（每 20 秒）
 *      上行：当前状态快照（是否守护中、剩余时长、今日累计、配置参数…）
 *      下行：`commands: [{ id, type, params }]` —— 家长在后台点的"锁定/解锁/改配置"
 *
 *   2. `POST /api/device/result`
 *      把每条指令的执行结果回执上去，后台详情页能看到成功/失败原因。
 *
 * 为什么用轮询而不是推送（WebSocket / FCM）：国产 ROM 后台限制下，
 * 长连接和推送都可能被静默切断；而"20 秒一次的心跳"本来就要发（后台靠它判断在线），
 * 顺手把指令捎回来最省事，也不依赖任何第三方服务。
 *
 * 线程模型：网络全部在单线程池里串行执行（避免两次心跳搅在一起），
 * 但**指令的执行必须在主线程** —— 它要建悬浮窗、改 SharedPreferences、动音量。
 */
object Remote {

    private const val TAG = "Remote"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "guardian-remote").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 同一时刻只允许一次心跳在飞，避免网络慢时请求堆成一片 */
    @Volatile
    private var busy = false

    // ==================================================================
    // 地址
    // ==================================================================

    /**
     * 规范化家长填的地址：
     *   `192.168.1.10:8000` → `http://192.168.1.10:8000`
     *   `https://abc.com/`  → `https://abc.com`
     * 返回 null 表示填的东西没法用。
     */
    fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        // 去掉误粘进来的路径（例如直接贴了 /api/overview）
        s = s.substringBefore("/api/")
        if (s.length <= 8) return null
        return s
    }

    // ==================================================================
    // 配对
    // ==================================================================

    /**
     * 用 6 位配对码换设备令牌。
     * 回调在主线程（界面直接更新 UI 即可）。
     */
    fun pair(
        ctx: Context,
        rawUrl: String,
        code: String,
        name: String,
        cb: (Boolean, String) -> Unit
    ) {
        val app = ctx.applicationContext
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            main.post { cb(false, "服务器地址不正确，例如 http://1.2.3.4:8000") }
            return
        }
        val c = code.trim()
        if (c.length < 4) {
            main.post { cb(false, "配对码不正确") }
            return
        }

        io.execute {
            val p = Prefs(app)
            val body = JSONObject()
                .put("code", c)
                .put("name", name.trim().ifEmpty { Build.MODEL ?: "安卓平板" })
                .put("model", Build.MODEL ?: "")
                .put("app_version", appVersion(app))

            val res = post("$url/api/device/pair", body.toString(), null)
            if (!res.ok) {
                main.post { cb(false, res.error) }
                return@execute
            }
            val obj = runCatching { JSONObject(res.body) }.getOrNull()
            val token = obj?.optString("token").orEmpty()
            val did = obj?.optString("device_id").orEmpty()
            if (token.isEmpty() || did.isEmpty()) {
                main.post { cb(false, "服务端返回的内容不完整，请检查服务端版本") }
                return@execute
            }

            p.serverUrl = url
            p.deviceToken = token
            p.deviceId = did
            p.remoteName = obj?.optString("name").orEmpty().ifEmpty { name }
            p.remoteLastError = ""
            p.remoteLastOkWall = 0L

            // 配好立刻同步一次：后台马上能看到这台设备，家长不用等 20 秒
            beat(app, p)

            val err = p.remoteLastError
            main.post {
                if (err.isEmpty()) cb(true, "配对成功") else cb(true, "配对成功（首次同步失败：$err）")
            }
        }
    }

    /** 解绑：后台就再也看不到这台设备了 */
    fun unbind(ctx: Context) {
        Prefs(ctx).clearRemote()
    }

    // ==================================================================
    // 心跳
    // ==================================================================

    /** 由守护服务每 20 秒调一次（未配对或上一次还没返回就跳过） */
    fun heartbeat(ctx: Context) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.remoteConfigured()) return
        if (busy) return
        busy = true
        io.execute { beat(app, p) }
    }

    private fun beat(app: Context, p: Prefs) {
        try {
            val r = post(
                p.serverUrl + "/api/device/heartbeat",
                statusJson(app, p),
                p.deviceToken
            )
            if (!r.ok) {
                p.remoteLastError = if (r.code == 401) {
                    "设备令牌已失效（可能后台删除了这台设备），请重新配对"
                } else {
                    r.error
                }
                return
            }

            p.remoteLastOkWall = System.currentTimeMillis()
            p.remoteLastError = ""

            // 顺手上报前台应用片段。失败不影响心跳，也不影响下面的指令处理。
            runCatching { postUsage(app, p) }
                .onFailure { Log.w(TAG, "应用片段上报异常", it) }

            val cmds = runCatching { JSONObject(r.body).optJSONArray("commands") }
                .getOrNull()
            // ---- 停用时段规则：服务端每轮都带一份权威副本 ----
            //
            // 这是"家长设规则时设备离线"和"设备重装/清数据"的自愈通道。
            // 用字符串比较来避免每 20 秒都写一次盘。
            runCatching {
                val o = JSONObject(r.body)
                if (o.has("quiet_hours")) {
                    val incoming = Quiet.toJson(Quiet.parse(o.optJSONArray("quiet_hours")))
                    if (incoming != p.quietRulesRaw) {
                        p.quietRulesRaw = incoming
                        p.quietSkipUntilWall = 0L
                        Log.i(TAG, "停用时段规则更新：${incoming.take(120)}")
                        // 规则变了就立刻重判一次：可能这一秒就该锁上，也可能该放开
                        GuardianService.refresh(app)
                    }
                }
            }.onFailure { Log.w(TAG, "解析停用时段规则失败", it) }

            // ---- 答题解锁（v2.6.0）：配置 + 题库版本 ----
            runCatching { syncQuiz(app, p, r.body) }
                .onFailure { Log.w(TAG, "同步答题配置失败", it) }

            if (cmds == null) return
            if (cmds.length() == 0) return

            val results = applyOnMain(cmds)
            if (results.isNotEmpty()) postResults(p, results)
        } catch (t: Throwable) {
            p.remoteLastError = t.javaClass.simpleName + ": " + (t.message ?: "")
            Log.w(TAG, "心跳失败", t)
        } finally {
            busy = false
        }
    }

    /**
     * 上报前台应用片段 + 亮屏区间 + 当日解锁/开关机（时间线用）。
     *
     * 没授权"使用情况访问"就整段跳过 —— 后台只会看到在线时间，
     * 不会看到应用名，守护功能本身不受影响。
     *
     * ## v2.3 为什么要重写
     *
     * 老实现是"上次上报到现在"的滑动窗口，游标存在 Prefs 里。看起来省事，
     * 但只要有一次心跳没跑（OPPO 速冻、Doze、网络断、进程被杀），游标就停在
     * 旧位置；更糟的是熄屏会把游标清零、亮屏后只补最近 30 秒 ——
     * 中间那段真实使用时间**再也补不回来**。表现就是家长看到的
     * "手机说 WorkBuddy 用了 3 分钟，后台说 1 分钟"。
     *
     * 现在：每次心跳都从**今天 0 点**重扫一遍系统事件，片段起点直接取系统事件
     * 的时间戳，所以扫多少遍结果都一样；服务端按 `(device_id, start)` 幂等覆盖 ——
     * 漏了心跳、进程被杀过、跨过了熄屏，下一次扫描自动补齐。
     */
    private fun postUsage(app: Context, p: Prefs) {
        if (!Usage.hasPermission(app)) return

        val now = System.currentTimeMillis()
        // ★ 一律从"今天 0 点"扫到现在。
        //
        // 试过"平时只补最近 6 分钟、5 分钟做一次整天重扫"来省电，但那个方案有个
        // 致命的坑：亮屏区间的起点会随着窗口滑动而变（`now - 6min` 每 20 秒都不一样），
        // 服务端按 `(device_id, start)` 存，于是每次心跳都在插一条新记录，
        // 亮屏时长会被算成好几倍。
        //
        // 全天扫的代价其实很小：系统一天也就几百到几千条使用事件，读一遍是毫秒级，
        // 而 clip/聚合都是内存操作，跑在 IO 线程里，不占主线程、不影响前台。
        val dayStart = localMidnight(now)

        val snap = Usage.snapshot(app, dayStart, now)

        val body = JSONObject()

        // 片段太多时只发最近的：服务端按 (device_id, start) 覆盖，
        // 早的那些上一轮已经发过了，不会丢。
        val segs = if (snap.segs.size > 400) snap.segs.subList(snap.segs.size - 400, snap.segs.size) else snap.segs
        val arr = JSONArray()
        for (s in segs) {
            arr.put(
                JSONObject()
                    .put("start", s.start / 1000.0)
                    .put("end", s.end / 1000.0)
                    .put("pkg", s.pkg)
                    .put("label", s.label)
            )
        }
        body.put("segments", arr)

        // 亮屏区间：时间轴上那条绿线就是它
        val scrList = if (snap.screen.size > 400) snap.screen.subList(snap.screen.size - 400, snap.screen.size) else snap.screen
        val scr = JSONArray()
        for (s in scrList) {
            scr.put(
                JSONObject()
                    .put("start", s.start / 1000.0)
                    .put("end", s.end / 1000.0)
            )
        }
        body.put("screen", scr)

        // 当天累计值：服务端按取最大值合并，重复上报不会翻倍
        body.put("day", dayKey(now))
        body.put("unlocks", snap.unlocks)
        body.put("boots", snap.startups)
        body.put("shutdowns", snap.shutdowns)
        body.put("screen_ms", snap.screenMs)

        // 系统口径要遍历所有应用的聚合数据，比读事件重一些，5 分钟算一次足够
        if (p.usageFullScanAt <= 0L || now - p.usageFullScanAt > 5 * 60_000L || p.usageFullScanAt < dayStart) {
            body.put("sys_ms", Usage.systemUsageMs(app, dayStart, now))
            p.usageFullScanAt = now
        }

        val r = post(p.serverUrl + "/api/device/usage", body.toString(), p.deviceToken)
        if (!r.ok) {
            Log.w(TAG, "应用片段上报失败：${r.error}")
        }
    }

    /**
     * 答题解锁：同步"开关 / 间隔 / 罚时"，并在题库版本变了时重新取题。
     *
     * ## 为什么"题库为空就不采纳服务端配置"
     *
     * 家长可能只在手机客户端里开了答题锁（没配任何题库，走本地算术题），
     * 这时服务端每次心跳都会说 `quiz_active = false`。如果照单全收，
     * 家长刚在手机上打开的功能会在 20 秒内被网页端"关掉"。
     * 所以约定：**只有服务端为此设备配了题库，才由服务端说了算**。
     */
    private fun syncQuiz(app: Context, p: Prefs, body: String) {
        val o = JSONObject(body)
        if (!o.has("quiz_banks")) return
        val banks = o.optJSONArray("quiz_banks") ?: JSONArray()
        if (banks.length() == 0) return

        val active = o.optBoolean("quiz_active", false)
        val interval = o.optInt("interval_minutes", p.quizIntervalMinutes)
        val penalty = o.optInt("penalty_minutes", p.quizPenaltyMinutes)
        var changed = false
        if (p.quizEnabled != active) {
            p.quizEnabled = active
            changed = true
        }
        if (p.quizIntervalMinutes != interval) {
            p.quizIntervalMinutes = interval
            changed = true
        }
        if (p.quizPenaltyMinutes != penalty) {
            p.quizPenaltyMinutes = penalty
            changed = true
        }

        val version = o.optString("quiz_version")
        if (active && version.isNotEmpty() && version != p.quizCacheVersion) {
            fetchQuiz(p, version)
        }
        if (changed) {
            Log.i(TAG, "答题配置更新：enabled=$active interval=$interval penalty=$penalty")
            GuardianService.refresh(app)
        }
    }

    /** 拉一次题目（题库很小，一次拿够，之后按版本号判断要不要重下） */
    private fun fetchQuiz(p: Prefs, version: String) {
        val body = JSONObject().put("limit", QuizBank.FETCH_LIMIT)
        val r = post(p.serverUrl + "/api/device/quiz", body.toString(), p.deviceToken)
        if (!r.ok) {
            Log.w(TAG, "取题失败：${r.error}")
            return
        }
        val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return
        val arr = o.optJSONArray("questions") ?: return
        val list = ArrayList<Q>(arr.length())
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            // 服务端会剔除结构不完整的题；这里再挡一次，坏数据不会进缓存
            Q.fromJson(q)?.let { list.add(it) }
        }
        if (list.isEmpty()) {
            Log.w(TAG, "取回来的题全都不可用，保留旧缓存")
            return
        }
        QuizBank.saveCache(p, o.optString("version", version), list)
        p.quizCacheAtWall = System.currentTimeMillis()
        Log.i(TAG, "题库已更新：${list.size} 题（版本 $version）")
    }

    /** 本地时区当天 0 点（毫秒） */
    private fun localMidnight(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 设备本地的日期字符串 yyyy-MM-dd（计数按设备自己的自然日归集） */
    private fun dayKey(now: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now))

    /** 上报给服务端的状态快照 */    private fun statusJson(app: Context, p: Prefs): String {
        val now = System.currentTimeMillis()
        val running = if (p.sessionStartWall > 0L && now > p.sessionStartWall) {
            now - p.sessionStartWall
        } else {
            0L
        }
        val restLeft = (p.lockedUntilWall - now).coerceAtLeast(0L)
        val interactive = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(false)

        return JSONObject()
            .put("app_version", appVersion(app))
            .put("enabled", p.enabled)
            .put("resting", restLeft > 0L)
            .put("rest_left_ms", restLeft)
            // 服务在跑的时候 sUsedMs 是"从系统事件重算"的权威值；
            // 服务没跑（比如刚配对、界面里点"立即同步"）就退回 Prefs 里的口径
            .put("used_ms", maxOf(p.sessionUsedMs + running, GuardianService.sUsedMs))
            .put("today_used_ms", maxOf(p.todayUsedMs, GuardianService.sTodayUsed))
            // 每日总时长（v2.5.0）：上限值以设备为准上报，后台改配置时再下发回来。
            // day_locked 决定设备卡上显示"今日额度已用完"还是正常状态。
            .put("day_limit_minutes", p.dayLimitMinutes)
            .put("day_locked", GuardianService.sDayLocked)
            .put(
                "day_unlock_left_ms",
                (p.dayUnlockUntilWall - now).coerceAtLeast(0L)
            )
            .put("rest_count", p.restCount)
            // 答题解锁（v2.6.0）：后台据此在设备卡上显示"答题解锁中"，
            // 也顺便让家长看见设备当前的实际参数（可能和网页上配的不一样，
            // 比如题库还没下发、功能只在客户端开过）
            .put("quiz_enabled", p.quizEnabled)
            .put("quiz_locked", QuizLock.active)
            .put("quiz_interval_minutes", p.quizIntervalMinutes)
            .put("quiz_penalty_minutes", p.quizPenaltyMinutes)
            .put("quiz_cache_count", runCatching { QuizBank.cached(p).size }.getOrDefault(0))
            .put("use_minutes", p.useMinutes)
            .put("rest_minutes", p.restMinutes)
            .put("reset_gap_seconds", p.resetGapSeconds)
            .put("tamper_policy", p.tamperPolicy == Prefs.TAMPER_REST)
            // 违规次数要让后台看到：它决定惩罚休息被放大几倍。
            // 不报上去的话，家长在网页上只能看到"设 5 分钟却休 15 分钟"，
            // 根本查不出原因。
            .put("tamper_strikes", p.tamperStrikes)
            .put("pin_set", p.pin.isNotEmpty())
            .put("screen_on", interactive)
            // 停用时段状态：后台据此在设备卡上显示"停用时段 · 还锁 2 小时"，
            // 并在事件流里记下"什么时候进的、什么时候放开的"。
            .put("in_quiet", GuardianService.sInQuiet)
            .put("quiet_left_ms", if (GuardianService.sInQuiet) GuardianService.sQuietLeftMs else 0L)
            .put("quiet_label", if (GuardianService.sInQuiet) GuardianService.sQuietLabel else "")
            .put("audio_suppressed", Silence.isSuppressing)
            .put("pause_media_on_rest", p.pauseMediaOnRest)
            .put("mute_on_rest", p.muteOnRest)
            .toString()
    }

    // ==================================================================
    // 指令执行
    // ==================================================================

    /**
     * 指令必须在主线程执行（要建悬浮窗、写 Prefs、动音量），
     * 所以这里 post 到主线程并**同步等它做完**再返回，回执才不会漏。
     */
    private fun applyOnMain(
        arr: JSONArray
    ): List<Pair<Long, Pair<Boolean, String>>> {
        val out = ArrayList<Pair<Long, Pair<Boolean, String>>>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val id = c.optLong("id", 0L)
                    val type = c.optString("type")
                    val params = c.optJSONObject("params") ?: JSONObject()
                    val r = GuardianService.applyRemote(type, params)
                    out.add(id to r)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "执行远程指令失败", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return out
    }

    private fun postResults(p: Prefs, results: List<Pair<Long, Pair<Boolean, String>>>) {
        for ((id, r) in results) {
            val body = JSONObject()
                .put("command_id", id)
                .put("ok", r.first)
                .put("message", r.second.take(300))
            val res = post(p.serverUrl + "/api/device/result", body.toString(), p.deviceToken)
            if (!res.ok) Log.w(TAG, "回执上报失败（指令 $id）：${res.error}")
        }
    }

    // ==================================================================
    // HTTP
    // ==================================================================

    private class Resp(val code: Int, val body: String, val error: String) {
        val ok: Boolean get() = code in 200..299 && error.isEmpty()
    }

    private fun post(urlStr: String, body: String, token: String?): Resp {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrEmpty()) {
                c.setRequestProperty("Authorization", "Bearer $token")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code in 200..299) {
                Resp(code, text, "")
            } else {
                Resp(code, text, detailOf(text, code))
            }
        } catch (t: Throwable) {
            // 网络不通是最常见的情况（家长改了地址、平板没 WiFi、服务端没开），
            // 给一句人话，别把 UnknownHostException 直接甩到界面上
            val msg = when (t) {
                is java.net.UnknownHostException -> "找不到服务器，请检查地址是否正确"
                is java.net.SocketTimeoutException -> "连接服务器超时"
                is java.net.ConnectException -> "无法连接服务器（服务端没启动或端口不对）"
                else -> t.javaClass.simpleName + ": " + (t.message ?: "")
            }
            Resp(0, "", msg)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 服务端错误统一是 {"detail": "..."}，取出来给人看 */
    private fun detailOf(text: String, code: Int): String {
        val d = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
        return d.ifEmpty { "服务端返回 $code" }
    }

    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    }.getOrDefault("")
}

// v2.3.0：采集窗口改为"整天重扫"，见 postUsage 注释
