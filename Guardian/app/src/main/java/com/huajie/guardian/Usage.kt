package com.huajie.guardian

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台应用采集 —— 让家长在后台时间线上看到"几点几分在用什么"。
 *
 * ## 用什么 API
 *
 * `UsageStatsManager.queryEvents()`。它由系统记录，**不依赖我们的进程活着**：
 * 就算守护被冻结、被杀，只要之后还能跑一次，这段时间的切换事件照样能补回来。
 * 这一点很重要 —— 比"自己监听 Activity 切换"可靠得多。
 *
 * ## 需要权限
 *
 * `PACKAGE_USAGE_STATS`（设置 → 特殊应用权限 → 使用情况访问）。
 * 这是**可选**权限：没授权时 [hasPermission] 返回 false，上报整段跳过，
 * 后台只会看到在线时间，不会看到应用名。守护本身的功能完全不受影响。
 *
 * ## 只记"切换点"
 *
 * 系统事件是一串"某时刻某应用进入前台"。相邻同包要合并（Activity 之间跳转会连报），
 * 过滤掉系统 UI / 锁屏这类噪声，最后生成 `[start, end)` 片段。
 */
object Usage {

    private const val TAG = "GuardianUsage"

    /** 回溯"最后一次前台应用"时最多往前看多久 */
    private const val MAX_LOOKBACK_MS = 12 * 3600_000L

    /** 一个前台应用片段（毫秒） */
    data class Seg(val start: Long, val end: Long, val pkg: String, val label: String)

    /** 一个时间区间（毫秒），亮屏区间用它 */
    data class Span(val start: Long, val end: Long)

    /**
     * 一次采集的结果。
     *
     * ★ v2.3 起这里**不再有"上一段起点"**。
     *
     * 老版本把"此刻正在用的那一段"的起点存到 Prefs、下次原样传回来，靠这个让
     * 服务端按 `(start, pkg)` 幂等覆盖。问题是一旦某次心跳没跑（OPPO 速冻、Doze、
     * 网络断了、熄屏清了游标），起点就断了，那段真实使用时间**永久丢失** ——
     * 家长看到的"WorkBuddy 只用了 1 分钟、手机说 3 分钟"就是这么来的。
     *
     * 现在改成：每次采集都从"今天 0 点"重新扫一遍系统事件。系统事件是系统自己
     * 记的，不会因为我们进程死了而消失；同一段时间扫多少遍结果都一样（起点就是
     * 切换事件的时间戳），所以既幂等又自愈 —— 漏了几次心跳，下次扫描自动补齐。
     */
    data class Snapshot(
        val segs: List<Seg>,
        /** 亮屏区间；每个元素都是"屏幕亮着"的一段 */
        val screen: List<Span>,
        /** 当天解锁次数（KEYGUARD_HIDDEN 事件数） */
        val unlocks: Int,
        /** 当天开机次数（DEVICE_STARTUP 事件数） */
        val startups: Int,
        /** 当天关机次数（DEVICE_SHUTDOWN 事件数） */
        val shutdowns: Int,
        /** 亮屏总时长（毫秒），等于 [screen] 各段之和 */
        val screenMs: Long,
    )

    // ------------------------------------------------------------------
    // UsageEvents 的事件类型
    //
    // 这些在 `UsageEvents.Event` 里都有同名常量，但 SCREEN_INTERACTIVE 之后的
    // 那批是 API 28 才加的，而 minSdk 是 26 —— 低版本上引用常量名会在类加载时
    // 直接 NoSuchFieldError。所以这里用数值 + 注释，全版本安全。
    // ------------------------------------------------------------------
    private const val EV_FOREGROUND = 1        // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
    private const val EV_SCREEN_ON = 15        // SCREEN_INTERACTIVE
    private const val EV_SCREEN_OFF = 16       // SCREEN_NON_INTERACTIVE
    private const val EV_KEYGUARD_HIDDEN = 18  // 解锁（锁屏消失）
    private const val EV_DEVICE_SHUTDOWN = 26  // 关机
    private const val EV_DEVICE_STARTUP = 27   // 开机

    /** 查过就缓存：同一个包名不必每次都走 PackageManager（还要跨进程） */
    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * 上一次采集时"正在前台"的包名。
     *
     * 只用来兜一个边角情况：窗口里一条切换事件都没有（一直没换过应用），
     * 但系统事件里也查不到更早的记录时，用它接上。
     */
    @Volatile
    private var lastKnownPkg: String? = null

    /** 这些包不算"孩子在用什么"，直接丢掉 */
    private val NOISE = setOf(
        "android",
        "com.android.systemui",
        "com.android.keyguard",
        "com.android.internal.systemui.navbar.gestural",
    )

    // ==================================================================
    // 权限
    // ==================================================================

    /**
     * 是否已授予"使用情况访问"。
     *
     * 走 AppOps 而不是 checkSelfPermission —— 这个权限是特殊权限，
     * 授没授只能通过 AppOps 查。部分 ROM 上 AppOps 会返回 DEFAULT，
     * 这时再用 `queryUsageStats` 是否拿得到数据兜一次底。
     */
    fun hasPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ctx.packageName
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)

        if (mode == AppOpsManager.MODE_ALLOWED) return true
        if (mode != AppOpsManager.MODE_DEFAULT) return false

        // MODE_DEFAULT：这个 ROM 没给明确答案，用真实数据探一下
        return runCatching {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return false
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /** 跳到系统的"使用情况访问"授权页 */
    fun openSettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    // ==================================================================
    // 采集
    // ==================================================================

    /**
     * 扫一遍 `[fromMs, toMs)` 的系统事件，一次性拿到：前台应用片段、亮屏区间、
     * 解锁次数、开关机次数。
     *
     * **无状态**：不依赖任何"上次报到哪里"的记忆，同一个区间扫多少遍结果都一样。
     * 调用方按"今天 0 点 → 现在"传窗口，就能得到当天的全量结果；漏了心跳、进程
     * 被杀、跨过了熄屏，下一次扫描都会自动补齐。
     *
     * 只查一次 `queryEvents`：一天的事件量在几千条量级，读一遍是毫秒级开销，
     * 而它跑在 Remote 的单线程 IO 池里，不占主线程。
     */
    fun snapshot(ctx: Context, fromMs: Long, toMs: Long): Snapshot {
        if (toMs <= fromMs) return Snapshot(emptyList(), emptyList(), 0, 0, 0, 0)
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
            ?: return Snapshot(emptyList(), emptyList(), 0, 0, 0, 0)

        val home = homePackage(ctx)
        val self = ctx.packageName

        val switches = ArrayList<Pair<Long, String>>()
        // 亮屏区间：记录"开"的时刻，遇到"关"就落一段
        val screenRaw = ArrayList<Span>()
        var openAt = -1L
        var unlocks = 0
        var startups = 0
        var shutdowns = 0
        var lastTs = fromMs

        try {
            val events = usm.queryEvents(fromMs, toMs)
            if (events != null) {
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    val ts = e.timeStamp
                    if (ts <= 0L) continue
                    lastTs = ts
                    when (e.eventType) {
                        EV_FOREGROUND -> {
                            val pkg = e.packageName ?: continue
                            if (pkg.isEmpty() || pkg == self || NOISE.contains(pkg)) continue
                            switches.add(ts to pkg)
                        }
                        EV_SCREEN_ON -> if (openAt < 0L) openAt = ts
                        EV_SCREEN_OFF -> {
                            if (openAt >= 0L && ts > openAt) screenRaw.add(Span(openAt, ts))
                            openAt = -1L
                        }
                        EV_KEYGUARD_HIDDEN -> unlocks++
                        EV_DEVICE_STARTUP -> startups++
                        EV_DEVICE_SHUTDOWN -> shutdowns++
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryEvents 失败", t)
        }

        // ---- 屏幕状态收尾 ----
        //
        // 窗口结束时屏幕可能还亮着（还没等到 SCREEN_OFF），补到 now；
        // 反之如果此刻已经灭了却没见到 OFF 事件，只能收在最后一条事件上，
        // 宁可少算也不要把熄屏时间算成亮屏。
        val onNow = isInteractive(ctx)
        if (openAt >= 0L) {
            val end = if (onNow) toMs else maxOf(openAt, minOf(toMs, lastTs))
            if (end > openAt) screenRaw.add(Span(openAt, end))
        } else if (screenRaw.isEmpty() && onNow) {
            // 整段窗口都没见到亮灭事件，但屏幕此刻是亮的：按整段算
            screenRaw.add(Span(fromMs, toMs))
        }

        // ---- 前台应用片段 ----
        val segs = ArrayList<Seg>()
        if (switches.isNotEmpty()) {
            var curStart = maxOf(switches[0].first, fromMs)
            var curPkg = switches[0].second
            for (i in 1 until switches.size) {
                val (ts, pkg) = switches[i]
                if (pkg == curPkg) continue      // Activity 之间跳转，包名没变
                if (ts <= curStart) continue     // 时间戳重复/倒挂
                segs.add(Seg(curStart, ts, curPkg, labelOf(ctx, curPkg, home)))
                curStart = ts
                curPkg = pkg
            }
            if (toMs > curStart) segs.add(Seg(curStart, toMs, curPkg, labelOf(ctx, curPkg, home)))
            lastKnownPkg = curPkg
        } else {
            // 窗口里一次切换都没有：接上一次的包名，或者回溯找一次
            val pkg = lastKnownPkg
                ?: lastForeground(ctx, fromMs - MAX_LOOKBACK_MS, toMs)
            if (pkg != null && pkg != self && !NOISE.contains(pkg)) {
                segs.add(Seg(fromMs, toMs, pkg, labelOf(ctx, pkg, home)))
                lastKnownPkg = pkg
            }
        }

        // ---- 用亮屏区间裁剪 ----
        //
        // ★ 这一步替代了老版本的"熄屏就清游标"。老做法有两个毛病：
        //   1. 熄屏那一刻到上次心跳之间的时间直接丢；
        //   2. 亮屏后只从最近 30 秒起算，中间断掉的一截再也补不回来。
        // 现在的做法是照常采集、再用"屏幕亮着的那些区间"去切 —— 熄屏期间
        // 系统并不会把应用踢到后台，最后那个应用会一直挂着，不切就会把整夜
        // 都算成"在用 B 站"。
        val clipped = if (screenRaw.isEmpty()) segs else clipToScreen(segs, screenRaw)

        val screen = mergeSpans(screenRaw)
        val screenMs = screen.sumOf { it.end - it.start }
        return Snapshot(
            segs = clipped.filter { it.end - it.start >= 2_000L },
            screen = screen,
            unlocks = unlocks,
            startups = startups,
            shutdowns = shutdowns,
            screenMs = screenMs,
        )
    }

    /**
     * 把应用片段裁到"屏幕亮着"的区间里。
     *
     * 一个片段可能横跨熄屏，所以要**切开**而不是简单截断：
     * `[亮屏 10:00][熄屏 10:20][亮屏 10:40]` 里有一段 `[09:50, 11:00]` 的应用，
     * 应该变成 `[10:00,10:20]` 和 `[10:40,11:00]` 两段，中间那 20 分钟不属于它。
     */
    private fun clipToScreen(segs: List<Seg>, screen: List<Span>): List<Seg> {
        if (screen.isEmpty()) return segs
        val out = ArrayList<Seg>(segs.size)
        for (s in segs) {
            for (w in screen) {
                val a = maxOf(s.start, w.start)
                val b = minOf(s.end, w.end)
                if (b - a >= 1_000L) {
                    out.add(Seg(a, b, s.pkg, s.label))
                }
            }
        }
        out.sortBy { it.start }
        return out
    }

    /** 合并重叠/相接的区间（事件偶尔会重复报） */
    private fun mergeSpans(spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.start }
        val out = ArrayList<Span>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val s = sorted[i]
            if (s.start <= cur.end) {
                if (s.end > cur.end) cur = Span(cur.start, s.end)
            } else {
                out.add(cur)
                cur = s
            }
        }
        out.add(cur)
        return out
    }

    /**
     * 从片段里算"真正使用了多久"（毫秒）。
     *
     * ## 为什么不能再用"每秒累加"
     *
     * 老实现是 `step()` 里每秒 `todayBufferMs += dt`，并且 `dt > 5s` 就整段丢弃。
     * 这在国产 ROM 上必然失真：ColorOS 的「应用速冻」会把进程冻住几分钟，
     * 解冻后第一个 tick 的 dt 是几百秒，按规则直接丢掉 —— 孩子在玩，
     * 后台却说没玩。进程被杀重启后更彻底，那段直接不存在。
     *
     * 现在的口径：拿系统事件算。事件是系统记的，进程死多少次都在，
     * 同一段时间算多少遍结果都一样，所以既幂等又自愈。
     *
     * @param segs 前台片段（[snapshot] 已经裁到亮屏区间、且排除了系统 UI 和自己）
     * @param excludes 不计入的区间（强制休息 / 停用时段），元素是 `[start, end]`
     * @param gapMs 相邻片段间隔超过它就"重新开一轮"（[Prefs.resetGapSeconds]）；
     *              传 `Long.MAX_VALUE` 表示这是"全天累计"，不重置
     */
    fun usedMs(
        segs: List<Seg>,
        from: Long,
        to: Long,
        excludes: List<LongArray>,
        gapMs: Long = Long.MAX_VALUE,
    ): Long {
        if (to <= from) return 0L
        val raw = segs.mapNotNull {
            val a = maxOf(it.start, from)
            val b = minOf(it.end, to)
            if (b - a >= 1_000L) Span(a, b) else null
        }
        if (raw.isEmpty()) return 0L

        // 先合并：片段偶尔会重叠（Activity 快速切换、事件重复），
        // 不合并会把同一段时间算两遍，直接算出一个"用了 3 小时"的假数字。
        val merged = mergeSpans(raw)
        var total = 0L
        var cursor = -1L
        for (w in merged) {
            if (gapMs != Long.MAX_VALUE && cursor >= 0 && w.start - cursor > gapMs) {
                total = 0L   // 中间空闲太久，本轮从这一段的起点重新开始
            }
            var piece = w.end - w.start
            for (x in excludes) {
                val a = maxOf(w.start, x[0])
                val b = minOf(w.end, x[1])
                if (b > a) piece -= (b - a)
            }
            if (piece > 0L) total += piece
            cursor = w.end
        }
        return total.coerceAtLeast(0L)
    }

    /** 本地时区当天 0 点 */
    fun localMidnight(now: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 系统口径的当日前台总时长（毫秒）。
     *
     * 用 `queryAndAggregateUsageStats` —— 和手机自带"数字健康"是同一个 API、
     * 同一份数据。我们自己的片段是"从系统事件推出来的"，多少会受事件缺失影响；
     * 这个数字可以直接拿来对照，后台设备页会显示"系统口径 X / 采集口径 Y"，
     * 差得多就说明采集还有漏。
     */
    fun systemUsageMs(ctx: Context, fromMs: Long, toMs: Long): Long = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return 0L
        val map = usm.queryAndAggregateUsageStats(fromMs, toMs) ?: return 0L
        map.values
            .filter { it.packageName != ctx.packageName && !NOISE.contains(it.packageName) }
            .sumOf { it.totalTimeInForeground }
    }.getOrDefault(0L)

    /** 屏幕此刻是否亮着（含"亮着但在锁屏"） */
    fun isInteractive(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(false)

    /**
     * 回溯找"最后一次进入前台的包"。
     *
     * 只在"窗口内没有任何切换、且进程里也没记住上一次"的时候跑一次，
     * 所以扫大窗口不会成为常态开销。
     */
    private fun lastForeground(ctx: Context, fromMs: Long, toMs: Long): String? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        var lastPkg: String? = null
        var lastTs = 0L
        runCatching {
            val events = usm.queryEvents(fromMs.coerceAtLeast(0L), toMs) ?: return null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val pkg = e.packageName ?: continue
                if (pkg.isEmpty() || NOISE.contains(pkg) || pkg == ctx.packageName) continue
                if (e.timeStamp >= lastTs) {
                    lastTs = e.timeStamp
                    lastPkg = pkg
                }
            }
        }.onFailure { Log.w(TAG, "回溯前台应用失败", it) }
        return lastPkg
    }

    /**
     * 读一个可读的应用名。
     *
     * Android 11+ 的包可见性会挡住 `getApplicationInfo`，所以清单里声明了
     * `QUERY_ALL_PACKAGES`（侧载应用不受商店政策限制）。
     * 查不到就退回包名 —— 显示包名总比显示"未知应用"有用。
     */
    private fun labelOf(ctx: Context, pkg: String, home: String?): String {
        labelCache[pkg]?.let { return it }
        if (pkg == home) {
            labelCache[pkg] = "桌面"
            return "桌面"
        }
        val name = runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrElse {
            pkg.substringAfterLast('.')
        }
        labelCache[pkg] = name
        return name
    }

    /** 桌面（Launcher）的包名，用来把"桌面"和其它应用区分开 */
    private fun homePackage(ctx: Context): String? = runCatching {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
    }.getOrNull()

    /** 清掉名称缓存（换了语言或装了新应用时用得到） */
    fun clearCache() = labelCache.clear()
}
