package com.huajie.guardian

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置 + 运行期状态统一放这里，用 SharedPreferences 持久化。
 * 好处：进程被系统杀掉（华为后台管控很凶）重启后，计时不会归零。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val e = sp.edit()
        block(e)
        e.apply()
    }

    // ==================== 家长配置 ====================

    /** 总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(v) = edit { it.putBoolean(K_ENABLED, v) }

    /** 连续使用多少分钟后强制休息 */
    var useMinutes: Int
        get() = sp.getInt(K_USE_MIN, 30)
        set(v) = edit { it.putInt(K_USE_MIN, v) }

    /** 每次强制休息多少分钟 */
    var restMinutes: Int
        get() = sp.getInt(K_REST_MIN, 5)
        set(v) = edit { it.putInt(K_REST_MIN, v) }

    /**
     * 每天允许使用的总分钟数（0 = 不限制）。
     *
     * 和 [useMinutes] 是两个维度：[useMinutes] 管"连续用多久就歇一会儿"，
     * 这个管"一天总共只能用多久"。用完当天不再自动解除，要等第二天 0 点，
     * 或者由家长临时放行。
     */
    var dayLimitMinutes: Int
        get() = sp.getInt(K_DAY_LIMIT_MIN, 0)
        set(v) = edit { it.putInt(K_DAY_LIMIT_MIN, v.coerceIn(0, 1440)) }

    /** 家长在后台点过「今日解除」的日期（yyyy-MM-dd）；当天内不再按每日总量锁 */
    var dayClearedDay: String
        get() = sp.getString(K_DAY_CLEARED_DAY, "") ?: ""
        set(v) = edit { it.putString(K_DAY_CLEARED_DAY, v) }

    /** 家长用密码临时放行到什么时候（墙钟）；到时立刻恢复每日总量判定 */
    var dayUnlockUntilWall: Long
        get() = sp.getLong(K_DAY_UNLOCK_UNTIL, 0L)
        set(v) = edit { it.putLong(K_DAY_UNLOCK_UNTIL, v) }

    /** 本轮每日额度锁定已经提示过的日期，避免每秒重复弹 toast */
    var dayLockNoticeDay: String
        get() = sp.getString(K_DAY_LOCK_NOTICE_DAY, "") ?: ""
        set(v) = edit { it.putString(K_DAY_LOCK_NOTICE_DAY, v) }

    /** 清掉今日的一切放行状态（跨天 / 家长恢复限制时用） */
    fun clearDayUnlock() {
        edit {
            it.putString(K_DAY_CLEARED_DAY, "")
            it.putLong(K_DAY_UNLOCK_UNTIL, 0L)
        }
    }

    /** 熄屏 / 退出使用超过多少秒后，连续计时重新归零 */
    var resetGapSeconds: Int
        get() = sp.getInt(K_RESET_GAP, 300)
        set(v) = edit { it.putInt(K_RESET_GAP, v) }

    /** 家长密码，空串表示没设密码（此时遮罩上不显示"家长操作"入口） */
    var pin: String
        get() = sp.getString(K_PIN, "") ?: ""
        set(v) = edit {
            it.putString(K_PIN, v)
            // 换密码的同时清掉错误计数与冷却，免得家长被自己之前输错的记录挡在外面
            it.putInt(K_PIN_FAILS, 0)
            it.putLong(K_PIN_LOCKED_UNTIL, 0L)
        }

    /** 连续输错密码的次数（用于限流，防止孩子一个个试过去） */
    var pinFails: Int
        get() = sp.getInt(K_PIN_FAILS, 0)
        set(v) = edit { it.putInt(K_PIN_FAILS, v) }

    /** 输错太多次后的冷却截止时刻（墙钟）；0 表示不用冷却 */
    var pinLockedUntilWall: Long
        get() = sp.getLong(K_PIN_LOCKED_UNTIL, 0L)
        set(v) = edit { it.putLong(K_PIN_LOCKED_UNTIL, v) }

    /**
     * 孩子强行结束进程后的处理策略：
     * [TAMPER_OFF] 只记录，不惩罚
     * [TAMPER_REST] 立刻触发一次强制休息作为惩罚
     */
    var tamperPolicy: Int
        get() = sp.getInt(K_TAMPER_POLICY, TAMPER_OFF)
        set(v) = edit { it.putInt(K_TAMPER_POLICY, v) }

    // ==================== 休息时的静音策略 ====================

    /**
     * 休息期间压制后台播放（申请音频焦点，逼正在放视频的应用暂停）。
     * 默认开启：这是唯一"真暂停"的手段，而且不需要任何权限。
     */
    var pauseMediaOnRest: Boolean
        get() = sp.getBoolean(K_PAUSE_MEDIA, true)
        set(v) = edit { it.putBoolean(K_PAUSE_MEDIA, v) }

    /**
     * 兜底：休息期间把媒体音量压到 0。默认关闭。
     *
     * 只有在前两层（音频焦点 / 媒体会话暂停）都不管用的机器上才需要打开，
     * 因为它是系统级操作，异常退出时有可能把音量留在 0。
     */
    var muteOnRest: Boolean
        get() = sp.getBoolean(K_MUTE_ON_REST, false)
        set(v) = edit { it.putBoolean(K_MUTE_ON_REST, v) }

    /** 被我们静音之前的媒体音量。-1 表示当前没有由我们造成的静音 */
    var mutedSavedVolume: Int
        get() = sp.getInt(K_MUTED_SAVED_VOLUME, -1)
        set(v) = edit { it.putInt(K_MUTED_SAVED_VOLUME, v) }

    /**
     * 当前媒体音量是不是被我们压下去的。
     *
     * 必须**同步**落盘，而且要和 [mutedSavedVolume] 一起写：
     * 进程若在"静音中"被强行结束，下次启动要靠这个标记把音量恢复回来，
     * 否则设备会一直是静音，家长只会觉得"平板坏了"。
     */
    var mutedByUs: Boolean
        get() = sp.getBoolean(K_MUTED_BY_US, false)
        set(v) {
            sp.edit().putBoolean(K_MUTED_BY_US, v).commit()
        }

    /** 记录"我们把它静音了"，同时把原音量一起同步落盘（两件事不能分两次写） */
    fun commitMute(savedVolume: Int) {
        sp.edit()
            .putInt(K_MUTED_SAVED_VOLUME, savedVolume)
            .putBoolean(K_MUTED_BY_US, true)
            .commit()
    }

    /** 清除静音记录（音量本身由调用方恢复） */
    fun clearMute() {
        sp.edit()
            .putInt(K_MUTED_SAVED_VOLUME, -1)
            .putBoolean(K_MUTED_BY_US, false)
            .commit()
    }

    // ==================== 运行期状态 ====================

    /** 本轮连续使用开始的时间戳；0 表示当前没在计时 */
    var sessionStartWall: Long
        get() = sp.getLong(K_SESSION_START, 0L)
        set(v) = edit { it.putLong(K_SESSION_START, v) }

    /** 本轮已累计的使用毫秒数 */
    var sessionUsedMs: Long
        get() = sp.getLong(K_SESSION_USED, 0L)
        set(v) = edit { it.putLong(K_SESSION_USED, v) }

    /** 最近一次"离开使用状态"的时间戳 */
    var lastIdleWall: Long
        get() = sp.getLong(K_LAST_IDLE, 0L)
        set(v) = edit { it.putLong(K_LAST_IDLE, v) }

    /**
     * 本轮计时的起点（墙钟毫秒）。
     *
     * ★ 为什么不用 [sessionStartWall]：那个字段的语义是"正在使用的这一小段"的起点，
     * 一熄屏就被 `onUseEnd` 归零。本轮起点的语义是"这一轮从什么时候重新开始算"，
     * 只有三种事会让它前移：休息结束、停用结束、空闲超过阈值。
     *
     * 两者混用会出大事：进程被杀重启时 `sessionStartWall` 是残留值，
     * 早先的代码直接把它覆盖成"此刻"，于是被杀前那段真实使用时间永久丢失 ——
     * 孩子杀一次后台就能白玩一小时。现在本轮时长一律由系统事件重算，
     * 只需要一个可靠的起点，就是它。
     */
    var roundStartWall: Long
        get() = sp.getLong(K_ROUND_START, 0L)
        set(v) = edit { it.putLong(K_ROUND_START, v) }

    /** 强制休息的截止时间戳；大于当前时间说明正在休息 */
    var lockedUntilWall: Long
        get() = sp.getLong(K_LOCKED_UNTIL, 0L)
        set(v) = edit { it.putLong(K_LOCKED_UNTIL, v) }

    /** 当天已强制休息次数 */
    var restCount: Int
        get() = sp.getInt(K_REST_COUNT, 0)
        set(v) = edit { it.putInt(K_REST_COUNT, v) }

    /** 次数统计对应的日期（yyyy-MM-dd），跨天自动清零 */
    var restDay: String
        get() = sp.getString(K_REST_DAY, "") ?: ""
        set(v) = edit { it.putString(K_REST_DAY, v) }

    // ==================== 远程管理（服务端） ====================

    /**
     * 服务器地址，已规范化（带 http/https 前缀、无尾斜杠）。
     * 空串表示没配过远程管理。
     */
    var serverUrl: String
        get() = sp.getString(K_SERVER_URL, "") ?: ""
        set(v) = edit { it.putString(K_SERVER_URL, v) }

    /** 配对后服务端分配的设备 id（dev_xxxxxx） */
    var deviceId: String
        get() = sp.getString(K_DEVICE_ID, "") ?: ""
        set(v) = edit { it.putString(K_DEVICE_ID, v) }

    /**
     * 设备令牌。等同于这台平板的"密码"，**只在配对时下发一次**。
     * 落了盘就等于这台设备长期可信，所以不能出现在任何界面上。
     */
    var deviceToken: String
        get() = sp.getString(K_DEVICE_TOKEN, "") ?: ""
        set(v) = edit { it.putString(K_DEVICE_TOKEN, v) }

    /** 配对时填的设备名，显示在后台 */
    var remoteName: String
        get() = sp.getString(K_REMOTE_NAME, "") ?: ""
        set(v) = edit { it.putString(K_REMOTE_NAME, v) }

    /** 最近一次心跳成功的时间 */
    var remoteLastOkWall: Long
        get() = sp.getLong(K_REMOTE_LAST_OK, 0L)
        set(v) = edit { it.putLong(K_REMOTE_LAST_OK, v) }

    /** 最近一次心跳失败的原因（网络不通 / 令牌失效 / 服务端 500…） */
    var remoteLastError: String
        get() = sp.getString(K_REMOTE_LAST_ERROR, "") ?: ""
        set(v) {
            // 用块体：edit{} 返回 Unit，表达式体会编不过
            sp.edit().putString(K_REMOTE_LAST_ERROR, v).putLong(K_REMOTE_LAST_ERROR_WALL, System.currentTimeMillis()).apply()
        }

    var remoteLastErrorWall: Long
        get() = sp.getLong(K_REMOTE_LAST_ERROR_WALL, 0L)
        set(v) = edit { it.putLong(K_REMOTE_LAST_ERROR_WALL, v) }

    /** 是否已配对（地址和令牌都在才算） */
    fun remoteConfigured(): Boolean = serverUrl.isNotBlank() && deviceToken.isNotBlank()

    /** 解绑：清掉地址与令牌，后台就再也看不到这台设备 */
    fun clearRemote() {
        sp.edit()
            .remove(K_SERVER_URL)
            .remove(K_DEVICE_ID)
            .remove(K_DEVICE_TOKEN)
            .remove(K_REMOTE_NAME)
            .remove(K_REMOTE_LAST_OK)
            .remove(K_REMOTE_LAST_ERROR)
            .remove(K_REMOTE_LAST_ERROR_WALL)
            // ★ 停用时段必须一起清掉。
            //
            // 规则只有后台能改，解绑之后就再没有任何渠道能改它了 ——
            // 留着的话这台设备会永远按上一个家长设的作息锁屏，只能卸载重装。
            .remove(K_QUIET_RULES)
            .remove(K_QUIET_ENTERED)
            .remove(K_QUIET_NOTIFIED)
            .remove(K_QUIET_ALARM_AT)
            .commit()
    }

    // ==================== 每日使用累计（上报用） ====================

    /** 今天累计使用了多少毫秒（只统计"亮屏且解锁且不在休息"的时间） */
    var todayUsedMs: Long
        get() = sp.getLong(K_TODAY_USED, 0L)
        set(v) = edit { it.putLong(K_TODAY_USED, v) }

    /** 上面的数字属于哪一天（yyyy-MM-dd），跨天归零 */
    var todayDay: String
        get() = sp.getString(K_TODAY_DAY, "") ?: ""
        set(v) = edit { it.putString(K_TODAY_DAY, v) }

    // ============ 今天"不计入使用时长"的区间（休息 / 停用） ============
    //
    // 为什么需要它：休息和停用用的是**悬浮窗**遮罩，前台应用并没有变。
    // 系统事件里那段时间仍然记在微信 / 抖音头上，直接按片段求和会把
    // "被锁着干等"的时间也算成"孩子在玩"。必须把这些区间扣掉。
    //
    // 存成 [[start,end],...]，end == 0 表示这一段还没结束（正在休息/停用）。
    // 按天存：跨天自动作废，不需要额外清理。

    private var offRangesRaw: String
        get() = sp.getString(K_OFF_RANGES, "") ?: ""
        set(v) = edit { it.putString(K_OFF_RANGES, v) }

    private var offRangesDay: String
        get() = sp.getString(K_OFF_DAY, "") ?: ""
        set(v) = edit { it.putString(K_OFF_DAY, v) }

    private fun dayKeyOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun loadOffRanges(now: Long): MutableList<LongArray> {
        if (offRangesDay != dayKeyOf(now)) return mutableListOf()
        val raw = offRangesRaw
        if (raw.isBlank()) return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val out = mutableListOf<LongArray>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONArray(i) ?: continue
            val s = o.optLong(0, 0L)
            val e = o.optLong(1, 0L)
            if (s > 0L) out.add(longArrayOf(s, e))
        }
        return out
    }

    private fun saveOffRanges(now: Long, list: List<LongArray>) {
        val arr = JSONArray()
        // 只留最近 60 条：一天进出几十次已经很多了，再多就是异常抖动
        val tail = if (list.size > 60) list.subList(list.size - 60, list.size) else list
        for (r in tail) arr.put(JSONArray().put(r[0]).put(r[1]))
        offRangesDay = dayKeyOf(now)
        offRangesRaw = arr.toString()
    }

    /** 进入休息 / 停用：开一段"不计入使用"的区间 */
    fun pushOffStart(now: Long) {
        val list = loadOffRanges(now)
        // 上一段还没闭合就再开一段，说明状态机漏了一次收尾，先补上
        val last = list.lastOrNull()
        if (last != null && last[1] == 0L) last[1] = now
        list.add(longArrayOf(now, 0L))
        saveOffRanges(now, list)
    }

    /** 结束休息 / 停用：把最后一段没闭合的区间收在 [now] */
    fun closeOff(now: Long) {
        val list = loadOffRanges(now)
        val last = list.lastOrNull() ?: return
        if (last[1] != 0L) return
        if (now <= last[0]) {
            list.removeAt(list.size - 1)   // 时长为负或为零，直接丢掉
        } else {
            last[1] = now
        }
        saveOffRanges(now, list)
    }

    /**
     * 读出今天所有"不计入使用"的区间。
     *
     * 未闭合的那一段按 [now] 截断 —— 进程被杀时来不及写结束时间，
     * 不截断的话会一直算到下一次扫描，把之后真实的玩耍时间全扣掉。
     */
    fun offRanges(now: Long): List<LongArray> {
        val list = loadOffRanges(now)
        return list.map { r ->
            val e = if (r[1] == 0L) now else r[1]
            longArrayOf(r[0], e)
        }.filter { it[1] > it[0] }
    }

    /** 跨天清理（由 rolloverDayIfNeeded 调用） */
    fun resetOffRanges() {
        offRangesDay = ""
        offRangesRaw = ""
    }

    // ==================== 进程存活监控 ====================

    /**
     * 心跳时间戳，用 [android.os.SystemClock.elapsedRealtime] 记录。
     *
     * 为什么不用 System.currentTimeMillis()：关机重启后墙上时钟是连续的，
     * 会把"关机"误判成"进程被杀"。elapsedRealtime 开机归零，
     * 只要发现比上次小，就说明是重启而不是被杀。
     */
    var heartbeatElapsed: Long
        get() = sp.getLong(K_HEARTBEAT_ELAPSED, 0L)
        set(v) = edit { it.putLong(K_HEARTBEAT_ELAPSED, v) }

    /** 累计被强行结束的次数（划掉任务卡片 / 强行停止 / 被系统清理） */
    var killCount: Int
        get() = sp.getInt(K_KILL_COUNT, 0)
        set(v) = edit { it.putInt(K_KILL_COUNT, v) }

    /** 最近一次被强行结束的时间 */
    var lastKillWall: Long
        get() = sp.getLong(K_LAST_KILL, 0L)
        set(v) = edit { it.putLong(K_LAST_KILL, v) }

    /** 最近一次被强行结束的原因，展示在诊断区 */
    var lastKillReason: String
        get() = sp.getString(K_LAST_KILL_REASON, "") ?: ""
        set(v) = edit { it.putString(K_LAST_KILL_REASON, v) }

    // ==================== 后台冻结诊断 ====================

    /**
     * 守护的"节拍"（主线程每秒一次 tick）停过几次、最长停了多久。
     *
     * 为什么需要这个数：前台服务 + 常驻通知**只能证明进程没被杀**，
     * 证明不了它还在跑。ColorOS 的「应用速冻」、部分 ROM 的「睡眠待机优化」
     * 会把后台进程整个冻结（cgroup freezer），线程全部暂停 ——
     * 通知栏还挂着，但里面的数字不再刷新。
     *
     * 所以只要两次 tick 的间隔明显超过 1 秒，就记一笔。家长在诊断区看到
     * 「后台停滞：N 次（最长 X 秒）」就知道该去关速冻了。
     */
    var stallCount: Int
        get() = sp.getInt(K_STALL_COUNT, 0)
        set(v) = edit { it.putInt(K_STALL_COUNT, v) }

    /** 最长的一次停滞（毫秒） */
    var stallMaxMs: Long
        get() = sp.getLong(K_STALL_MAX, 0L)
        set(v) = edit { it.putLong(K_STALL_MAX, v) }

    /** 最近一次停滞的时间 */
    var stallLastWall: Long
        get() = sp.getLong(K_STALL_LAST, 0L)
        set(v) = edit { it.putLong(K_STALL_LAST, v) }

    /** 记一次 tick 停滞。写盘频率很低（只在真的停顿时），用 commit 保证可靠 */
    fun noteStall(gapMs: Long) {
        sp.edit()
            .putInt(K_STALL_COUNT, sp.getInt(K_STALL_COUNT, 0) + 1)
            .putLong(K_STALL_MAX, maxOf(sp.getLong(K_STALL_MAX, 0L), gapMs))
            .putLong(K_STALL_LAST, System.currentTimeMillis())
            .commit()
    }

    fun clearStall() {
        sp.edit()
            .putInt(K_STALL_COUNT, 0)
            .putLong(K_STALL_MAX, 0L)
            .putLong(K_STALL_LAST, 0L)
            .commit()
    }

    /**
     * 已经排好的「使用上限到点」闹钟时间。
     *
     * 用来避免每秒重排 —— 正常计时下 `now + 剩余时长` 恒等于同一个时刻，
     * 所以这个值排一次就基本不用再动。
     */
    var deadlineAtWall: Long
        get() = sp.getLong(K_DEADLINE_AT, 0L)
        set(v) = edit { it.putLong(K_DEADLINE_AT, v) }

    /**
     * 上一次"整天重扫"的时刻（毫秒）。
     *
     * v2.3 起采集改为无状态的整日重扫：每 5 分钟从今天 0 点扫一遍，
     * 平时只补最近 6 分钟。这个时间戳只用来控制节奏，**丢了也不影响准确性**
     * —— 顶多是多扫一次，而重扫的结果是幂等的。
     *
     * （[usageCursor] / [usageSegStart] / [usageSegPkg] 是 v2.2 的滑动窗口方案，
     * 已停用：任何一次心跳丢失都会让那段时间永久丢失，只保留字段做兼容。）
     */
    var usageFullScanAt: Long
        get() = sp.getLong(K_USAGE_FULL_SCAN, 0L)
        set(v) = edit { it.putLong(K_USAGE_FULL_SCAN, v) }

    /** 已经上报到哪个时刻（毫秒）。v2.3 起不再使用，保留字段兼容旧版本。 */
    var usageCursor: Long
        get() = sp.getLong(K_USAGE_CURSOR, 0L)
        set(v) = edit { it.putLong(K_USAGE_CURSOR, v) }

    /**
     * "当前正在使用的那一段"的起点（毫秒）与包名。
     *
     * 前台应用是个**进行中**的状态：孩子可能连着用 B 站一个小时，中途一次都不切换。
     * 我们每 20 秒心跳一次，每次都要把这一段重新报一遍、把 `end` 往后推。
     *
     * ★ 关键是 `start` 必须**保持不变**。
     * 服务端按 `(start, pkg)` 幂等覆盖，start 一变就退化成"每 20 秒新开一段"——
     * 一个小时能攒出 180 条碎片，时间轴上全挤成一团，
     * 时长统计和"打开次数"也都跟着失真。
     */
    var usageSegStart: Long
        get() = sp.getLong(K_USAGE_SEG_START, 0L)
        set(v) = edit { it.putLong(K_USAGE_SEG_START, v) }

    /** 当前段的包名；空串表示"没有进行中的段"（刚熄屏/刚重启） */
    var usageSegPkg: String
        get() = sp.getString(K_USAGE_SEG_PKG, "") ?: ""
        set(v) = edit { it.putString(K_USAGE_SEG_PKG, v) }

    /**
     * 服务"上一次是否正常退出"。
     *
     * 这是判断"进程有没有被强行结束"最可靠的信号：
     * 进程被强行结束 / 被系统直接清理时，onDestroy() **不会**执行，
     * 这个标记就会残留为 true。下次服务启动时读到 true，就说明上次是"非正常死亡"。
     * 正常 stopSelf / 家长关闭守护时，onDestroy 会把标记清成 false。
     */
    var serviceAlive: Boolean
        get() = sp.getBoolean(K_SERVICE_ALIVE, false)
        set(v) {
            // 这里刻意用 commit() 同步写入（调用点之后进程随时可能消失）
            sp.edit().putBoolean(K_SERVICE_ALIVE, v).commit()
        }

    /**
     * 进入一次强制休息，关键状态**同步落盘**。
     *
     * 必须用 commit()：调用点（onTaskRemoved）之后进程随时可能被系统立刻回收，
     * apply() 的异步写入有很大概率丢失，那样"惩罚休息"就白设了。
     */
    fun commitRest(untilWall: Long, restCount: Int, now: Long) {
        sp.edit()
            .putLong(K_LOCKED_UNTIL, untilWall)
            .putLong(K_SESSION_USED, 0L)
            .putLong(K_SESSION_START, 0L)
            .putLong(K_LAST_IDLE, now)
            .putInt(K_REST_COUNT, restCount)
            .commit()
    }

    /**
     * 这一轮休息的说明（写在遮罩倒计时下面）。
     *
     * 为什么必须有：家长配的是 5 分钟、实际休息 15 分钟时，光看倒计时
     * 只会认为"程序算错了"。写清「违规惩罚 ×3 · 本次 15 分钟」，
     * 他一眼就知道是惩罚倍数把它放大了，而不是计时出 bug。
     */
    var restPlan: String
        get() = sp.getString(K_REST_PLAN, "") ?: ""
        set(v) = edit { it.putString(K_REST_PLAN, v) }

    /**
     * 上一次进程是怎么退出的（系统记录，Android 11 / API 30 起可用）。
     *
     * 返回 `退出原因 to 时间戳`，拿不到返回 null（老系统，或被 ROM 抹掉）。
     *
     * 这是区分「用户主动强行停止」和「被省电策略/内存回收干掉」的唯一可靠证据：
     * 前者才算孩子拆防护，后者在国产 ROM 上每天都会发生好几次。
     * 老代码只看"我排的保活闹钟还在不在"，可**重启本来就会清空全部闹钟** ——
     * 于是每次开机都被记成一次违规，惩罚倍数一路涨到 ×3（设 5 分钟休 15 分钟）。
     */
    fun lastExitInfo(context: Context): Pair<Int, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val list = am?.getHistoricalProcessExitReasons(context.packageName, 0, 4)
            list?.firstOrNull()?.let { it.reason to it.timestamp }
        }.getOrNull()
    }

    /**
     * 记录一次"被强行结束"。
     *
     * 这里必须用 commit() 同步落盘而不是 apply()：调用点（如 onTaskRemoved）
     * 之后进程随时可能被系统立刻回收，异步写入有很大概率丢失。
     */
    fun markKill(reason: String) {
        val count = sp.getInt(K_KILL_COUNT, 0) + 1
        sp.edit()
            .putInt(K_KILL_COUNT, count)
            .putLong(K_LAST_KILL, System.currentTimeMillis())
            .putString(K_LAST_KILL_REASON, reason)
            .commit()
    }

    // ==================== 保活诊断 ====================

    /**
     * 是否**曾经**把保活闹钟挂进系统。
     *
     * 用来区分两种情况：
     *  - 首次安装后第一次启动 → 当然找不到闹钟，这不算异常；
     *  - 之前挂过、现在系统里却查不到 → 说明应用被置成了 stopped 状态
     *    （"强行停止"会清空全部闹钟与任务），这是唯一无法自救的情况。
     */
    var watchdogArmed: Boolean
        get() = sp.getBoolean(K_WATCHDOG_ARMED, false)
        set(v) {
            // 同步落盘：这个标记要在"进程随时可能消失"的场景下可靠
            sp.edit().putBoolean(K_WATCHDOG_ARMED, v).commit()
        }

    /** 强保活通道（闹钟时钟）下次触发时间，避免每次都重排导致状态栏图标闪 */
    var safetyAtWall: Long
        get() = sp.getLong(K_SAFETY_AT, 0L)
        set(v) = edit { it.putLong(K_SAFETY_AT, v) }

    /** 最近一次服务启动的时间 */
    var lastStartWall: Long
        get() = sp.getLong(K_LAST_START_WALL, 0L)
        set(v) = edit { it.putLong(K_LAST_START_WALL, v) }

    /** 最近一次服务启动的触发来源（闹钟 / 开机 / 用户打开…），展示在诊断区 */
    var lastStartTrigger: String
        get() = sp.getString(K_LAST_START_TRIGGER, "") ?: ""
        set(v) = edit { it.putString(K_LAST_START_TRIGGER, v) }

    /** 这次启动之前，守护进程已经中断了多久（毫秒）；-1 表示首次或无法判断 */
    var lastDowntimeMs: Long
        get() = sp.getLong(K_LAST_DOWNTIME, -1L)
        set(v) = edit { it.putLong(K_LAST_DOWNTIME, v) }

    /** 任务卡片被从「最近任务」划掉的次数 */
    var taskRemovedCount: Int
        get() = sp.getInt(K_TASK_REMOVED_COUNT, 0)
        set(v) = edit { it.putInt(K_TASK_REMOVED_COUNT, v) }

    var lastTaskRemovedWall: Long
        get() = sp.getLong(K_TASK_REMOVED_WALL, 0L)
        set(v) = edit { it.putLong(K_TASK_REMOVED_WALL, v) }

    /** 疑似被"强行停止"（系统清空了我们的闹钟）的次数 */
    var forceStopCount: Int
        get() = sp.getInt(K_FORCE_STOP_COUNT, 0)
        set(v) = edit { it.putInt(K_FORCE_STOP_COUNT, v) }

    var lastForceStopWall: Long
        get() = sp.getLong(K_FORCE_STOP_WALL, 0L)
        set(v) = edit { it.putLong(K_FORCE_STOP_WALL, v) }

    /**
     * 未兑现的「惩罚欠账」（毫秒）。> 0 表示有一笔惩罚还没执行。
     *
     * 为什么必须落成"欠账"而不能当场执行：
     * 「强行停止」会把应用置为 stopped 状态并清空全部闹钟与任务，进程根本不会被拉起。
     * 也就是说——**检测到这件事的时候，我们通常根本不在运行**。
     * 唯一能做的是先同步落一笔账，等下次有人把 App 打开（或系统重启后手动点开）时立刻兑现。
     */
    var pendingPenaltyMs: Long
        get() = sp.getLong(K_PENDING_PENALTY, 0L)
        set(v) = edit { it.putLong(K_PENDING_PENALTY, v) }

    var pendingPenaltyReason: String
        get() = sp.getString(K_PENDING_REASON, "") ?: ""
        set(v) = edit { it.putString(K_PENDING_REASON, v) }

    /** 累计第几次"拆防护"（强停 / 取消设备管理员）。用于逐次加重惩罚，家长可清零 */
    var tamperStrikes: Int
        get() = sp.getInt(K_TAMPER_STRIKES, 0)
        set(v) = edit { it.putInt(K_TAMPER_STRIKES, v) }

    /** 已经核算过的"上次进程退出"时间戳，避免同一条系统记录被反复当成新违规 */
    var lastExitTs: Long
        get() = sp.getLong(K_LAST_EXIT_TS, 0L)
        set(v) = edit { it.putLong(K_LAST_EXIT_TS, v) }

    /** 最近若干次"拆防护"的时间戳（毫秒，逗号分隔，最多 10 个，新的在前） */
    var tamperTimes: String
        get() = sp.getString(K_TAMPER_TIMES, "") ?: ""
        set(v) = edit { it.putString(K_TAMPER_TIMES, v) }

    /** 最近一次启动服务失败的原因（例如被系统后台启动限制拦截） */
    var lastStartError: String
        get() = sp.getString(K_LAST_START_ERROR, "") ?: ""
        set(v) = edit { it.putString(K_LAST_START_ERROR, v) }

    // ==================== 停用时段（宵禁） ====================

    /**
     * 停用时段规则（服务端下发的权威副本，JSON 数组字符串）。
     *
     * 只由 [Remote] 在收到心跳时覆盖，App 界面**不提供**任何编辑入口 ——
     * 这条限制如果能在孩子手里的设备上改，就等于没有。
     */
    var quietRulesRaw: String
        get() = sp.getString(K_QUIET_RULES, "") ?: ""
        set(v) = edit { it.putString(K_QUIET_RULES, v) }

    /** 本地解析出来的规则列表（每次读都重新解析，规则最多几条，开销可忽略） */
    fun quietRules(): List<Quiet.Rule> = Quiet.fromJson(quietRulesRaw)

    /**
     * 接管停用时段时，如果当时设备本来就在"应停用"的区间里，
     * 记下"这段停用是从哪一刻开始算的"，免得家长刚设好就显示一个
     * 从凌晨算起的巨大倒计时。
     *
     * 0 表示当前不在停用中。
     */
    var quietEnteredWall: Long
        get() = sp.getLong(K_QUIET_ENTERED, 0L)
        set(v) = edit { it.putLong(K_QUIET_ENTERED, v) }

    /** 上一次"到点自动进停用"已经提示过的时刻，避免每秒弹一次 Toast */
    var quietNotifiedWall: Long
        get() = sp.getLong(K_QUIET_NOTIFIED, 0L)
        set(v) = edit { it.putLong(K_QUIET_NOTIFIED, v) }

    /** 已排好的"停用边界"闹钟时刻（墙钟毫秒），0 表示没排 */
    var quietAlarmAtWall: Long
        get() = sp.getLong(K_QUIET_ALARM_AT, 0L)
        set(v) = edit { it.putLong(K_QUIET_ALARM_AT, v) }

    /**
     * 家长在遮罩上点了「提前结束本次停用」之后，本段停用的解除时刻。
     *
     * 只对这一**段**有效：到点自动失效，下一段照旧锁。
     * 如果做成"永久关掉"，家长临时放行一次就等于把宵禁废了。
     */
    var quietSkipUntilWall: Long
        get() = sp.getLong(K_QUIET_SKIP_UNTIL, 0L)
        set(v) = edit { it.putLong(K_QUIET_SKIP_UNTIL, v) }

    /**
     * 清空停用时段。
     *
     * 解绑远程管理时必须调用：否则这台设备会继续按上一个家长设的作息锁屏，
     * 而且再也没有渠道能改回来 —— 只能卸载重装。
     */
    fun clearQuiet() {
        sp.edit()
            .remove(K_QUIET_RULES)
            .remove(K_QUIET_ENTERED)
            .remove(K_QUIET_NOTIFIED)
            .remove(K_QUIET_ALARM_AT)
            .remove(K_QUIET_SKIP_UNTIL)
            .commit()
    }

    /** 记录一次服务启动（同步落盘：调用点之后进程随时可能消失） */
    fun markStart(trigger: String, now: Long) {
        sp.edit()
            .putString(K_LAST_START_TRIGGER, trigger)
            .putLong(K_LAST_START_WALL, now)
            .commit()
    }

    /** 记录一次"卡片被划掉"（同步落盘，理由同上） */
    fun markTaskRemoved(now: Long) {
        sp.edit()
            .putInt(K_TASK_REMOVED_COUNT, sp.getInt(K_TASK_REMOVED_COUNT, 0) + 1)
            .putLong(K_TASK_REMOVED_WALL, now)
            .commit()
    }

    /** 记录一次"疑似被强行停止"（同步落盘：调用点之后进程随时可能消失） */
    fun markForceStop(now: Long) {
        sp.edit()
            .putInt(K_FORCE_STOP_COUNT, sp.getInt(K_FORCE_STOP_COUNT, 0) + 1)
            .putLong(K_FORCE_STOP_WALL, now)
            .putString(K_TAMPER_TIMES, pushTamperTime(now))
            .commit()
    }

    /** 最近若干次拆防护的时间戳，新的在前 */
    fun tamperTimeList(): List<Long> =
        tamperTimes.split(',').mapNotNull { it.trim().toLongOrNull() }

    private fun pushTamperTime(now: Long): String {
        val list = tamperTimeList()
        // 同一时刻会被 markForceStop 和 noteTamper 各记一次，这里去重
        if (list.firstOrNull() == now) return tamperTimes
        return (listOf(now) + list).take(MAX_TAMPER_TIMES).joinToString(",")
    }

    /**
     * 第 n 次违规对应的惩罚时长。逐次加重，封顶 [MAX_PENALTY_MS]。
     *
     * 为什么要加重：家长实测孩子已经试了 6 次「强行停止」，说明一次普通休息
     * 对他不构成任何代价。第 2 次起翻倍，是让"这条捷径"变得明显不划算。
     */
    fun penaltyMsFor(strikes: Int): Long {
        val factor = when {
            strikes <= 1 -> 1
            strikes == 2 -> 2
            else -> 3
        }
        return minOf(restMinutes * 60_000L * factor, MAX_PENALTY_MS)
    }

    /**
     * 记一笔"拆防护"（强停 / 取消设备管理员），并按当前策略生成欠账。
     *
     * @return 是否生成了新的惩罚欠账（未开启惩罚时返回 false，只留痕）
     */
    fun noteTamper(now: Long, reason: String): Boolean {
        val strikes = tamperStrikes + 1
        val usePenalty = tamperPolicy == TAMPER_REST
        val ms = if (usePenalty) penaltyMsFor(strikes) else 0L

        val e = sp.edit()
            .putInt(K_TAMPER_STRIKES, strikes)
            .putString(K_TAMPER_TIMES, pushTamperTime(now))
        if (ms > 0L) {
            // 一次没兑现完又来一次：取较大者，不叠加成天文数字
            e.putLong(K_PENDING_PENALTY, maxOf(sp.getLong(K_PENDING_PENALTY, 0L), ms))
                .putString(K_PENDING_REASON, reason)
        }
        e.commit()
        return ms > 0L
    }

    /** 取走欠账（读出并清零）。没有欠账返回 null */
    fun takePenalty(): Pair<Long, String>? {
        val ms = sp.getLong(K_PENDING_PENALTY, 0L)
        if (ms <= 0L) return null
        val reason = sp.getString(K_PENDING_REASON, "") ?: ""
        sp.edit()
            .putLong(K_PENDING_PENALTY, 0L)
            .putString(K_PENDING_REASON, "")
            .commit()
        return ms to reason
    }

    /**
     * 家长手动清零违规记录（同时撤销未兑现的欠账）。
     *
     * 必须有这个出口：惩罚是逐次加重的，如果孩子一次闹到 3 倍，
     * 之后每一次都是 3 倍，会一直卡在最重档。
     */
    fun clearTamper() {
        sp.edit()
            .putInt(K_TAMPER_STRIKES, 0)
            .putInt(K_FORCE_STOP_COUNT, 0)
            .putInt(K_TASK_REMOVED_COUNT, 0)
            .putLong(K_PENDING_PENALTY, 0L)
            .putString(K_PENDING_REASON, "")
            .putString(K_TAMPER_TIMES, "")
            .putLong(K_LAST_KILL, 0L)
            .putString(K_LAST_KILL_REASON, "")
            .commit()
    }

    /** 清空本轮计时 */
    fun resetSession() {
        sessionStartWall = 0L
        sessionUsedMs = 0L
        lastIdleWall = 0L
        // 本轮从"此刻"重新算。设成 0 会被当成"从今天 0 点算"，
        // 那样家长一点「重新开始」就立刻又超时 —— 必须留一个真实起点。
        roundStartWall = System.currentTimeMillis()
    }

    companion object {
        /** 被强行结束后：只记录 */
        const val TAMPER_OFF = 0

        /** 被强行结束后：立刻触发一次强制休息 */
        const val TAMPER_REST = 1

        /** 惩罚时长上限：3 小时（防止倍数叠出一个离谱的数字） */
        const val MAX_PENALTY_MS = 180L * 60_000L

        /** 「拆防护」时间记录保留条数 */
        const val MAX_TAMPER_TIMES = 10

        private const val FILE = "guardian_prefs"
        private const val K_ENABLED = "enabled"
        private const val K_USE_MIN = "use_minutes"
        private const val K_REST_MIN = "rest_minutes"
        private const val K_REST_PLAN = "rest_plan_text"
        private const val K_LAST_EXIT_TS = "last_exit_ts"
        private const val K_RESET_GAP = "reset_gap_seconds"
        private const val K_PIN = "pin"
        private const val K_PIN_FAILS = "pin_fails"
        private const val K_PIN_LOCKED_UNTIL = "pin_locked_until"
        private const val K_TAMPER_POLICY = "tamper_policy"
        private const val K_PAUSE_MEDIA = "pause_media_on_rest"
        private const val K_MUTE_ON_REST = "mute_on_rest"
        private const val K_MUTED_SAVED_VOLUME = "muted_saved_volume"
        private const val K_MUTED_BY_US = "muted_by_us"
        private const val K_SESSION_START = "session_start"
        private const val K_SESSION_USED = "session_used_ms"
        private const val K_LAST_IDLE = "last_idle"
        private const val K_ROUND_START = "round_start_wall"
        private const val K_LOCKED_UNTIL = "locked_until"
        private const val K_REST_COUNT = "rest_count"
        private const val K_REST_DAY = "rest_day"
        private const val K_HEARTBEAT_ELAPSED = "heartbeat_elapsed"
        private const val K_KILL_COUNT = "kill_count"
        private const val K_LAST_KILL = "last_kill_wall"
        private const val K_LAST_KILL_REASON = "last_kill_reason"
        private const val K_SERVICE_ALIVE = "service_alive"
        private const val K_WATCHDOG_ARMED = "watchdog_armed"
        private const val K_SAFETY_AT = "safety_at_wall"
        private const val K_LAST_START_WALL = "last_start_wall"
        private const val K_LAST_START_TRIGGER = "last_start_trigger"
        private const val K_LAST_DOWNTIME = "last_downtime_ms"
        private const val K_TASK_REMOVED_COUNT = "task_removed_count"
        private const val K_TASK_REMOVED_WALL = "last_task_removed_wall"
        private const val K_FORCE_STOP_COUNT = "force_stop_count"
        private const val K_FORCE_STOP_WALL = "last_force_stop_wall"
        private const val K_PENDING_PENALTY = "pending_penalty_ms"
        private const val K_PENDING_REASON = "pending_penalty_reason"
        private const val K_TAMPER_STRIKES = "tamper_strikes"
        private const val K_TAMPER_TIMES = "tamper_times"
        private const val K_LAST_START_ERROR = "last_start_error"

        // ---- 停用时段（宵禁）----
        private const val K_QUIET_RULES = "quiet_rules_json"
        private const val K_QUIET_ENTERED = "quiet_entered_wall"
        private const val K_QUIET_NOTIFIED = "quiet_notified_wall"
        private const val K_QUIET_ALARM_AT = "quiet_alarm_at_wall"
        private const val K_QUIET_SKIP_UNTIL = "quiet_skip_until_wall"

        // ---- 后台冻结诊断 / 到点闹钟 ----
        private const val K_STALL_COUNT = "tick_stall_count"
        private const val K_STALL_MAX = "tick_stall_max_ms"
        private const val K_STALL_LAST = "tick_stall_last_wall"
        private const val K_DEADLINE_AT = "deadline_at_wall"
        private const val K_USAGE_FULL_SCAN = "usage_full_scan_ms"
        private const val K_USAGE_CURSOR = "usage_cursor_ms"
        private const val K_USAGE_SEG_START = "usage_seg_start_ms"
        private const val K_USAGE_SEG_PKG = "usage_seg_pkg"

        // ---- 远程管理 ----
        private const val K_SERVER_URL = "remote_server_url"
        private const val K_DEVICE_ID = "remote_device_id"
        private const val K_DEVICE_TOKEN = "remote_device_token"
        private const val K_REMOTE_NAME = "remote_device_name"
        private const val K_REMOTE_LAST_OK = "remote_last_ok_wall"
        private const val K_REMOTE_LAST_ERROR = "remote_last_error"
        private const val K_REMOTE_LAST_ERROR_WALL = "remote_last_error_wall"

        // ---- 每日累计 ----
        private const val K_TODAY_USED = "today_used_ms"
        private const val K_TODAY_DAY = "today_used_day"
        private const val K_DAY_LIMIT_MIN = "day_limit_minutes"
        private const val K_DAY_CLEARED_DAY = "day_cleared_day"
        private const val K_DAY_UNLOCK_UNTIL = "day_unlock_until_wall"
        private const val K_DAY_LOCK_NOTICE_DAY = "day_lock_notice_day"
        private const val K_OFF_RANGES = "off_ranges_json"
        private const val K_OFF_DAY = "off_ranges_day"
    }
}
