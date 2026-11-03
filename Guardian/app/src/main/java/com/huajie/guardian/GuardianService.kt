package com.huajie.guardian

import android.app.ApplicationExitInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 守护服务：整个应用的大脑。
 *
 * 工作机制（不需要 Root、不需要"使用情况访问"权限）：
 *  1. 每秒轮询「屏幕是否点亮 + 是否已解锁」，以此判断孩子是否"正在使用"。
 *  2. 连续使用时长达到上限 → 弹全屏遮罩，倒计时强制休息。
 *  3. 遮罩有两级，互为兜底：
 *       一级：TYPE_APPLICATION_OVERLAY 系统悬浮窗（首选，层级最高，能吃掉返回键）
 *       二级：LockActivity 全屏 Activity（国产 ROM 拦截悬浮窗时自动降级启用）
 *  4. 前台服务 + 常驻通知，保证在后台长时间存活。
 *  5. 进程保活：onTaskRemoved 后立刻自我重启，另有每分钟心跳闹钟负责复活。
 */
class GuardianService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager

    private val handler = Handler(Looper.getMainLooper())

    /** 上一次采样时"是否正在使用"，用于捕捉状态跳变 */
    private var lastActive = false

    private var tickerRunning = false

    /** 上一次把心跳写盘的时间，避免每秒都写一次磁盘 */
    private var lastHeartbeatWrite = 0L

    // ---- 使用时长：从系统事件重建 ----
    //
    // ★ v2.4.1 起这里**不再做"每秒累加"**。
    //
    // 老做法是 `todayBufferMs += dt`，并且 `dt > 5 秒` 就整段丢掉。看着合理，
    // 实际在 OPPO/华为上必然失真：应用速冻会把进程冻住几分钟，解冻后第一个
    // tick 的 dt 是几百秒，按规则直接扔 —— 孩子在玩，后台显示只用了 20 分钟；
    // 进程被杀重启后那段更彻底，直接不存在。
    //
    // 现在一律"从今天 0 点重扫系统事件"，同一段时间算多少遍结果都一样，
    // 漏几次心跳、杀几次进程都能自愈。每 [USAGE_SCAN_MS] 算一次就够。

    /** 上一次重建的时间 */
    private var usageScanAt = 0L

    /** 重建是否可用（没授权"使用情况访问"时为 false，退回老的秒级累加） */
    private var usageReady = false

    /** 上一次重建出的"今日累计使用" */
    private var todayFromEvents = 0L

    /** 上一次重建出的"本轮已用" */
    private var roundFromEvents = 0L

    /** 兜底：上一次 tick 的时间（只在 [usageReady] 为 false 时用） */
    private var lastTickWall = 0L

    /** 兜底：今日累计里还没落盘的毫秒数 */
    private var todayBufferMs = 0L
    private var lastTodayFlush = 0L

    /** 上一次向服务器发心跳的时间 */
    private var lastRemoteBeat = 0L

    /**
     * 上一次 tick 的 [SystemClock.elapsedRealtime]。
     *
     * 用来量两次节拍之间的空档：正常是 1 秒，一旦明显变长，说明进程被冻结
     * 或主线程被别的东西占住了 —— 这正是"通知里的计时两三秒后不动"的原因。
     */
    private var lastTickElapsed = 0L

    /** 上一次真正推送到通知栏的内容指纹，避免每秒重复 notify（国产 ROM 会节流） */
    private var lastNotifKey: String? = null

    // ---- 悬浮窗相关 ----
    /** API 30+ 用 createWindowContext 拿到的窗口上下文，必须持有强引用否则窗口会失效 */
    private var overlayWindowContext: Context? = null
    private var overlayWm: WindowManager? = null
    private var overlayView: View? = null

    /** 本轮休息是否已经提示过遮罩失败，避免每秒弹一次 Toast */
    private var overlayErrorNotified = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> handler.post { step() }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            // ★ 两件事必须保证，否则守护会"静悄悄地死掉"：
            //
            //  1) step() 抛任何异常都不能让节拍断掉。原来的写法是
            //     `step(); postDelayed(...)`，一次异常就等于计时节拍永久停摆，
            //     而前台服务通知还挂在通知栏上 —— 看起来"应用在跑"，其实全停了。
            //  2) 两次节拍之间的空档要记下来。前台服务只能证明进程没被杀，
            //     证明不了它还在跑：ColorOS 的「应用速冻」会把整个进程冻结，
            //     线程全部暂停，通知栏的数字也就不再刷新。这个数就是证据。
            val el = SystemClock.elapsedRealtime()
            val prev = lastTickElapsed
            lastTickElapsed = el
            if (prev > 0L) {
                val gap = el - prev
                if (gap > STALL_THRESHOLD_MS) {
                    runCatching { prefs.noteStall(gap) }
                    Log.w(TAG, "tick 停滞 ${gap}ms（后台被冻结，或主线程被占）")
                }
            }
            try {
                step()
            } catch (t: Throwable) {
                Log.e(TAG, "step 异常（已吞掉，节拍继续）", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        createChannel()
        // 上一次运行若在"休息静音"状态下被杀，音量可能还停在 0，这里恢复回来
        Silence.recoverIfStuck(this, prefs)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 这次是谁把我们叫起来的？——诊断区要靠它区分"闹钟自动复活"和"用户手动打开"
        val trigger = when (intent?.action) {
            ACTION_START -> "家长打开开关"
            ACTION_HEARTBEAT -> "保活闹钟"
            ACTION_TEST_REST -> "家长测试"
            ACTION_REFRESH -> "参数刷新"
            ACTION_DEADLINE -> "到点闹钟"
            ACTION_QUIET -> "停用时段闹钟"
            ACTION_STOP -> "家长关闭守护"
            null -> "系统重启服务"
            else -> intent.action ?: "未知"
        }

        // ---- 家长主动关闭守护 ----
        //
        // 如果已经和远程后台配对，这里**不能**把服务真的停掉：
        // 服务一停就再没有心跳，家长在后台点「开启守护」永远送不到设备上，
        // 只能人跑过去手动开。所以改成"远程待命"：通知文案变成"已暂停"，
        // 遮罩和计时全停，只留心跳。
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            prefs.resetSession()
            prefs.lockedUntilWall = 0L
            prefs.closeOff(System.currentTimeMillis())
            endRest()
            if (!prefs.remoteConfigured()) {
                teardown()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_TEST_REST -> {
                // 家长点"立即测试"：不等时长用完，直接进入一次休息。
                // 测试不计入"今日休息次数"，免得家长自测把统计弄乱。
                if (prefs.enabled) {
                    val now = System.currentTimeMillis()
                    val restMs = prefs.restMinutes * 60_000L
                    prefs.commitRest(now + restMs, prefs.restCount, now)
                    sResting = true
                    sRestLeftMs = restMs
                    overlayErrorNotified = false
                    lastOverlayError = null
                }
            }
            ACTION_REFRESH -> {
                // 参数改了，重新评估一次
            }
            ACTION_HEARTBEAT -> {
                // 心跳闹钟或复活闹钟把我们叫醒了，走下面的保活流程即可
            }
        }

        if (!prefs.enabled && !prefs.remoteConfigured()) {
            KeepAlive.cancelAll(this)
            teardown()
            return START_NOT_STICKY
        }

        // 反推"应用是不是被强行停止过"。
        //
        // ★ 老判据（只看保活闹钟还在不在）有两个致命误判，v2.3.1 全部修掉：
        //
        //   1. **重启会清空所有闹钟**。于是每次开机都被当成"孩子强停了"，
        //      违规次数单调上涨；惩罚是逐次加重的（×1 → ×2 → ×3），
        //      家长设 5 分钟，孩子的休息时间就变成了 15 分钟。
        //   2. OPPO「应用速冻」/ 内存回收也会让闹钟消失，同样不算人为。
        //
        // 现在的判据：
        //   - 先排除重启（elapsedRealtime 回绕 = 重启过，系统的闹钟当然没了）；
        //   - 能拿到系统退出记录（Android 11+）就以它为准，只有
        //     REASON_USER_REQUESTED / REASON_USER_STOPPED 才算强行停止；
        //     被省电策略杀（REASON_FREEZER）、被回收（REASON_LOW_MEMORY）一律不算；
        //   - 拿不到记录（老系统）才退回"闹钟被清空"这个弱判据。
        //
        // 这是**唯一无法自救**的情况：stopped 状态下，闹钟、Job、开机广播全被清掉，
        // 任何代码都不会被执行。所以这里不试图"救活"，只做两件事：
        //   1. 留痕（家长在诊断区能看见是几点、几次）；
        //   2. 落一笔惩罚欠账，等下次 App 被打开时第一秒就兑现。
        //
        // 必须放在 armWatchdog() 之前判断：排上之后当然就查得到了，永远发现不了。
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastElapsed = prefs.heartbeatElapsed
        val rebooted = lastElapsed > 0L && nowElapsed < lastElapsed
        val forceStopped = !rebooted && userForceStopped()
        if (forceStopped) {
            val now = System.currentTimeMillis()
            prefs.markForceStop(now)
            prefs.markKill(getString(R.string.kill_reason_force_stop))
            prefs.noteTamper(now, getString(R.string.kill_reason_force_stop))
            Log.w(TAG, "检测到应用曾被「强行停止」，累计 ${prefs.forceStopCount} 次")
        }

        // 进程可能是被系统杀掉后由闹钟拉起来的，先判断这中间是不是"被强行结束"过。
        //
        // 关键：只能在本进程**第一次**启动服务时判断一次。
        // 保活闹钟每 15 秒就会触发一次 onStartCommand，那时 prefs.serviceAlive 已经是 true，
        // 若每次都判断就会被当成"上次死于非命"，变成每 15 秒误判一次并触发惩罚。
        if (!sCheckedKillInThisProcess) {
            sCheckedKillInThisProcess = true
            detectKill(forceStopped)
        }

        prefs.markStart(trigger, System.currentTimeMillis())

        // ★ 前台服务没起来就必须立刻收摊。
        //
        // Android 12+ 对"后台启动前台服务"限制很严，ColorOS / MIUI 还会再加一层。
        // 一旦 startForeground() 失败而我们继续留在运行状态，系统会在约 5 秒后
        // 抛 RemoteServiceException（"Context.startForegroundService() did not then
        // call Service.startForeground()"）把**整个进程**干掉 ——
        // 从用户角度看就是"打开应用闪退"，而且堆栈里根本看不到我们自己的代码。
        if (!startForegroundSafely()) {
            running = false
            prefs.serviceAlive = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 标记"当前正常运行中"。正常退出时 onDestroy 会把它清掉；
        // 若进程是被强杀的，这个标记会残留，下次启动就能据此发现"上次死于非命"
        prefs.serviceAlive = true
        running = true
        if (!tickerRunning) {
            tickerRunning = true
            handler.post(tick)
        }
        // ★ 续期保活通道。
        //
        // 这一步是整个保活机制的核心：闹钟是一次性的，**每次启动都必须重排**，
        // 链条才不会断。而"每次启动都会走这里"这一点，恰好和 ROM 有没有回调
        // onTaskRemoved/onDestroy 无关 —— 即使那两个回调一个都不执行，
        // 系统里的 15 秒闹钟到点照样会把我们叫回来，叫回来之后又排下一次。
        KeepAlive.armWatchdog(this)
        prefs.watchdogArmed = true
        KeepAlive.ensurePeriodicJob(this)
        step()
        return START_STICKY
    }

    /**
     * 任务卡片被从「最近任务」划掉时回调。
     *
     * 这是孩子最常用的"破解"手法。此处必须立刻做三件事：
     *  1. 记一笔（家长在诊断区能看到孩子试过几次）；
     *  2. 若家长开启了惩罚，立刻进入一次休息（关键状态同步落盘，
     *     这样即使进程紧接着被回收，复活后也能继续休息）；
     *  3. 排出复活通道 —— 因为紧接着进程随时可能被系统回收，
     *     而闹钟/任务都注册在系统侧，进程死了照样会被触发。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!prefs.enabled) return

        val now = System.currentTimeMillis()
        prefs.markTaskRemoved(now)
        prefs.markKill(getString(R.string.kill_reason_task_swiped))
        // 这次是我们自己处理的，把"运行中"标记清掉，
        // 免得复活后被 detectKill 重复记一次、导致双重惩罚
        prefs.serviceAlive = false

        accrueTamperPenalty(now, getString(R.string.kill_reason_task_swiped))

        KeepAlive.chainRelaunch(this)
        KeepAlive.scheduleResurrectJob(this)

        // 进程若还活着，立刻把遮罩补回来
        handler.post { step() }
    }

    override fun onDestroy() {
        tickerRunning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlay()
        if (instance === this) instance = null
        // 服务没了就别再占着音频焦点，否则别的应用放不了声音（进程若被强杀，系统会自动收回）
        Silence.endRest(this, prefs)
        running = false
        // onDestroy 能走完，说明是正常退出（stopSelf / 系统优雅停止），清掉标记
        prefs.serviceAlive = false

        // 不是家长主动关闭、而是被系统回收时（此时 enabled 仍为 true），排复活通道
        if (prefs.enabled) {
            KeepAlive.chainRelaunch(this)
            KeepAlive.scheduleResurrectJob(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun teardown() {
        tickerRunning = false
        handler.removeCallbacksAndMessages(null)
        endRest()
        running = false
        sUsing = false
        // 家长主动关闭：撤掉所有复活闹钟与任务，并清掉运行标记，
        // 免得下次重新开启时被误判成"进程被杀过"
        KeepAlive.cancelAll(this)
        prefs.serviceAlive = false
        prefs.heartbeatElapsed = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        stopSelf()
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    private fun step() {
        val now = System.currentTimeMillis()
        rolloverDayIfNeeded()
        writeHeartbeat(now)
        // 使用时长：每 20 秒从系统事件重建一次（幂等），不是每秒累加
        refreshUsage(now)

        // ★ 第一件事：把"还没兑现的惩罚"兑现掉。
        // 被「强行停止」时进程不在运行、遮罩也无从谈起，只能靠这一句在
        // "下次 App 被打开的第一秒"补上。必须放在时长判断之前，
        // 否则会被 usedMsNow() 重新累计使用时长，惩罚就白记了。
        if (prefs.enabled) consumePendingPenalty(now)

        val active = syncActivityState(now)

        if (!prefs.enabled) {
            endRest()
            sResting = false
            sInQuiet = false
            sQuietLeftMs = 0L
            sQuietLabel = ""
            sUsing = false
            sUsedMs = 0L
            // 守护没开，就不该再留一个"到点闹钟"
            KeepAlive.cancelDeadline(this)
            KeepAlive.cancelQuiet(this)
            // 已配对远程后台 → 保持"待命"：只发心跳，不遮屏、不计时。
            // 这样家长在后台点「开启守护」还能把守护重新拉起来。
            if (prefs.remoteConfigured()) {
                maybeRemote(now)
                refreshNotification()
            }
            return
        }

        // ============ 停用时段（宵禁）：优先级高于"休息" ============
        //
        // 语义上这两件事完全不同：
        //   · 休息 = "你用了太久，歇一会儿"，时长由使用量决定；
        //   · 停用 = "现在不是玩手机的时间"，由墙上时钟决定。
        // 停用期间不该继续累计使用时长，也不该再判"用满了该休息"——
        // 否则熬过宵禁一解锁就被判超时、立刻又锁，孩子会以为是死循环。
        val quietHit = Quiet.current(this, prefs, now)
        if (quietHit != null) {
            if (!sInQuiet) enterQuiet(now, quietHit)
            sInQuiet = true
            sQuietLeftMs = quietHit.leftMs
            sQuietLabel = quietHit.label(this)

            // 遮罩挡住画面的同时，把后面的视频也按住（和休息一致）
            Silence.applyRest(this, prefs)
            ensureOverlay()
            if (overlayView == null) ensureLockActivity()
            renderOverlay(sQuietLeftMs, RestUi.Mode.QUIET)

            // 停用期间不算"在用"，通知也不该显示正计时
            sUsing = false
            sUsedMs = 0L
            // 到点闹钟换成"放开那一刻"，用时长闹钟就没意义了
            KeepAlive.cancelDeadline(this)
            armQuietBoundary(now, inQuiet = true)
            maybeRemote(now)
            refreshNotification()
            return
        }
        if (sInQuiet) exitQuiet(now, notify = true)

        val leftMs = prefs.lockedUntilWall - now

        if (leftMs > 0) {
            // ---------- 正在强制休息 ----------
            sResting = true
            sRestLeftMs = leftMs
            // 画面被遮罩挡住的同时，把后面的视频也按住（申请音频焦点 / 暂停播放器）。
            // 每轮都调：视频 App 可能自动续播，压一次不够。
            Silence.applyRest(this, prefs)
            ensureOverlay()
            // 悬浮窗加不上就退到全屏 Activity 兜底
            if (overlayView == null) ensureLockActivity()
            renderOverlay(leftMs)
        } else {
            if (prefs.lockedUntilWall != 0L) {
                // ---------- 休息刚结束 ----------
                prefs.lockedUntilWall = 0L
                prefs.sessionUsedMs = 0L
                prefs.sessionStartWall = if (active) now else 0L
                prefs.lastIdleWall = now
                // 本轮从这一刻重新算；同时把"不计入使用"的区间收尾，
                // 否则休息这段时间会被当成"还在玩"扣不掉
                prefs.roundStartWall = now
                prefs.closeOff(now)
                endRest()
                toast(getString(R.string.rest_finished))
            } else {
                sResting = false
            }

            // ---------- 判断是否该进入休息 ----------
            if (active && usedMsNow(now) >= prefs.useMinutes * 60_000L) {
                startRest(now)
            }
        }

        sUsing = active
        sUsedMs = usedMsNow(now)
        armUsageDeadline(now, active)
        armQuietBoundary(now, inQuiet = false)
        maybeRemote(now)
        refreshNotification()
    }

    /**
     * 把「使用上限到点那一刻」交给系统闹钟。
     *
     * 这是对付"后台被冻结"的兜底。守护的计时靠主线程每秒 tick，而国产 ROM
     * 可以把整个后台进程冻住（ColorOS 的「应用速冻」、睡眠待机优化），
     * tick 一停，用满上限也不会弹遮罩。闹钟注册在系统侧，进程冻结时照样投递，
     * 到点会把服务叫醒，[step] 再判定超时并建立遮罩。
     *
     * 只在"守护开着、不在休息、屏幕点亮"时排；熄屏或暂停就撤掉，
     * 免得熄屏期间白白闹醒设备（那时计时本来就不走）。
     */
    private fun armUsageDeadline(now: Long, active: Boolean) {
        if (!prefs.enabled || prefs.lockedUntilWall > now || !active) {
            KeepAlive.cancelDeadline(this)
            return
        }
        val remain = prefs.useMinutes * 60_000L - usedMsNow(now)
        if (remain > 1_000L) {
            KeepAlive.armDeadline(this, now + remain)
        } else {
            KeepAlive.cancelDeadline(this)
        }
    }

    // ==================================================================
    // 远程上报
    // ==================================================================

    /**
     * 重建"今日使用时长"和"本轮已用时长"。
     *
     * 口径：拿系统的前台应用事件算，再扣掉「强制休息 / 停用时段」那些
     * 被遮罩挡着、但前台应用没变的区间（见 [Prefs.offRanges]）。
     *
     * 只做算术、不做状态迁移 —— 休息、停用、清零都在别处做完，
     * 这里只回答"到现在为止用了多久"。
     */
    private fun refreshUsage(now: Long) {
        // 先对账：此刻没有任何遮罩，却还挂着一段"未闭合的不计入区间"，
        // 说明进程是在上次遮罩期间被杀掉的。不收尾的话，那段区间会一直
        // 延长到现在，把之后孩子正常玩的时间全扣成"被锁着干等"。
        if (prefs.lockedUntilWall <= now && !sInQuiet) prefs.closeOff(now)

        if (!Usage.hasPermission(this)) {
            // 没有"使用情况访问"就看不到事件，只能退回老的秒级累加。
            // 这个口径在速冻下会漏，但总比完全没有数字好。
            usageReady = false
            fallbackAccrue(now)
            return
        }

        // 首次启动 / 刚升级：本轮从此刻算起。
        // 设成"今天 0 点"会让刚升级的用户一打开就被判超时 —— 旧版本本来
        // 就漏记了一大截，重算出来的数一定超过上限，直接锁屏会把人吓一跳。
        if (prefs.roundStartWall <= 0L) prefs.roundStartWall = now

        // ★ 本轮起点比上次扫描还新 → 缓存已经作废，必须立刻重算。
        //
        // 漏了这一条的后果很具体：家长在遮罩上点「跳过」后 roundStartWall 被推到此刻，
        // 但 roundFromEvents 还是上一轮的"已用满 25 分钟"，下一 tick（1 秒内）
        // 立刻又判超时 → 遮罩刚撤掉又弹回来。孩子看到的是"点了跳过完全没用"。
        // 同理，"休息自然结束"也走这条路径，不修的话歇完 5 分钟一解锁又被锁一次。
        if (usageReady && now - usageScanAt < USAGE_SCAN_MS &&
            prefs.roundStartWall <= usageScanAt
        ) {
            return
        }

        val dayStart = Usage.localMidnight(now)
        val snap = runCatching { Usage.snapshot(this, dayStart, now) }.getOrNull()
        if (snap == null) {
            usageReady = false
            fallbackAccrue(now)
            return
        }

        val ex = prefs.offRanges(now)
        todayFromEvents = Usage.usedMs(snap.segs, dayStart, now, ex)
        roundFromEvents = Usage.usedMs(
            snap.segs,
            maxOf(dayStart, prefs.roundStartWall),
            now,
            ex,
            prefs.resetGapSeconds * 1000L,
        )
        usageScanAt = now
        usageReady = true
        prefs.todayUsedMs = todayFromEvents
        sTodayUsed = todayFromEvents
    }

    /**
     * 没有"使用情况访问"权限时的兜底：每秒累加。
     *
     * 保留它只是为了不至于完全没数字。它有两个已知缺陷（速冻/被杀会丢时间），
     * 所以**不是**权威口径 —— 有权限时一律走 [refreshUsage]。
     */
    private fun fallbackAccrue(now: Long) {
        val last = lastTickWall
        lastTickWall = now
        if (last <= 0L) return
        val dt = now - last
        if (dt <= 0L || dt > 5_000L) return
        if (prefs.lockedUntilWall > now) return
        if (sInQuiet) return
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        todayBufferMs += dt
        if (now - lastTodayFlush >= 5_000L) {
            prefs.todayUsedMs = prefs.todayUsedMs + todayBufferMs
            todayBufferMs = 0L
            lastTodayFlush = now
        }
    }

    /** 把还没落盘的累计时长写下去（心跳上报前调用） */
    private fun flushToday(now: Long) {
        if (usageReady) return
        if (todayBufferMs <= 0L) return
        prefs.todayUsedMs = prefs.todayUsedMs + todayBufferMs
        todayBufferMs = 0L
        lastTodayFlush = now
    }

    /** 每 [REMOTE_INTERVAL_MS] 发一次心跳（指令就是搭这趟车回来的） */
    private fun maybeRemote(now: Long) {
        if (now - lastRemoteBeat < REMOTE_INTERVAL_MS) return
        lastRemoteBeat = now
        if (!prefs.remoteConfigured()) return
        flushToday(now)
        Remote.heartbeat(this)
    }

    /**
     * 记录心跳。每 [HEARTBEAT_WRITE_MS] 写一次盘即可——
     * 判断"进程是否被杀"用不着秒级精度，写太频繁反而费电。
     */
    private fun writeHeartbeat(now: Long) {
        if (now - lastHeartbeatWrite < HEARTBEAT_WRITE_MS) return
        lastHeartbeatWrite = now
        prefs.heartbeatElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * 上次进程退出是不是"用户主动强行停止"（只有这种才算拆防护）。
     *
     * 优先用系统记录的退出原因；拿不到（Android 10 及以下）才退回
     * "保活闹钟被清空"这个弱判据。有系统记录时绝不能用弱判据 ——
     * 国产 ROM 清闹钟的原因太多了（重启、速冻、省电策略），
     * 每一种都会让违规次数往上涨，休息时长随之被 ×2 ×3 放大。
     */
    private fun userForceStopped(): Boolean {
        val info = prefs.lastExitInfo(this)
        if (info != null) {
            val (reason, ts) = info
            // 同一条系统记录只核算一次，否则每次启动都会被当成新的违规
            if (ts <= prefs.lastExitTs) return false
            prefs.lastExitTs = ts
            val userStopped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                    reason == ApplicationExitInfo.REASON_USER_STOPPED)
            if (!userStopped) {
                // 被系统清理 / 被速冻 / 崩溃，都不是人为，只记日志不发惩罚
                Log.i(TAG, "上次退出原因=$reason，非用户主动强停，不计违规")
            }
            return userStopped
        }
        return prefs.watchdogArmed && !KeepAlive.isWatchdogArmed(this)
    }

    /**
     * 服务启动时调用：判断守护进程是不是被强行结束过。
     *
     * 两个信号，任意一个成立即认定"被结束"。注意这两个信号**只用于诊断留痕**
     * （"守护中断了多久"），不再直接折算成违规次数 —— 它们分不清"人为结束"
     * 和"被省电策略干掉"，而后者在 OPPO/vivo 上每天都会发生。
     *
     *  **信号 A（主要）**：`prefs.serviceAlive` 仍为 true。
     *    正常退出会走 onDestroy 把标记清掉；进程被强杀时 onDestroy 根本不会执行，
     *    标记就残留下来了。这个信号**立刻有效**，不受心跳间隔长短影响。
     *
     *  **信号 B（辅助）**：心跳间隔异常。用 elapsedRealtime 判断——
     *    它开机归零，所以 nowElapsed < lastElapsed 说明是关机重启而不是被杀，
     *    这种情况必须忽略，否则每次开机都会误判成"孩子把孩子结束了"。
     */
    private fun detectKill(forceStopped: Boolean) {
        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastElapsed = prefs.heartbeatElapsed

        val uncleanExit = prefs.serviceAlive
        val rebooted = lastElapsed > 0L && nowElapsed < lastElapsed
        val gapMs = if (lastElapsed > 0L && !rebooted) nowElapsed - lastElapsed else 0L
        val gapKill = gapMs > KeepAlive.DEATH_THRESHOLD_MS

        // 记录"这次复活之前，守护已经中断了多久"。
        //
        // 这是判断保活通道到底灵不灵的关键指标：
        //   15 秒上下  → 主通道正常工作
        //   几分钟     → 主通道被省电策略延迟了，只有 5 分钟的强通道在工作
        //   几十分钟+  → 闹钟全废了，是被用户手动打开 App 才恢复的
        if (!rebooted && lastElapsed > 0L) {
            prefs.lastDowntimeMs = gapMs
        }

        if (forceStopped) {
            // 上一段已经留过痕、也记过账了，这里只补统计，不重复惩罚
        } else if (!rebooted && (uncleanExit || gapKill)) {
            val reason = when {
                uncleanExit -> getString(R.string.kill_reason_unclean)
                else -> getString(R.string.kill_reason_heartbeat_gap, gapMs / 60_000L)
            }
            // ★ 只留痕，**不计违规**。
            //
            // 这两个信号分不清"孩子强杀"和"被 ROM 清理 / 被速冻 / 内存回收"：
            // 后者在 OPPO、vivo 上一天能发生十几次。之前每发生一次就往违规次数上 +1，
            // 惩罚倍数很快升到 ×3 —— 家长设 5 分钟，孩子实际休息 15 分钟，
            // 看起来就像"服务器或 App 把时长算错了"。
            //
            // 真正的人为强停由 [userForceStopped] 用系统退出记录判定，不走这里。
            prefs.markKill(reason)
        }

        lastHeartbeatWrite = now
        prefs.heartbeatElapsed = nowElapsed
    }

    /**
     * 孩子强行结束进程后的惩罚策略。
     *
     * 这里**只记账、不当场执行**：被"强行停止"时进程根本不在运行，
     * 就算当场执行也没有任何东西能显示遮罩。账落到 [Prefs.pendingPenaltyMs] 后，
     * 由 [consumePendingPenalty] 在本进程随后的第一次 tick 里兑现
     * （正常情况下就是"App 被再次打开的那一秒"）。
     */
    private fun accrueTamperPenalty(now: Long, reason: String) {
        if (prefs.tamperPolicy != Prefs.TAMPER_REST) return
        if (prefs.lockedUntilWall > now) return      // 已经在休息，不叠账
        if (prefs.noteTamper(now, reason)) {
            Log.i(TAG, "记为惩罚欠账：$reason（第 ${prefs.tamperStrikes} 次）")
        }
    }

    /**
     * 兑现待执行的惩罚欠账。
     *
     * 放在 [step] 的最前面，意味着：只要进程活着、只要 tick 跑一次，
     * 欠账立刻变成一次真正的强制休息。即使欠账是在"应用被强停后重新打开"的
     * 第一次 tick 里被兑现，遮罩也会在同一秒建起来。
     */
    private fun consumePendingPenalty(now: Long) {
        // 顺序很重要：先判断"是不是已经在休息"，再取账。
        // 反过来的话，一笔欠账会在休息期间被静默取走又丢弃 —— 惩罚就白记了。
        if (prefs.lockedUntilWall > now) return
        val debt = prefs.takePenalty() ?: return
        enterRest(now, debt.second.ifEmpty { getString(R.string.rest_reason_tamper) }, debt.first)
    }

    /** 进入一次强制休息（[reason] 只用于日志，[ms] 便于惩罚按倍数加长） */
    private fun enterRest(now: Long, reason: String, ms: Long = prefs.restMinutes * 60_000L) {
        // 把"为什么休这么久"写到遮罩上。
        //
        // 惩罚休息的时长是「配置值 × 违规倍数」：家长配 5 分钟、孩子被记了 3 次，
        // 实际就是 15 分钟。只显示倒计时的话，家长只会认为"时长算错了"，
        // 所以这里必须把倍数和原因一起说清楚。
        val baseMs = prefs.restMinutes * 60_000L
        val factor = if (baseMs > 0) ((ms + baseMs / 2) / baseMs).toInt().coerceAtLeast(1) else 1
        prefs.restPlan = if (factor > 1) {
            getString(R.string.rest_plan_penalty, reason, factor, ms / 60_000L)
        } else {
            getString(R.string.rest_plan_plain, reason, ms / 60_000L)
        }

        prefs.commitRest(now + ms, prefs.restCount + 1, now)
        // 休息期间前台应用不变（遮罩是悬浮窗），系统照样把时间记给微信/抖音。
        // 记下这一段，算今日使用时长时扣掉。
        prefs.pushOffStart(now)

        sResting = true
        sRestLeftMs = ms
        overlayErrorNotified = false
        lastOverlayError = null
        Log.i(TAG, "进入强制休息：$reason，${ms / 60_000L} 分钟（基础 ${prefs.restMinutes} 分钟，×$factor）")
    }

    /**
     * 判断此刻孩子是否"正在使用"：屏幕点亮 且 已解锁。
     * 只有状态发生变化时才更新时间戳，避免反复写入磁盘。
     */
    private fun syncActivityState(now: Long): Boolean {
        val active = power.isInteractive && !keyguard.isKeyguardLocked
        if (active != lastActive) {
            if (active) onUseStart(now) else onUseEnd(now)
            lastActive = active
        }
        return active
    }

    private fun onUseStart(now: Long) {
        // 空闲超过阈值 → 上一轮的连续时长作废，重新开始算
        val gap = if (prefs.lastIdleWall > 0L) now - prefs.lastIdleWall else Long.MAX_VALUE
        val fresh = gap > prefs.resetGapSeconds * 1000L
        if (fresh) {
            prefs.sessionUsedMs = 0L
            prefs.roundStartWall = now
        }
        // ★ 本轮起点只在"这一轮确实断了"的时候重建。
        //
        // v2.4.0 及以前是**无条件**覆盖成"此刻"，注释说怕把进程不在的时间算进去。
        // 但这一刀切得太狠：进程被杀重启、tick 被速冻几十分钟后恢复，都会走到
        // 这里，覆盖一次就把刚过去那段真实使用抹掉了 —— 孩子杀一次后台就能
        // 白玩一小时。现在改为只在这两种情况下重建：空闲够久、或本来就没有起点。
        if (prefs.lockedUntilWall <= now && (fresh || prefs.sessionStartWall <= 0L)) {
            prefs.sessionStartWall = now
        }
    }

    private fun onUseEnd(now: Long) {
        val start = prefs.sessionStartWall
        if (start > 0L) {
            prefs.sessionUsedMs += (now - start).coerceAtLeast(0L)
        }
        prefs.sessionStartWall = 0L
        prefs.lastIdleWall = now
    }

    /** 当前这一轮已用的毫秒数（含正在进行的这一段） */
    private fun usedMsNow(now: Long): Long {
        if (usageReady) {
            // 起点在最近一次扫描之后被前移过（跳过休息 / 休息结束 / 停用结束 /
            // 家长清零）→ 缓存的 roundFromEvents 属于**上一轮**，本轮此刻就是 0。
            // 直接返回 0 而不是等下一次扫描：这一步是"跳过之后立刻又被锁"的最后一道防线。
            if (prefs.roundStartWall > usageScanAt) return 0L

            // 两次重建之间按墙钟外推。不外推的话通知栏的正计时会 20 秒才跳一次，
            // 看着像卡死；但只在"确实还在用"时外推，熄屏 / 休息 / 停用期间不加。
            val extra = if (now > usageScanAt &&
                prefs.lockedUntilWall <= now &&
                !sInQuiet &&
                power.isInteractive && !keyguard.isKeyguardLocked
            ) {
                now - usageScanAt
            } else {
                0L
            }
            val guess = roundFromEvents + extra
            // 硬不变量：本轮"已用"不可能超过"本轮起点到现在"的墙钟时长。
            // 任何缓存/外推写错都会被这一句夹住，代价最多是显示偏小一秒，
            // 但绝不会因为一个旧数字把已经放行的孩子重新锁回去。
            val cap = if (prefs.roundStartWall > 0L) {
                (now - prefs.roundStartWall).coerceAtLeast(0L)
            } else {
                guess
            }
            return minOf(guess, cap)
        }
        // 没权限 → 老的"起点到现在的墙钟差"口径
        val running = if (prefs.sessionStartWall > 0L && now > prefs.sessionStartWall) {
            now - prefs.sessionStartWall
        } else {
            0L
        }
        return prefs.sessionUsedMs + running
    }

    private fun startRest(now: Long) {
        enterRest(now, getString(R.string.rest_reason_usage_limit))
        // 已经进休息了，到点闹钟就没有意义了，立刻撤掉
        KeepAlive.cancelDeadline(this)
        ensureOverlay()
        if (overlayView == null) ensureLockActivity()
        renderOverlay(sRestLeftMs)
        refreshNotification()
    }

    /** 结束休息：撤掉遮罩、放开音频焦点、通知兜底界面退出 */
    private fun endRest() {
        sResting = false
        sRestLeftMs = 0L
        Silence.endRest(this, prefs)
        removeOverlay()
        runCatching {
            sendBroadcast(Intent(ACTION_REST_END).setPackage(packageName))
        }
    }

    // ==================================================================
    // 停用时段（宵禁）
    // ==================================================================

    /**
     * 进入一段停用。
     *
     * 只做三件事：清掉"本轮已用时长"、记下起点、提示一次。
     * **不要**动 [Prefs.lockedUntilWall] —— 那是"强制休息"的状态，
     * 两套倒计时混在一起的话，停用结束时会莫名其妙留下一个休息倒计时。
     */
    private fun enterQuiet(now: Long, hit: Quiet.Hit) {
        val label = hit.label(this)
        prefs.quietEnteredWall = now
        // 停用期间不该再累计使用时长：把这一轮清空，结束之后从头算。
        // 不清的话，孩子熬完宵禁一解锁就立刻被判"已用满 X 分钟"，马上又被锁。
        prefs.sessionUsedMs = 0L
        prefs.sessionStartWall = 0L
        prefs.lastIdleWall = now
        prefs.roundStartWall = now
        // 停用期间前台应用没变（遮罩是悬浮窗），系统仍然记着"在用微信"。
        // 开一段"不计入"的区间，算使用时长时把它扣掉。
        prefs.pushOffStart(now)
        sUsing = false
        sUsedMs = 0L

        // 一段只提示一次：进程被杀重启时 sInQuiet 会归零，
        // 光靠它去重会让同一段停用反复弹提示，所以再用时间戳兜一道。
        if (now - prefs.quietNotifiedWall > 60_000L) {
            prefs.quietNotifiedWall = now
            toast(getString(R.string.quiet_started, label))
        }
        Log.i(TAG, "进入停用时段：$label，还剩 ${hit.leftMinutes} 分钟")
    }

    /** 离开停用（到点放开，或家长提前放行） */
    private fun exitQuiet(now: Long, notify: Boolean) {
        sInQuiet = false
        sQuietLeftMs = 0L
        sQuietLabel = ""
        prefs.quietEnteredWall = 0L
        prefs.quietNotifiedWall = 0L
        // 放开的那一刻重新开始计时，理由同 enterQuiet：不要让旧账立刻触发休息
        prefs.sessionUsedMs = 0L
        prefs.lastIdleWall = now
        prefs.roundStartWall = now
        prefs.sessionStartWall =
            if (power.isInteractive && !keyguard.isKeyguardLocked) now else 0L
        // 停用段收尾，之后的时间才算"在用"
        prefs.closeOff(now)
        // 如果同时还在"强制休息"里，遮罩要留着，由下一轮按休息模式重绘
        if (prefs.lockedUntilWall <= now) endRest()
        if (notify) toast(getString(R.string.quiet_ended))
        Log.i(TAG, "退出停用时段")
    }

    /**
     * 排"停用边界"闹钟。
     *
     * 停用中 → 排"放开那一刻"；不在停用中 → 排"下一段开始的那一刻"。
     * 只在需要时排一条，15 秒内的重复调用会被 [KeepAlive.armQuiet] 挡掉。
     */
    private fun armQuietBoundary(now: Long, inQuiet: Boolean) {
        if (!prefs.enabled || prefs.quietRules().none { it.enabled }) {
            KeepAlive.cancelQuiet(this)
            return
        }
        val at = if (inQuiet) {
            // 倒计时是按分钟算的，对齐到整分钟才是真正的边界时刻
            (now / 60_000L) * 60_000L + sQuietLeftMs
        } else {
            Quiet.nextStartAt(prefs, now)
        }
        if (at > now) KeepAlive.armQuiet(this, at) else KeepAlive.cancelQuiet(this)
    }

    /**
     * 家长在遮罩上点「提前结束本次停用」。
     *
     * 只放行**这一轮**：把截止时刻记成本段结束的时刻，到点后规则照常生效。
     * 做成永久关闭的话，家长某晚临时放行一次，宵禁就再也不会锁了。
     */
    private fun skipQuiet(now: Long) {
        // 用规则**现算**这一段的结束时刻，不要依赖 [sQuietLeftMs]。
        // 它是进程内的静态字段：服务刚被系统拉起、还没跑满一轮 tick 时它是 0，
        // 那样算出来的"放行到"就等于此刻，家长点完立刻又锁 —— 看起来就是按钮失灵。
        // 取整到分钟也一并去掉：直接 now + 剩余毫秒，边界不会提前几十秒失效。
        val hit = Quiet.current(this, prefs, now)
        prefs.quietSkipUntilWall = hit?.let { now + it.leftMs } ?: prefs.quietSkipUntilWall
        exitQuiet(now, notify = false)
        toast(getString(R.string.quiet_skipped))
        // 立刻重判一次，遮罩才能在这一秒就撤掉
        step()
    }

    // ==================================================================
    // 一级遮罩：系统悬浮窗
    // ==================================================================

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    /**
     * 取用于添加悬浮窗的 WindowManager。
     *
     * 关键点：直接用 Service 的 getSystemService(WINDOW_SERVICE) 拿到的
     * WindowManager 绑在**非可视上下文**上，在国产 ROM 上 addView 很容易
     * 抛 BadTokenException / 权限异常。Android 11+ 的正确做法是用
     * createWindowContext(TYPE_APPLICATION_OVERLAY) 拿一个专用窗口上下文。
     */
    private fun obtainOverlayWindowManager(): WindowManager? {
        overlayWm?.let { return it }

        val wm: WindowManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wc = createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                )
                overlayWindowContext = wc
                wc.getSystemService(WindowManager::class.java)
            } catch (t: Throwable) {
                Log.e(TAG, "createWindowContext 失败，退回默认 WindowManager", t)
                getSystemService(WindowManager::class.java)
            }
        } else {
            getSystemService(WindowManager::class.java)
        }

        overlayWm = wm
        return wm
    }

    private fun ensureOverlay() {
        if (overlayView != null) return
        if (!canDrawOverlay()) {
            lastOverlayError = "未获得悬浮窗权限(Settings.canDrawOverlays=false)"
            return
        }
        val wm = obtainOverlayWindowManager() ?: run {
            lastOverlayError = "无法获取 WindowManager"
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_lock, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 注意：故意不加 FLAG_NOT_FOCUSABLE —— 保持可聚焦，才能吃掉返回键
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.TOP or Gravity.START

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 吃掉返回键，防止孩子用返回键退出；
        // 顺带吃掉音量键——休息期间不该再调声音，否则"兜底静音"会被一键调回来
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        }
        view.requestFocus()

        RestUi.bind(
            root = view,
            prefs = prefs,
            onSkip = { skipRest() },
            onPauseGuard = { disableGuard() },
            onMessage = { toast(it) }
        )

        try {
            wm.addView(view, params)
            overlayView = view
            sOverlayShown = true
            lastOverlayError = null
        } catch (t: Throwable) {
            overlayView = null
            sOverlayShown = false
            lastOverlayError = t.javaClass.simpleName + ": " + (t.message ?: "无详细信息")
            Log.e(TAG, "悬浮窗 addView 失败", t)
            if (!overlayErrorNotified) {
                overlayErrorNotified = true
                toast(getString(R.string.warn_overlay_failed, lastOverlayError ?: ""))
            }
            // 换一个干净的 WindowManager 下次重试
            overlayWm = null
        }
    }

    /**
     * 重绘遮罩。
     *
     * [mode] 决定文案：休息说"你用了太久"，停用说"现在不是玩手机的时间"。
     * 同一套布局、同一套 PIN 校验，只有这层文案不一样 —— 见 [RestUi.applyMode]。
     */
    private fun renderOverlay(leftMs: Long, mode: RestUi.Mode = RestUi.Mode.REST) {
        val v = overlayView ?: return
        val plan = if (mode == RestUi.Mode.QUIET) {
            getString(R.string.quiet_plan, sQuietLabel)
        } else {
            prefs.restPlan
        }
        RestUi.render(v, this, leftMs, prefs.restCount, plan, mode)
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        overlayView = null
        sOverlayShown = false
        runCatching { overlayWm?.removeView(v) }
    }

    // ==================================================================
    // 二级兜底：全屏 LockActivity
    // ==================================================================

    private fun ensureLockActivity() {
        // 屏幕没亮时不要去拉 Activity，否则可能把屏幕点亮
        if (!power.isInteractive) return
        if (LockActivity.isForeground) return
        runCatching {
            startActivity(
                Intent(this, LockActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        }.onFailure { t ->
            lastOverlayError = "LockActivity: " + (t.message ?: t.javaClass.simpleName)
            Log.e(TAG, "拉起 LockActivity 失败", t)
        }
    }

    // ==================================================================
    // 家长操作
    // ==================================================================

    /** 家长验证通过：跳过本次休息 / 放行本轮停用 */
    private fun skipRest() {
        val now = System.currentTimeMillis()
        if (sInQuiet) {
            skipQuiet(now)
            return
        }
        prefs.lockedUntilWall = 0L
        prefs.sessionUsedMs = 0L
        prefs.lastIdleWall = now
        prefs.sessionStartWall = if (lastActive) now else 0L
        prefs.roundStartWall = now
        prefs.closeOff(now)
        endRest()
        toast(getString(R.string.rest_skipped))
    }

    /** 家长验证通过：暂停守护 */
    private fun disableGuard() {
        prefs.enabled = false
        prefs.resetSession()
        prefs.lockedUntilWall = 0L
        // 暂停时可能还在遮罩里：把"不计入"的区间收尾，免得它一直开着，
        // 之后孩子正常用手机的时间被当成"被锁着"扣掉
        prefs.closeOff(System.currentTimeMillis())
        sInQuiet = false
        sQuietLeftMs = 0L
        sQuietLabel = ""
        KeepAlive.cancelQuiet(this)
        toast(getString(R.string.guard_paused))
        teardown()
    }

    // ==================================================================
    // 远程指令（由 Remote 在主线程调用）
    // ==================================================================

    /**
     * 执行一条后台下发的指令，返回（是否成功，给家长看的一句话）。
     *
     * 必须在主线程执行：建悬浮窗、写 Prefs、改音量都要求主线程。
     * [Remote] 已经保证了这一点，这里不再自己切线程。
     */
    private fun handleRemote(type: String, p: JSONObject): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        return try {
            when (type) {
                "lock" -> {
                    val minutes = p.optInt("minutes", prefs.restMinutes).coerceIn(1, 240)
                    enterRest(now, getString(R.string.rest_reason_remote), minutes * 60_000L)
                    ensureOverlay()
                    if (overlayView == null) ensureLockActivity()
                    renderOverlay(sRestLeftMs)
                    refreshNotification()
                    true to "已锁定 $minutes 分钟"
                }

                "unlock" -> {
                    if (prefs.lockedUntilWall <= now) {
                        true to "当前不在休息，无需解锁"
                    } else {
                        val active = power.isInteractive && !keyguard.isKeyguardLocked
                        prefs.lockedUntilWall = 0L
                        prefs.sessionUsedMs = 0L
                        prefs.lastIdleWall = now
                        prefs.sessionStartWall = if (active) now else 0L
                        prefs.roundStartWall = now
                        prefs.closeOff(now)
                        endRest()
                        true to "已解除锁定"
                    }
                }

                // 家长在后台把「拆防护」计数归零：惩罚倍数回到 ×1，
                // 之后的休息时长就等于配置值。误判累积上去后必须有这个出口。
                "reset_tamper" -> {
                    val before = prefs.tamperStrikes
                    prefs.clearTamper()
                    GuardianService.refresh(this)
                    true to "已清零违规计数（原 $before 次），休息时长恢复为 ${prefs.restMinutes} 分钟"
                }

                // 家长在后台改「停用时段」：整份规则替换
                "quiet" -> {
                    val arr = p.optJSONArray("rules")
                        ?: return false to "缺少停用规则（rules）"
                    val parsed = Quiet.parse(arr)
                    if (arr.length() > 0 && parsed.isEmpty()) {
                        return false to "规则格式不对，已拒绝应用（本地规则保持不变）"
                    }
                    prefs.quietRulesRaw = Quiet.toJson(parsed)
                    // 规则换了，之前的"本轮放行"自然作废
                    prefs.quietSkipUntilWall = 0L
                    prefs.quietNotifiedWall = 0L
                    // 立刻重判：可能马上要锁上，也可能正在锁着而现在该放开
                    step()
                    val on = parsed.count { it.enabled }
                    if (on == 0) {
                        KeepAlive.cancelQuiet(this)
                        true to "停用时段已更新：没有启用的规则，设备不会锁屏"
                    } else {
                        armQuietBoundary(now, inQuiet = sInQuiet)
                        true to "停用时段已更新：$on 条生效"
                    }
                }

                "pause" -> {
                    prefs.enabled = false
                    prefs.resetSession()
                    prefs.lockedUntilWall = 0L
                    sInQuiet = false
                    sQuietLeftMs = 0L
                    sQuietLabel = ""
                    KeepAlive.cancelQuiet(this)
                    endRest()
                    if (prefs.remoteConfigured()) {
                        // 保持远程待命，家长还能再远程开回来
                        refreshNotification()
                        true to "守护已暂停（保持远程待命）"
                    } else {
                        teardown()
                        true to "守护已暂停"
                    }
                }

                "enable" -> {
                    val already = prefs.enabled
                    prefs.enabled = true
                    if (!already) {
                        prefs.resetSession()
                        prefs.lockedUntilWall = 0L
                        prefs.lastIdleWall = now
                        prefs.sessionStartWall =
                            if (power.isInteractive && !keyguard.isKeyguardLocked) now else 0L
                        lastActive = power.isInteractive && !keyguard.isKeyguardLocked
                    }
                    if (!tickerRunning) {
                        tickerRunning = true
                        handler.post(tick)
                    }
                    if (!startForegroundSafely()) {
                        return false to "前台服务起不来（被系统的后台启动限制拦了），请手动打开一次应用"
                    }
                    running = true
                    KeepAlive.armWatchdog(this)
                    prefs.watchdogArmed = true
                    KeepAlive.ensurePeriodicJob(this)
                    refreshNotification()
                    true to if (already) "守护本来就在运行" else "守护已开启"
                }

                "config" -> {
                    val applied = ArrayList<String>()
                    if (p.has("use_minutes")) {
                        prefs.useMinutes = p.optInt("use_minutes", prefs.useMinutes).coerceIn(1, 600)
                        applied.add("使用上限 ${prefs.useMinutes} 分钟")
                    }
                    if (p.has("rest_minutes")) {
                        prefs.restMinutes = p.optInt("rest_minutes", prefs.restMinutes).coerceIn(1, 240)
                        applied.add("休息 ${prefs.restMinutes} 分钟")
                    }
                    if (p.has("reset_gap_seconds")) {
                        prefs.resetGapSeconds =
                            p.optInt("reset_gap_seconds", prefs.resetGapSeconds).coerceIn(30, 3600)
                        applied.add("重计阈值 ${prefs.resetGapSeconds} 秒")
                    }
                    if (p.has("tamper_policy")) {
                        val on = p.optBoolean("tamper_policy", false)
                        // 与界面上的约束保持一致：没设家长密码时不能开惩罚，
                        // 否则一旦触发休息，家长自己也被关在遮罩外面
                        if (on && prefs.pin.isEmpty()) {
                            return false to "设备还没设置家长密码，不能开启强停惩罚"
                        }
                        prefs.tamperPolicy = if (on) Prefs.TAMPER_REST else Prefs.TAMPER_OFF
                        applied.add(if (on) "强停惩罚开" else "强停惩罚关")
                    }
                    if (p.has("pause_media_on_rest")) {
                        prefs.pauseMediaOnRest = p.optBoolean("pause_media_on_rest", true)
                        applied.add(if (prefs.pauseMediaOnRest) "休息暂停播放开" else "休息暂停播放关")
                    }
                    if (p.has("mute_on_rest")) {
                        prefs.muteOnRest = p.optBoolean("mute_on_rest", false)
                        applied.add(if (prefs.muteOnRest) "静音兜底开" else "静音兜底关")
                    }
                    if (applied.isEmpty()) {
                        false to "没有可修改的参数"
                    } else {
                        refreshNotification()
                        true to "已应用：" + applied.joinToString("，")
                    }
                }

                "sync" -> true to "已同步"

                else -> false to "不支持的指令：$type"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "执行远程指令失败：$type", t)
            false to (t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    // ==================================================================
    // 通知栏
    // ==================================================================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /**
     * 构造常驻通知。
     *
     * ## 关键：时间用系统的 Chronometer 走，不靠我们每秒 notify
     *
     * 原来是把"已用 02:15"写死在文本里、每秒 notify 一次。这有两个问题：
     *
     *  1. 一旦进程被 ROM 冻结（tick 停摆），文本就永远停在那一刻 ——
     *     用户看到的就是"倒计时两三秒后不动了"，而且很难判断是流程卡了还是进程被冻了；
     *  2. 国产 ROM 对同一通知的高频更新有节流，每秒一次本身就不被欢迎。
     *
     * 改成 Chronometer 之后，秒数是 **SystemUI 自己**刷的，进程被冻结也照走，
     * 而且系统一秒钟只重绘一次由它统一完成，反而更省。
     *
     * - 休息中：倒计时（`setChronometerCountDown` + `when = 休息结束时刻`），绝对准确 ——
     *   休息时长本来就是按墙钟算的。
     * - 使用中：正计时（`when = 现在 - 本轮已用`）。熄屏会暂停计时，此时
     *   退回纯文本，避免通知栏比真实时长走得更快。
     */
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = System.currentTimeMillis()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)

        when {
            // ---------- 停用时段：系统倒计时 ----------
            sInQuiet && sQuietLeftMs > 0L -> {
                b.setContentText(getString(R.string.quiet_notif))
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(now + sQuietLeftMs)
                    .setShowWhen(true)
            }

            // ---------- 正在强制休息：系统倒计时 ----------
            sResting && prefs.lockedUntilWall > now -> {
                b.setContentText(getString(R.string.notif_resting_chrono))
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(prefs.lockedUntilWall)
                    .setShowWhen(true)
            }

            // ---------- 守护关闭 ----------
            !prefs.enabled -> {
                b.setContentText(
                    getString(
                        if (prefs.remoteConfigured()) R.string.notif_standby
                        else R.string.notif_paused
                    )
                ).setShowWhen(false)
            }

            // ---------- 正在使用：系统正计时 ----------
            sUsing -> {
                b.setContentText(getString(R.string.notif_using_chrono, prefs.useMinutes))
                    .setUsesChronometer(true)
                    .setWhen(now - sUsedMs)
                    .setShowWhen(true)
            }

            // ---------- 熄屏暂停：纯文本（计时本来就不走） ----------
            else -> {
                b.setContentText(
                    getString(R.string.notif_using_paused, RestUi.fmt(sUsedMs), prefs.useMinutes)
                ).setShowWhen(false)
            }
        }

        return b.build()
    }

    /**
     * 推送通知。
     *
     * 用"指纹"去重：内容没变化（比如整分钟以内只有 Chronometer 在走）就不再 notify，
     * 这样既不给 ROM 的通知节流送人头，也不影响系统自己刷新秒数。
     */
    private fun refreshNotification() {
        runCatching {
            val now = System.currentTimeMillis()
            val key = when {
                sInQuiet -> "quiet:${sQuietLabel}"
                sResting && prefs.lockedUntilWall > now -> "rest:${prefs.lockedUntilWall / 1000}"
                !prefs.enabled -> if (prefs.remoteConfigured()) "standby" else "off"
                sUsing -> "use"
                else -> "idle:${sUsedMs / 1000}"
            }
            if (key == lastNotifKey) return
            lastNotifKey = key
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    /**
     * 把服务提升为前台。
     *
     * @return 是否成功。失败时**调用方必须马上 stopSelf()**：
     *   停在"已 startForegroundService、却没 startForeground"的中间态，
     *   系统会在几秒后以 RemoteServiceException 结束整个进程。
     */
    private fun startForegroundSafely(): Boolean {
        val n = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败", t)
            // 最常见的失败是 Android 12+ 的后台前台服务启动限制。
            // 记下来，家长在诊断区能看到，就不用猜"为什么复活了却没有通知"。
            prefs.lastStartError =
                "startForeground: " + t.javaClass.simpleName + " " + (t.message ?: "")
            false
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private fun rolloverDayIfNeeded() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.restDay != today) {
            prefs.restDay = today
            prefs.restCount = 0
        }
        // 今日使用累计也要跨天归零，否则后台的"今日时长"会越滚越大
        if (prefs.todayDay != today) {
            prefs.todayDay = today
            prefs.todayUsedMs = 0L
            todayBufferMs = 0L
            todayFromEvents = 0L
            roundFromEvents = 0L
            usageScanAt = 0L
            sTodayUsed = 0L
            // 昨天的"不计入区间"和新的一天无关；本轮也从头算
            prefs.resetOffRanges()
            prefs.roundStartWall = 0L
        }
    }

    private fun toast(msg: String) {
        runCatching {
            handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val CHANNEL_ID = "guardian_watch"
        private const val NOTIF_ID = 1001
        private const val TICK_MS = 1000L

        /** 向服务器发心跳的间隔（也是指令下发的最大延迟） */
        private const val REMOTE_INTERVAL_MS = 20_000L

        /** 心跳写盘间隔 */
        private const val HEARTBEAT_WRITE_MS = 5_000L

        /**
         * 使用时长从系统事件重建的间隔。
         *
         * 为什么不是每秒：`queryEvents` 要把今天的事件读一遍（几百到几千条），
         * 每秒跑一次纯属浪费电。20 秒足够 —— 中间的空档由 [usedMsNow] 按墙钟
         * 外推，通知栏的正计时不会看起来卡住。
         */
        private const val USAGE_SCAN_MS = 20_000L

        /**
         * 两次 tick 间隔超过这个值，就记一次"后台停滞"。
         *
         * 正常是 1 秒；GC、主线程偶尔被占可能到 1.x 秒，所以留到 3 秒才记，
         * 免得统计里全是噪声。真正被冻结时通常是几十秒到几分钟。
         */
        private const val STALL_THRESHOLD_MS = 3_000L

        const val ACTION_START = "com.huajie.guardian.action.START"
        const val ACTION_STOP = "com.huajie.guardian.action.STOP"
        const val ACTION_REFRESH = "com.huajie.guardian.action.REFRESH"
        const val ACTION_TEST_REST = "com.huajie.guardian.action.TEST_REST"

        /** 心跳 / 复活闹钟统一用这个 action 唤醒服务 */
        const val ACTION_HEARTBEAT = "com.huajie.guardian.action.HEARTBEAT"

        /**
         * 「使用上限到点」闹钟专用。
         *
         * 与 [ACTION_HEARTBEAT] 分开只是为了诊断区能分辨"这次是被什么叫醒的"：
         * 是常规 15 秒自检，还是到点闹钟（说明当时 tick 已经停摆、靠系统兜住了）。
         */
        const val ACTION_DEADLINE = "com.huajie.guardian.action.DEADLINE"

        /**
         * 「停用时段边界」闹钟专用：22:00 到点自动锁、06:00 到点自动放开。
         *
         * 单独一个 action 是为了诊断区能分辨"这次是被停用闹钟叫醒的" ——
         * 家长最关心的就是"我说的 22 点，到底准不准"。
         */
        const val ACTION_QUIET = "com.huajie.guardian.action.QUIET"

        /** 休息结束时广播，通知兜底界面退出 */
        const val ACTION_REST_END = "com.huajie.guardian.action.REST_END"

        // ---- 供界面读取的运行时状态 ----
        /**
         * 本进程内是否已经做过"是否被强行结束"的判断。
         * 进程级静态量：进程重启即归零，正好用来标记"这是一个新进程"。
         */
        @Volatile private var sCheckedKillInThisProcess = false

        /** 服务是否存活 */
        @Volatile var running = false

        /** 是否处于"正在使用"状态 */
        @Volatile var sUsing = false

        /** 是否正在强制休息 */
        @Volatile var sResting = false

        /** 休息剩余毫秒 */
        @Volatile var sRestLeftMs = 0L

        /** 是否正处于「停用时段」 */
        @Volatile var sInQuiet = false

        /** 停用剩余毫秒 */
        @Volatile var sQuietLeftMs = 0L

        /** 当前停用时段的描述（"每天 22:00–06:00"），上报给后台看 */
        @Volatile var sQuietLabel = ""

        /** 本轮已使用毫秒 */
        @Volatile var sUsedMs = 0L

        /**
         * 今日累计使用毫秒（从系统事件重算出来的权威值）。
         *
         * 以前这个数只在服务内部累加、由 Prefs 转手给 [Remote] 上报，
         * 现在算完直接放这儿，心跳读它就行，不用再各自维护一份。
         */
        @Volatile var sTodayUsed = 0L

        /** 一级遮罩（系统悬浮窗）当前是否已挂上 */
        @Volatile var sOverlayShown = false

        /** 最近一次遮罩失败的原因，用于界面诊断 */
        @Volatile var lastOverlayError: String? = null

        /**
         * 当前存活的实例。
         *
         * 远程指令要改的是"服务正在跑的这一份状态"（遮罩、计时、保活），
         * 所以必须拿到实例，不能用静态字段另起一套。
         */
        @Volatile
        private var instance: GuardianService? = null

        /**
         * 远程指令的统一入口（由 [Remote] 在主线程调用）。
         *
         * 服务刚好被系统回收时如实回执失败，而不是假装成功 ——
         * 家长在后台看到"失败：守护服务没有在运行"，比看到"已锁定"却毫无反应要好。
         */
        fun applyRemote(type: String, params: JSONObject): Pair<Boolean, String> {
            val svc = instance
                ?: return false to "守护服务没有在运行（可能刚被系统回收），稍后重试"
            return svc.handleRemote(type, params)
        }

        /**
         * 遮罩上点「跳过」的统一入口。
         *
         * 为什么要有它：[LockActivity] 是"悬浮窗加不上"时的兜底界面，它原先自己
         * 复制了一份跳过逻辑（清 prefs、设 sResting=false、finish 自己），服务那边
         * 有另一份 `skipRest()`。两份逻辑越走越偏，实测的后果是：
         *   · 兜底界面里漏了 sessionStartWall 的重建 → 没有"使用情况访问"权限时
         *     下一 tick 立刻按"起点到现在的墙钟差"判超时，遮罩又弹回来；
         *   · 服务不知道遮罩已经跳过，该撤的悬浮窗、该放的音频焦点都没撤。
         * 现在两边都走这里：服务活着就交给服务，服务不在才按最小集直接改状态。
         */
        fun skipFromMask(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.skipRest()
                return
            }
            // 服务不在运行（极少数：进程刚被回收、遮罩还没淡出）。
            // 只改持久化状态，下次启动时 onUseStart / refreshUsage 会自己对齐。
            val p = Prefs(ctx)
            val now = System.currentTimeMillis()
            val hit = Quiet.current(ctx, p, now)
            if (hit != null) {
                p.quietSkipUntilWall = now + hit.leftMs
                p.closeOff(now)
                p.roundStartWall = now
            } else {
                p.lockedUntilWall = 0L
                p.sessionUsedMs = 0L
                // 置 0 而不是置 now：下次 onUseStart 会按"刚开始用"重建，
                // 这样没有使用记录权限时也不会把空档算成使用时长。
                p.sessionStartWall = 0L
                p.lastIdleWall = now
                p.roundStartWall = now
                p.closeOff(now)
            }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, GuardianService::class.java).setAction(ACTION_START)
            // 必须吞掉异常：调用方可能是闹钟触发的 JobService 或广播接收器，
            // 一旦抛出去，整条保活链路就断了，而且家长什么都看不到。
            runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { t ->
                Log.e(TAG, "启动守护服务失败", t)
                Prefs(ctx).lastStartError =
                    "启动服务: " + t.javaClass.simpleName + " " + (t.message ?: "")
            }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, GuardianService::class.java).setAction(ACTION_STOP))
            }
        }

        fun refresh(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, GuardianService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
