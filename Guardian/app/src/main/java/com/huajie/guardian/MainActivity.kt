package com.huajie.guardian

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 家长设置界面。
 * 设了密码之后，进入本界面必须先验证密码，防止孩子自己改设置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvStatus: TextView
    private lateinit var tvDiag: TextView
    private lateinit var swEnable: SwitchCompat
    private lateinit var tvPinState: TextView
    private lateinit var tvOverlayState: TextView
    private lateinit var tvNotifState: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnNotif: Button
    private lateinit var btnBattery: Button
    private lateinit var tvAdminState: TextView
    private lateinit var btnAdmin: Button
    private lateinit var swTamperRest: SwitchCompat
    private lateinit var tvTamperHint: TextView
    private lateinit var btnClearTamper: Button
    private lateinit var swPauseMedia: SwitchCompat
    private lateinit var swMuteRest: SwitchCompat
    private lateinit var tvMediaAccessState: TextView
    private lateinit var btnMediaAccess: Button
    private lateinit var tvRemoteState: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etPairCode: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var btnPair: Button
    private lateinit var btnRemoteSync: Button
    private lateinit var btnRemoteUnbind: Button
    private lateinit var btnCrashLog: Button

    // ---- 主界面 / 设置页 ----
    private lateinit var homeView: View
    private lateinit var settingsView: View
    private lateinit var tvToday: TextView
    private lateinit var pbUsage: ProgressBar
    private lateinit var tvUsageState: TextView

    /** 当前是不是在「设置页」（返回键据此决定回主界面还是退出） */
    private var inSettings = false

    /** 「使用情况访问」权限查得太勤没意义，10 秒缓存一次 */
    private var usagePermAt = 0L
    private var usagePermOk = false

    private val handler = Handler(Looper.getMainLooper())
    private var uiReady = false

    /** 自动重启服务的尝试次数，避免服务真的起不来时每秒钟狂刷 */
    private var startAttempts = 0

    /**
     * 是否已通过家长密码验证。
     * 没验证之前，设置内容整体隐藏（`setContentVisible(false)`），只能看到密码框。
     */
    private var unlocked = false

    /** 当前弹出的密码框，用来避免重复弹 */
    private var pinDialog: AlertDialog? = null

    /** 上锁期间铺在 DecorView 上的"已上锁"遮片（见 [setLockHintVisible]） */
    private var lockHint: View? = null

    /** 上次崩溃的一行摘要（setupUi 时读一次，之后不再读盘） */
    private var crashLine = ""

    /**
     * 家长自己点「去设置」跳系统界面时记下的时刻（elapsedRealtime），0 表示当前没有跳转。
     *
     * 这类跳转会导致本页面 onStop，但它是家长主动发起的，
     * 回来时再要一次密码太折腾（光授权就得来回输四五次），所以放行一次。
     * 用时间戳而不是布尔值：标记会自己过期，绝不会留在那里把下一次真正的"隐藏"也放过去。
     */
    private var externalJumpAtElapsed = 0L

    /** 上面那种"主动跳转"的宽限截止时刻；超时回来照样重新验证 */
    private var suppressRelockUntilElapsed = 0L

    private val uiTick = object : Runnable {
        override fun run() {
            ensureServiceRunning()
            // ★ 上锁期间一律不碰界面控件。
            //
            // 设了家长密码时，onCreate 只弹密码框、**不执行 setupUi()**，
            // 所有 lateinit 控件都还没赋值；而 onResume 里的 uiTick 是立刻跑的
            // （不需要等密码）。少了这个判断，第一次 tick 就会抛
            // UninitializedPropertyAccessException —— 表现就是"点开就闪退"。
            if (unlocked && uiReady) {
                renderStatus()
                renderHome()
                // 设置页里的诊断/权限/远程状态没必要每秒刷 —— 主界面看不到它们
                if (inSettings) {
                    renderDiag()
                    refreshPermissions()
                    renderRemote()
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // 有密码就先把整个设置界面藏起来，验证通过后再显示，
        // 免得孩子隔着密码框把设置内容看个遍
        if (prefs.pin.isNotEmpty()) {
            setContentVisible(false)
            promptPin()
        } else {
            unlocked = true
            setupUi()
        }
    }

    override fun onResume() {
        super.onResume()

        // 家长主动跳去系统设置页后回来：短时间内视为同一次操作，不重新验证；
        // 但在设置里待太久（或中途设备被别人拿走），回来照样锁上。
        if (unlocked && suppressRelockUntilElapsed > 0L &&
            SystemClock.elapsedRealtime() > suppressRelockUntilElapsed
        ) {
            unlocked = false
        }
        suppressRelockUntilElapsed = 0L

        // ★ v1.5 修复的关键点：
        // 「隐藏」(Home / 切到别的应用 / 进最近任务) 并不会销毁 Activity，
        // 重新打开只走 onStart → onResume，onCreate 不会再来一次。
        // 而原来密码框只在 onCreate 里弹，于是隐藏再打开永远不会要求输密码，
        // 孩子拿到设备从最近任务一点就能直接改设置。
        // 所以每次回到前台都必须自己判断一次。
        maybeRelock()

        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiTick)
    }

    override fun onStop() {
        super.onStop()
        // 只要离开前台就上锁：孩子从「最近任务」把应用调回来，也必须重新输密码。
        // 唯一例外是家长自己点的「去设置」授权跳转，那种来回不应该反复要密码。
        val justJumpedOut = externalJumpAtElapsed > 0L &&
            SystemClock.elapsedRealtime() - externalJumpAtElapsed < JUMP_WINDOW_MS
        externalJumpAtElapsed = 0L

        if (justJumpedOut) {
            suppressRelockUntilElapsed = SystemClock.elapsedRealtime() + EXTERNAL_GRACE_MS
        } else if (prefs.pin.isNotEmpty()) {
            unlocked = false
            // 离开的一瞬间就把设置内容藏起来：等下次 onResume 再藏，
            // 中间会漏出一帧完整的设置界面，孩子从最近任务预览里就能看到。
            setContentVisible(false)
        }
    }

    /**
     * 用户主动离开本页面（按 Home 键、按最近任务键、切到别的应用）时会先走这里。
     *
     * 它比 onStop 更精确：我们自己 startActivity 跳去系统设置页**不会**触发它，
     * 而"按最近任务键"这种场景在某些 ROM 上只走 onPause + 这里、不走 onStop。
     * 两处都上锁，覆盖得更全。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.pin.isNotEmpty()) {
            unlocked = false
            setContentVisible(false)
        }
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    private fun setupUi() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDiag = findViewById(R.id.tvDiag)
        swEnable = findViewById(R.id.swEnable)
        tvPinState = findViewById(R.id.tvPinState)
        tvOverlayState = findViewById(R.id.tvOverlayState)
        tvNotifState = findViewById(R.id.tvNotifState)
        tvBatteryState = findViewById(R.id.tvBatteryState)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnNotif = findViewById(R.id.btnNotif)
        btnBattery = findViewById(R.id.btnBattery)
        tvAdminState = findViewById(R.id.tvAdminState)
        btnAdmin = findViewById(R.id.btnAdmin)
        swTamperRest = findViewById(R.id.swTamperRest)
        tvTamperHint = findViewById(R.id.tvTamperHint)
        btnClearTamper = findViewById(R.id.btnClearTamper)
        swPauseMedia = findViewById(R.id.swPauseMedia)
        swMuteRest = findViewById(R.id.swMuteRest)

        tvRemoteState = findViewById(R.id.tvRemoteState)
        etServerUrl = findViewById(R.id.etServerUrl)
        etPairCode = findViewById(R.id.etPairCode)
        etDeviceName = findViewById(R.id.etDeviceName)
        btnPair = findViewById(R.id.btnPair)
        btnRemoteSync = findViewById(R.id.btnRemoteSync)
        btnRemoteUnbind = findViewById(R.id.btnRemoteUnbind)
        btnCrashLog = findViewById(R.id.btnCrashLog)
        crashLine = Crash.summary(this)
        tvMediaAccessState = findViewById(R.id.tvMediaAccessState)
        btnMediaAccess = findViewById(R.id.btnMediaAccess)

        // ---- 主界面 / 设置页 ----
        homeView = findViewById(R.id.homeView)
        settingsView = findViewById(R.id.settingsView)
        tvToday = findViewById(R.id.tvToday)
        pbUsage = findViewById(R.id.pbUsage)
        tvUsageState = findViewById(R.id.tvUsageState)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener { showSettings(null) }
        findViewById<Button>(R.id.btnOpenRemote).setOnClickListener { showSettings(R.id.etServerUrl) }
        findViewById<Button>(R.id.btnOpenDiag).setOnClickListener { showSettings(R.id.tvDiag) }
        findViewById<Button>(R.id.btnBackHome).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener { openUsageAccess() }

        // ---- 主开关 ----
        swEnable.isChecked = prefs.enabled
        swEnable.setOnCheckedChangeListener { _, checked -> onToggle(checked) }

        // ---- 时长设置 ----
        bindSeek(
            findViewById(R.id.seekUse), findViewById(R.id.tvUseValue),
            step = 5, min = 5, max = 120, unit = getString(R.string.unit_minute),
            initial = prefs.useMinutes
        ) { prefs.useMinutes = it }

        // ---- 每日总时长（v2.5.0）----
        // 0 = 不限。滑到最左边显示「不限」，其余按半小时一档。
        bindSeek(
            findViewById(R.id.seekDay), findViewById(R.id.tvDayValue),
            step = 30, min = 0, max = 480, unit = getString(R.string.unit_minute),
            initial = prefs.dayLimitMinutes,
            format = { v ->
                if (v <= 0) getString(R.string.day_limit_off)
                else getString(R.string.day_limit_value, v / 60, v % 60)
            }
        ) { v ->
            prefs.dayLimitMinutes = v
            // 改成不限时，如果正锁着要立刻放开；改小了要立刻判一次
            GuardianService.refresh(this)
        }

        bindSeek(
            findViewById(R.id.seekRest), findViewById(R.id.tvRestValue),
            step = 1, min = 1, max = 30, unit = getString(R.string.unit_minute),
            initial = prefs.restMinutes
        ) { prefs.restMinutes = it }

        bindSeek(
            findViewById(R.id.seekGap), findViewById(R.id.tvGapValue),
            step = 1, min = 1, max = 30, unit = getString(R.string.unit_minute),
            initial = prefs.resetGapSeconds / 60
        ) { prefs.resetGapSeconds = it * 60 }

        // ---- 答题解锁（v2.6.0）----
        //
        // 开关放在家长密码之后，和"惩罚休息"一个道理：开了却没有密码，
        // 遮罩上不会出现「家长操作」入口，家长自己想放行也放不了。
        val swQuiz = findViewById<android.widget.Switch>(R.id.swQuiz)
        val tvQuizState = findViewById<TextView>(R.id.tvQuizState)
        fun renderQuizState() {
            val n = QuizBank.cached(prefs).size
            tvQuizState.text = if (n > 0) getString(R.string.quiz_state_fmt, n)
            else getString(R.string.quiz_state_none)
        }
        swQuiz.isChecked = prefs.quizEnabled
        renderQuizState()
        swQuiz.setOnCheckedChangeListener { _, checked ->
            if (checked && prefs.pin.isEmpty()) {
                Toast.makeText(this, R.string.quiz_need_pin, Toast.LENGTH_LONG).show()
                swQuiz.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.quizEnabled = checked
            GuardianService.refresh(this)
        }

        bindSeek(
            findViewById(R.id.seekQuizInterval), findViewById(R.id.tvQuizIntervalValue),
            step = 1, min = 1, max = 60, unit = getString(R.string.unit_minute),
            initial = prefs.quizIntervalMinutes
        ) { prefs.quizIntervalMinutes = it }

        bindSeek(
            findViewById(R.id.seekQuizPenalty), findViewById(R.id.tvQuizPenaltyValue),
            step = 1, min = 1, max = 15, unit = getString(R.string.unit_minute),
            initial = prefs.quizPenaltyMinutes
        ) { prefs.quizPenaltyMinutes = it }

        // ---- 停用时段（v2.6.1：可以在本机编辑）----
        //
        // 以前只能由服务端下发，没配后台的机器上这个功能等于不存在。
        // 现在本机也能设，规则和下发的是**并集**：后台的删不掉，
        // 免得孩子在这台设备上一删就把宵禁废了。
        renderQuiet()
        findViewById<Button>(R.id.btnAddQuiet).setOnClickListener { addQuietRule() }

        // ---- 家长密码 ----
        findViewById<Button>(R.id.btnPin).setOnClickListener { editPin() }
        findViewById<Button>(R.id.btnClearPin).setOnClickListener { clearPin() }

        // ---- 重置计时 ----
        findViewById<Button>(R.id.btnResetSession).setOnClickListener {
            prefs.resetSession()
            GuardianService.refresh(this)
            Toast.makeText(this, R.string.msg_session_reset, Toast.LENGTH_SHORT).show()
            renderStatus()
        }

        // ---- 权限 ----
        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnNotif.setOnClickListener { requestNotif() }
        btnBattery.setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btnHuawei).setOnClickListener { openHuaweiStartup() }

        // ---- 防止孩子强制结束 ----
        btnAdmin.setOnClickListener { onAdminClick() }
        swTamperRest.isChecked = prefs.tamperPolicy == Prefs.TAMPER_REST
        swTamperRest.setOnCheckedChangeListener { _, checked ->
            // 没设密码就不允许开惩罚：一旦被触发进入休息，家长自己也会被挡在外面，
            // 而没有密码时遮罩上连"家长操作"入口都不显示，等于把自己锁死
            if (checked && prefs.pin.isEmpty()) {
                Toast.makeText(this, R.string.tamper_need_pin, Toast.LENGTH_LONG).show()
                swTamperRest.isChecked = false
                return@setOnCheckedChangeListener
            }
            prefs.tamperPolicy = if (checked) Prefs.TAMPER_REST else Prefs.TAMPER_OFF
            GuardianService.refresh(this)
        }

        // 违规记录清零：惩罚是逐次加重的，必须给家长一个回到"第 1 次"的出口
        btnClearTamper.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_clear_tamper)
                .setMessage(R.string.confirm_clear_tamper)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.clearTamper()
                    Toast.makeText(this, R.string.msg_tamper_cleared, Toast.LENGTH_SHORT).show()
                    renderTamperHint()
                    renderDiag()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // ---- 休息时让视频安静下来 ----
        swPauseMedia.isChecked = prefs.pauseMediaOnRest
        swPauseMedia.setOnCheckedChangeListener { _, checked ->
            prefs.pauseMediaOnRest = checked
            GuardianService.refresh(this)
        }
        swMuteRest.isChecked = prefs.muteOnRest
        swMuteRest.setOnCheckedChangeListener { _, checked ->
            prefs.muteOnRest = checked
            GuardianService.refresh(this)
        }
        btnMediaAccess.setOnClickListener { openNotificationAccess() }

        // ---- 远程管理 ----
        etServerUrl.setText(prefs.serverUrl)
        etDeviceName.setText(prefs.remoteName)
        btnPair.setOnClickListener { doPair() }
        btnRemoteSync.setOnClickListener { doSyncNow() }
        btnRemoteUnbind.setOnClickListener { doUnbind() }
        renderRemote()

        // ---- 测试 ----
        findViewById<Button>(R.id.btnTestRest).setOnClickListener { testRest() }
        findViewById<Button>(R.id.btnSelfTestKill).setOnClickListener { selfTestKill() }
        btnCrashLog.setOnClickListener { showCrashLog() }

        refreshPinState()
        renderStatus()
        renderDiag()
        refreshPermissions()

        // ★ 放到最后再置位：这个标记代表"控件全部就绪"。
        // 放在函数开头的话，万一中间某一步抛异常，后面每秒一次的刷新
        // 就会去访问半初始化的控件，把一次小问题放大成闪退。
        uiReady = true

        // 启动状态刷新（onResume 可能已经走过了）
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
    }

    // ==================================================================
    // 主界面 ⇄ 设置页
    // ==================================================================

    /**
     * 打开设置页。
     *
     * 主界面只留"状态 + 开关 + 今日概览 + 三个入口"，参数、权限、密码、
     * 远程管理、测试、诊断全部收进这里 —— 一屏到底的长页面确实不好用，
     * 而且家长日常只会动开关。
     *
     * [scrollTo] 给了控件 id 就滚到那块（比如从「远程管理」入口进来），
     * 不用家长自己在设置里翻。
     */
    private fun showSettings(scrollTo: Int? = null) {
        if (!::homeView.isInitialized) return
        homeView.visibility = View.GONE
        settingsView.visibility = View.VISIBLE
        inSettings = true

        // 刚好这会儿可能从系统设置页回来（授权了使用情况访问），顺手刷新一次
        renderDiag()
        refreshPermissions()
        renderUsageState(force = true)

        if (scrollTo != null) {
            val sv = findViewById<ScrollView>(R.id.mainScroll)
            sv.post {
                val target = findViewById<View>(scrollTo)
                sv.smoothScrollTo(0, target.top)
            }
        } else {
            findViewById<ScrollView>(R.id.mainScroll)?.scrollTo(0, 0)
        }
    }

    private fun showHome() {
        if (!::homeView.isInitialized) return
        settingsView.visibility = View.GONE
        homeView.visibility = View.VISIBLE
        inSettings = false
        renderStatus()
        renderHome()
        findViewById<ScrollView>(R.id.mainScroll)?.scrollTo(0, 0)
    }

    /** 设置页里按返回 = 回主界面，而不是直接退出应用 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inSettings) {
            showHome()
            return
        }
        super.onBackPressed()
    }

    /** 主界面那一小行"今日已用 X · 休息 N 次" + 进度条 */
    private fun renderHome() {
        if (!::tvToday.isInitialized) return

        val used = prefs.todayUsedMs
        tvToday.text = if (used <= 0L && prefs.restCount <= 0) {
            getString(R.string.today_summary_idle)
        } else {
            getString(R.string.today_summary, fmtDuration(used), prefs.restCount)
        }

        val limit = (prefs.useMinutes * 60_000L).coerceAtLeast(60_000L)
        pbUsage.progress = ((GuardianService.sUsedMs * 100L) / limit).toInt().coerceIn(0, 100)

        renderUsageState(force = false)
    }

    /**
     * 「使用情况访问」授权状态。
     *
     * 这是个可选权限：没授权也能正常守护，只是后台时间线上看不到应用名。
     * AppOps 查询是跨进程的，每秒查一次没必要，缓存 10 秒。
     */
    private fun renderUsageState(force: Boolean) {
        if (!::tvUsageState.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (force || now - usagePermAt > 10_000L) {
            usagePermAt = now
            usagePermOk = Usage.hasPermission(this)
        }
        tvUsageState.text = getString(
            if (usagePermOk) R.string.usage_access_ok else R.string.usage_access_missing
        )
    }

    private fun openUsageAccess() {
        if (Usage.openSettings(this)) {
            // 跳转后 onStop 会触发上锁，这里按"主动跳系统页"的宽限处理
            externalJumpAtElapsed = SystemClock.elapsedRealtime()
            Toast.makeText(this, R.string.usage_access_opened, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.usage_access_no_settings, Toast.LENGTH_LONG).show()
            openAppDetails()
        }
    }

    // ==================================================================
    // 远程管理
    // ==================================================================

    /** 配对请求正在飞的时候，别让每秒一次的刷新把"正在连接"覆盖掉 */
    private var pairingInFlight = false

    private fun renderRemote() {
        // 双保险：这个函数的调用点既可能是 setupUi()（控件已就绪），
        // 也可能是每秒的 uiTick。上锁时 setupUi() 还没跑，这些控件全是
        // 未初始化的 lateinit —— 直接访问就是一次崩溃，且是"每次打开都崩"那种。
        if (!::btnRemoteSync.isInitialized) return
        if (pairingInFlight) return

        val configured = prefs.remoteConfigured()
        btnRemoteSync.isEnabled = configured
        btnRemoteUnbind.isEnabled = configured

        if (!configured) {
            tvRemoteState.text = getString(R.string.remote_state_off)
            return
        }

        val ago = if (prefs.remoteLastOkWall > 0L) {
            agoOf(prefs.remoteLastOkWall)
        } else {
            getString(R.string.remote_never_synced)
        }
        val sb = StringBuilder(
            getString(R.string.remote_state_on, prefs.serverUrl, prefs.deviceId, ago)
        )
        val err = prefs.remoteLastError
        if (err.isNotEmpty()) {
            sb.append('\n').append(getString(R.string.remote_state_err, err))
        }
        if (!prefs.enabled) {
            sb.append('\n').append(getString(R.string.remote_standby_note))
        }
        tvRemoteState.text = sb
    }

    private fun agoOf(wall: Long): String {
        val sec = ((System.currentTimeMillis() - wall) / 1000L).coerceAtLeast(0L)
        return when {
            sec < 10L -> "刚刚"
            sec < 60L -> "$sec 秒前"
            sec < 3600L -> "${sec / 60} 分钟前"
            sec < 86400L -> "${sec / 3600} 小时前"
            else -> "${sec / 86400} 天前"
        }
    }

    private fun doPair() {
        val url = etServerUrl.text.toString().trim()
        val code = etPairCode.text.toString().trim()
        val name = etDeviceName.text.toString().trim()

        if (url.isEmpty()) {
            toast(getString(R.string.remote_need_url))
            return
        }
        if (code.length < 4) {
            toast(getString(R.string.remote_need_code))
            return
        }

        pairingInFlight = true
        btnPair.isEnabled = false
        tvRemoteState.text = getString(R.string.remote_pairing)

        Remote.pair(this, url, code, name) { ok, msg ->
            pairingInFlight = false
            btnPair.isEnabled = true
            toast(msg)
            if (ok) {
                etPairCode.setText("")
                prefs.remoteName = name.ifEmpty { Build.MODEL ?: "安卓平板" }
                etServerUrl.setText(prefs.serverUrl)
                // 心跳是由守护服务发的，所以配对完必须确保服务在跑
                GuardianService.start(this)
            }
            renderRemote()
        }
    }

    private fun doSyncNow() {
        if (!prefs.remoteConfigured()) return
        // 服务没跑的话，心跳能发出去，但指令没人执行 —— 先把它拉起来
        GuardianService.start(this)
        Remote.heartbeat(this)
        toast(getString(R.string.remote_sync_hint))
    }

    private fun doUnbind() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_remote_unbind)
            .setMessage(R.string.remote_unbind_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Remote.unbind(this)
                prefs.remoteName = ""
                etPairCode.setText("")
                toast(getString(R.string.remote_unbound))
                // 解绑之后如果守护本来就是关的，服务就没有继续待命的理由了，
                // 让 onStartCommand 重新走一遍判断，自然退出。
                GuardianService.refresh(this)
                renderRemote()
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun bindSeek(
        seek: SeekBar,
        label: TextView,
        step: Int,
        min: Int,
        max: Int,
        unit: String,
        initial: Int,
        format: ((Int) -> String)? = null,
        onValue: (Int) -> Unit
    ) {
        val steps = (max - min) / step
        seek.max = steps
        val idx = ((initial - min) / step).coerceIn(0, steps)
        seek.progress = idx
        fun text(v: Int) = format?.invoke(v) ?: getString(R.string.value_fmt, v, unit)
        label.text = text(min + idx * step)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                label.text = text(v)
                onValue(v)
                GuardianService.refresh(this@MainActivity)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ==================================================================
    // 自动确保守护服务在运行（重要修复）
    // ==================================================================

    /**
     * 只要「开启守护」是打开的，应用一到前台就保证服务一定在跑。
     *
     * 之前只有切换开关和开机广播会启动服务，于是进程一旦被系统收走，
     * 重新打开 App 就会一直停在"守护未在运行"，必须手动关一下开关再打开。
     * 现在每秒检查一次，发现没跑就自己拉起来。
     */
    private fun ensureServiceRunning() {
        if (!prefs.enabled) {
            startAttempts = 0
            return
        }
        if (GuardianService.running) {
            startAttempts = 0
            return
        }
        if (startAttempts >= MAX_START_ATTEMPTS) return
        startAttempts++
        GuardianService.start(this)
    }

    // ==================================================================
    // 开关
    // ==================================================================

    private fun onToggle(checked: Boolean) {
        prefs.enabled = checked
        if (checked) {
            if (!canOverlay()) {
                Toast.makeText(this, R.string.warn_need_overlay, Toast.LENGTH_LONG).show()
            }
            prefs.resetSession()
            GuardianService.start(this)
        } else {
            GuardianService.stop(this)
        }
        renderStatus()
    }

    // ==================================================================
    // 状态渲染
    // ==================================================================

    private fun renderStatus() {
        if (!::tvStatus.isInitialized) return
        val text = when {
            !prefs.enabled -> getString(R.string.status_off)
            GuardianService.sResting ->
                getString(R.string.status_resting, fmt(GuardianService.sRestLeftMs))
            !GuardianService.running -> getString(R.string.status_starting)
            GuardianService.sUsing -> {
                val limitMs = prefs.useMinutes * 60_000L
                val left = (limitMs - GuardianService.sUsedMs).coerceAtLeast(0L)
                getString(R.string.status_running, fmt(GuardianService.sUsedMs), fmt(left))
            }
            else -> getString(R.string.status_idle)
        }
        tvStatus.text = "$text\n" + getString(R.string.status_rest_count, prefs.restCount)
    }

    /**
     * 诊断信息：悬浮窗没弹出来的时候，靠这一块定位原因。
     */
    private fun renderDiag() {
        if (!::tvDiag.isInitialized) return

        val overlayPerm = if (canOverlay()) {
            getString(R.string.state_granted)
        } else {
            getString(R.string.state_missing)
        }

        val layer = when {
            GuardianService.sOverlayShown -> getString(R.string.diag_layer_overlay)
            LockActivity.isForeground -> getString(R.string.diag_layer_activity)
            else -> getString(R.string.diag_layer_none)
        }

        val err = GuardianService.lastOverlayError ?: getString(R.string.diag_none)

        // 后台守护：有没有被发现"进程曾经被结束"
        val keepAlive = if (prefs.killCount > 0) {
            getString(R.string.diag_keepalive_killed, prefs.killCount) + "  " +
                getString(
                    R.string.diag_kill_reason,
                    fmtClock(prefs.lastKillWall),
                    prefs.lastKillReason
                )
        } else {
            getString(R.string.diag_keepalive_ok)
        }

        val admin = if (isAdminActive()) {
            getString(R.string.admin_state_on)
        } else {
            getString(R.string.admin_state_off)
        }

        // ---- 保活通道：系统里还挂着我们排的闹钟吗 ----
        // 查不到 = 应用曾被置为 stopped 状态（「强行停止」会清空全部闹钟）
        val channel = if (KeepAlive.isWatchdogArmed(this)) {
            getString(R.string.diag_channel_ok)
        } else {
            getString(R.string.diag_channel_missing)
        }

        // ---- 最近一次启动的来源与时间：区分"闹钟自动复活"和"用户手动打开" ----
        val lastStart = if (prefs.lastStartWall > 0L) {
            getString(
                R.string.diag_last_start,
                fmtClock(prefs.lastStartWall),
                prefs.lastStartTrigger
            )
        } else {
            getString(R.string.diag_start_none)
        }

        // ---- 上次复活前守护中断了多久：判断保活到底灵不灵的核心指标 ----
        // 正常应在 15 秒上下；几分钟说明只有 5 分钟的强通道在工作；
        // 几十分钟甚至更久，说明闹钟全废了、是被手动打开 App 才恢复的。
        val downtime = if (prefs.lastDowntimeMs >= 0L) {
            fmtDuration(prefs.lastDowntimeMs)
        } else {
            getString(R.string.diag_downtime_first)
        }

        val taskRemoved = if (prefs.taskRemovedCount > 0) {
            getString(
                R.string.diag_task_removed,
                prefs.taskRemovedCount,
                fmtClock(prefs.lastTaskRemovedWall)
            )
        } else {
            getString(R.string.diag_task_removed_none)
        }

        val forceStop = if (prefs.forceStopCount > 0) {
            getString(
                R.string.diag_force_stop_hit,
                prefs.forceStopCount,
                fmtClock(prefs.lastForceStopWall)
            )
        } else {
            getString(R.string.diag_force_stop_ok)
        }

        // 最近几次强停的时间：孩子会说"我没弄"，时间线是最直接的证据
        val forceStopTimes = prefs.tamperTimeList().take(4).joinToString("、") { fmtClock(it) }

        // 加固等级：决定"强行停止"这条路到底能不能被堵住
        val harden = when {
            isDeviceOwner() -> getString(R.string.diag_harden_owner)
            isAdminActive() -> getString(R.string.diag_harden_admin)
            else -> getString(R.string.diag_harden_none)
        }

        val startErr = prefs.lastStartError.ifEmpty { getString(R.string.diag_none) }

        // 后台有没有被系统"冻结"。
        //
        // 这是"通知栏里的计时突然不动了"的直接证据：前台服务只保证进程不被回收，
        // 保证不了它一直在跑。ColorOS 的「应用速冻」/ 睡眠待机优化会把进程整个冻住，
        // 线程全暂停 —— 通知还挂着，数字却不再刷新。
        val stall = if (prefs.stallCount > 0) {
            getString(R.string.diag_stall, prefs.stallCount, fmtDuration(prefs.stallMaxMs))
        } else {
            getString(R.string.diag_stall_none)
        }

        // 电池优化白名单：没加入时，国产 ROM 更容易在后台把进程冻住
        val batteryOpt = if (batteryWhitelisted()) {
            getString(R.string.diag_battery_ok)
        } else {
            getString(R.string.diag_battery_missing)
        }

        // 上次崩溃：家长不用连电脑抓 logcat，这里直接能看到崩在哪一行。
        // 内容在 setupUi() 时读一次就缓存了，不必每秒读盘。
        val crash = crashLine

        // 休息静音：只有休息期间才显示实时的压制情况，平时给一句说明即可
        val silenceNote = if (GuardianService.sResting) {
            Silence.lastNote.ifEmpty { Silence.describe(this) }
        } else {
            getString(R.string.silence_idle)
        }

        // 停用时段：分管两处统计，家长排查"到底哪条规则在锁我"时要用
        val quietLine = getString(
            R.string.diag_quiet_fmt,
            prefs.quietRules().count { it.enabled },
            prefs.localQuietRules().count { it.enabled }
        )

        tvDiag.text = buildString {
            append(getString(R.string.diag_overlay_perm, overlayPerm))
            append('\n')
            append(quietLine)
            append('\n')
            append(getString(R.string.diag_lock_layer, layer))
            append('\n')
            append(getString(R.string.diag_channel, channel))
            append('\n')
            append(getString(R.string.diag_battery, batteryOpt))
            append('\n')
            append(stall)
            append('\n')
            append(getString(R.string.diag_keepalive, keepAlive))
            append('\n')
            append(getString(R.string.diag_downtime, downtime))
            append('\n')
            append(lastStart)
            append('\n')
            append(taskRemoved)
            append('\n')
            append(forceStop)
            append('\n')
            if (forceStopTimes.isNotEmpty()) {
                append(getString(R.string.diag_force_stop_recent, forceStopTimes))
                append('\n')
            }
            append(getString(R.string.diag_harden, harden))
            append('\n')
            append(getString(R.string.diag_admin, admin))
            append('\n')
            append(getString(R.string.diag_silence, silenceNote))
            append('\n')
            append(getString(R.string.diag_start_error, startErr))
            append('\n')
            if (crash.isNotEmpty()) {
                append(getString(R.string.diag_crash, crash))
                append('\n')
            }
            append(getString(R.string.diag_error, err))
        }

        renderTamperHint()
    }

    /**
     * 违规记录提示。
     *
     * 必须把因果讲清楚：被「强行停止」之后系统会清空我们的全部闹钟与任务，
     * 守护在那一刻就已经停了，**没有任何办法把自己拉回来**。
     * 所以我们做的是"记账"——下次应用被打开的第一秒就把休息补上。
     */
    private fun renderTamperHint() {
        if (!::tvTamperHint.isInitialized) return
        val strikes = prefs.tamperStrikes
        val count = maxOf(prefs.forceStopCount, strikes)
        if (count <= 0) {
            tvTamperHint.setText(R.string.tamper_hint_none)
            btnClearTamper.visibility = View.GONE
            return
        }
        btnClearTamper.visibility = View.VISIBLE

        val recent = prefs.tamperTimeList().firstOrNull()?.let { fmtClock(it) } ?: "—"
        val pending = prefs.pendingPenaltyMs
        if (prefs.tamperPolicy == Prefs.TAMPER_REST) {
            val ms = if (pending > 0L) pending else prefs.penaltyMsFor(strikes + 1)
            val nth = if (pending > 0L) strikes else strikes + 1
            tvTamperHint.text = getString(
                R.string.tamper_hint_records,
                count, recent, fmtDuration(ms), nth
            )
        } else {
            tvTamperHint.text = getString(R.string.tamper_hint_no_penalty, count, recent)
        }
    }

    private fun refreshPermissions() {
        if (!::tvOverlayState.isInitialized) return

        val overlayOk = canOverlay()
        tvOverlayState.setText(if (overlayOk) R.string.state_granted else R.string.state_missing)
        btnOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        tvNotifState.setText(if (notifOk) R.string.state_granted else R.string.state_missing)
        btnNotif.visibility = if (notifOk) View.GONE else View.VISIBLE

        val batteryOk = runCatching {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        tvBatteryState.setText(if (batteryOk) R.string.state_granted else R.string.state_missing)
        btnBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE

        // 通知使用权：决定能不能直接给播放器下发暂停
        val mediaOk = Silence.hasSessionAccess(this)
        tvMediaAccessState.setText(if (mediaOk) R.string.state_granted else R.string.state_missing)
        btnMediaAccess.visibility = if (mediaOk) View.GONE else View.VISIBLE

        refreshAdmin()
    }

    // ==================================================================
    // 设备管理员（防止「强行停止」与静默卸载）
    // ==================================================================

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun isAdminActive(): Boolean = runCatching {
        getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent())
    }.getOrDefault(false)

    /**
     * 是否是「设备所有者」(Device Owner)。
     *
     * 这是唯一能真正堵住「强行停止」和卸载的级别 —— 系统会直接把这两个入口禁用，
     * 且不能像设备管理员那样被孩子自行取消。代价是需要用 adb 一次性设置
     * （通常还要恢复出厂），所以只做状态展示，不在应用内引导开启。
     */
    private fun isDeviceOwner(): Boolean = runCatching {
        getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
    }.getOrDefault(false)

    private fun refreshAdmin() {
        if (!::tvAdminState.isInitialized) return
        val active = isAdminActive()
        tvAdminState.setText(if (active) R.string.admin_sub_on else R.string.admin_sub_off)
        btnAdmin.setText(
            if (active) R.string.btn_deactivate_admin else R.string.btn_activate_admin
        )
    }

    private fun onAdminClick() {
        if (isAdminActive()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.btn_deactivate_admin)
                .setMessage(R.string.admin_confirm_deactivate)
                .setPositiveButton(android.R.string.ok) { _, _ -> deactivateAdmin() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.admin_activate_title)
                .setMessage(R.string.admin_activate_explain)
                .setPositiveButton(android.R.string.ok) { _, _ -> launchAdminActivation() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launchAdminActivation() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_activate_explain)
            )
        val ok = jumpToSystem { startActivity(intent) }
        if (!ok) {
            Toast.makeText(this, R.string.admin_activate_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun deactivateAdmin() {
        runCatching {
            getSystemService(DevicePolicyManager::class.java)
                .removeActiveAdmin(adminComponent())
        }
        refreshAdmin()
        Toast.makeText(this, R.string.admin_disabled, Toast.LENGTH_LONG).show()
    }

    private fun refreshPinState() {
        tvPinState.setText(if (prefs.pin.isEmpty()) R.string.pin_unset else R.string.pin_set)
    }

    // ==================================================================
    // 密码
    // ==================================================================

    /**
     * 回到前台时如果还没验证过，就重新要求输密码。
     *
     * 为什么不能只在 onCreate 里弹：本 Activity 在清单里是 `launchMode="singleTask"`，
     * 而且"隐藏"（Home / 切走 / 最近任务）不会销毁它，重新打开只是把已有实例拉回前台，
     * onCreate 不会再执行。所以必须每次回到前台主动检查。
     */
    private fun maybeRelock() {
        if (unlocked) return

        // 密码被清掉了（家长在别处操作过），直接放行
        if (prefs.pin.isEmpty()) {
            unlocked = true
            setContentVisible(true)
            if (!uiReady) setupUi()
            return
        }

        // 已经有一个密码框在等着了，别叠第二个
        if (pinDialog?.isShowing == true) return

        setContentVisible(false)
        promptPin()
    }

    private fun promptPin() {
        if (isFinishing || isDestroyed) return

        var dlg: AlertDialog? = null
        dlg = pinPadDialog(
            titleRes = R.string.parent_verify,
            hintRes = R.string.pin_enter,
            // 位数已知，输满即自动校验，不用再点"确定"
            maxLen = prefs.pin.length,
            cancelable = false,
            negativeRes = R.string.exit_app,
            onNegative = { finish() }
        ) { entered, pad ->
            pad.clear()
            val left = PinGuard.lockedLeftMs(prefs)
            if (left > 0L) {
                // 冷却期内不校验，防止孩子靠"多试几次"蒙对
                Toast.makeText(
                    this,
                    getString(R.string.pin_locked_fmt, PinGuard.lockedLeftSeconds(prefs)),
                    Toast.LENGTH_LONG
                ).show()
            } else if (entered == prefs.pin) {
                PinGuard.onOk(prefs)
                // 先置位再收对话框：下面 onDismissListener 里会检查 unlocked，
                // 顺序反了会被误判成"没验证就把密码框关掉了"
                unlocked = true
                dlg?.dismiss()
                setContentVisible(true)
                if (!uiReady) setupUi()
            } else {
                val cooled = PinGuard.onWrong(prefs)
                Toast.makeText(
                    this,
                    if (cooled) {
                        getString(R.string.pin_locked_fmt, PinGuard.lockedLeftSeconds(prefs))
                    } else {
                        getString(R.string.pin_wrong_tries, PinGuard.MAX_TRIES - prefs.pinFails)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        dlg.setOnDismissListener {
            if (pinDialog === dlg) pinDialog = null
            // 密码框没了、却还没验证通过 → 绝不能停在一片空白上（看起来就是"闪退"）。
            // 唯一的例外是 Activity 自己在销毁/旋转，那种情况交给系统重建。
            if (!unlocked && !isFinishing && !isDestroyed && !isChangingConfigurations) finish()
        }
        pinDialog = dlg
    }

    /**
     * 验证通过前，把设置内容整体藏起来（密码框是独立窗口，不受影响）。
     *
     * 同时铺一层不透明的"已上锁"提示：
     * 原来只做 `INVISIBLE`，万一密码框因为任何原因没弹出来（系统回收窗口、
     * ROM 拦截对话框等），家长看到的是一片空白，和闪退一模一样，根本无从判断。
     * 现在至少能明确看到"应用还在，只是要密码"。
     */
    private fun setContentVisible(visible: Boolean) {
        findViewById<View>(android.R.id.content).visibility =
            if (visible) View.VISIBLE else View.INVISIBLE
        setLockHintVisible(!visible)
    }

    /** 全屏的"已上锁"遮片，直接挂在 DecorView 上（内容根布局此时是 INVISIBLE） */
    private fun setLockHintVisible(show: Boolean) {
        val decor = window?.decorView as? android.view.ViewGroup ?: return
        if (!show) {
            lockHint?.let { decor.removeView(it) }
            lockHint = null
            return
        }
        if (lockHint != null) return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
            // 吃掉点击，别让底下的控件（虽然不可见）收到事件
            isClickable = true
        }
        box.addView(
            TextView(this).apply {
                text = getString(R.string.lock_hint_title)
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(themeColor(android.R.attr.textColorPrimary))
            }
        )
        box.addView(
            TextView(this).apply {
                text = getString(R.string.lock_hint_sub)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(12), dp(28), 0)
                setTextColor(themeColor(android.R.attr.textColorSecondary))
            }
        )
        decor.addView(
            box,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        lockHint = box
    }

    // ==================================================================
    // 停用时段（本机编辑，v2.6.1）
    // ==================================================================

    /**
     * 重绘停用时段列表。
     *
     * 列表里**同时**显示后台下发的和本机加的，并用标签区分 ——
     * 只显示本机的话，家长会以为"我在手机上加了两条，怎么列表里只有两条"
     * （后台那几条其实也在生效），只能靠猜。
     */
    private fun renderQuiet() {
        val host = findViewById<LinearLayout>(R.id.quietHost)
        host.removeAllViews()

        val local = prefs.localQuietRules()
        val all = prefs.allQuietRules()

        if (all.isEmpty()) {
            host.addView(
                TextView(this).apply {
                    text = getString(R.string.quiet_empty)
                    textSize = 13f
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
        }

        for (r in all) {
            // 本机是否有"等价的一条"：按时间+星期判断，不看 id/label
            val isLocal = local.any { it.from == r.from && it.to == r.to && it.days == r.days }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val tv = TextView(this).apply {
                textSize = 14f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                text = getString(
                    R.string.quiet_tag_fmt,
                    getString(if (isLocal) R.string.quiet_tag_local else R.string.quiet_tag_server),
                    Quiet.describe(r)
                )
            }
            row.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (isLocal) {
                val del = Button(this).apply {
                    text = getString(R.string.btn_delete)
                    textSize = 13f
                    setOnClickListener { confirmDeleteQuiet(r) }
                }
                row.addView(del)
            } else {
                // 下发的规则在本机只能看不能改：说清楚去哪儿改，
                // 否则家长会一直找"为什么这条没有删除按钮"
                val tip = TextView(this).apply {
                    text = getString(R.string.quiet_server_tip)
                    textSize = 11f
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                }
                row.addView(tip)
            }
            host.addView(row)
        }

        // 下一段什么时候开始：只显示倒计时列表的话，家长看不出"今晚到底锁不锁"
        val next = Quiet.nextStartAt(prefs, System.currentTimeMillis())
        if (next > 0L) {
            val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
            host.addView(
                TextView(this).apply {
                    text = getString(R.string.quiet_next_fmt, fmt.format(Date(next)))
                    textSize = 12f
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
    }

    /** 添加一条本机停用时段。要求先有家长密码，和"惩罚休息""答题解锁"一致。 */
    private fun addQuietRule() {
        if (prefs.pin.isEmpty()) {
            Toast.makeText(this, R.string.quiet_need_pin, Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.localQuietRules().size >= MAX_LOCAL_QUIET) {
            Toast.makeText(this, R.string.quiet_full, Toast.LENGTH_SHORT).show()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }

        fun caption(res: Int) = TextView(this).apply {
            setText(res)
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), 0, 0)
        }

        val start = android.widget.TimePicker(this).apply {
            setIs24HourView(true)
            hour = 22
            minute = 0
        }
        val end = android.widget.TimePicker(this).apply {
            setIs24HourView(true)
            hour = 7
            minute = 0
        }

        box.addView(caption(R.string.quiet_dlg_start))
        box.addView(start)
        box.addView(caption(R.string.quiet_dlg_end))
        box.addView(end)
        box.addView(caption(R.string.quiet_dlg_days))

        // 星期：7 个勾选框，一个都不勾 = 每天（和服务端 days=0 的口径一致）
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val checks = ArrayList<android.widget.CheckBox>()
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..6) {
            val cb = android.widget.CheckBox(this).apply {
                text = names[i]
                textSize = 12f
                setPadding(0, 0, 0, 0)
            }
            checks.add(cb)
            dayRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(dayRow)

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.quiet_dlg_title)
            .setView(wrap(box))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.show()
        // 覆写确定键：要校验（零长度 / 重复），校验不过就不能关框
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val from = start.hour * 60 + start.minute
            val to = end.hour * 60 + end.minute
            if (from == to) {
                Toast.makeText(this, R.string.quiet_same_time, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var days = 0
            for (i in 0..6) if (checks[i].isChecked) days = days or (1 shl i)
            val list = prefs.localQuietRules()
            if (list.any { it.from == from && it.to == to && it.days == days }) {
                Toast.makeText(this, R.string.quiet_dup, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.setLocalQuietRules(
                list + Quiet.Rule(
                    id = "loc_${System.currentTimeMillis()}",
                    enabled = true,
                    label = "",
                    from = from,
                    to = to,
                    days = days
                )
            )
            dlg.dismiss()
            renderQuiet()
            GuardianService.refresh(this)
            Toast.makeText(this, R.string.quiet_added, Toast.LENGTH_SHORT).show()
            // 改完正好落在时段里 → 马上就会锁，先说一声，别让家长以为是 App 抽风
            if (Quiet.current(this, prefs, System.currentTimeMillis()) != null) {
                Toast.makeText(this, R.string.quiet_locked_now, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteQuiet(r: Quiet.Rule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.quiet_del_title)
            .setMessage(Quiet.describe(r))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setLocalQuietRules(
                    prefs.localQuietRules().filterNot {
                        it.from == r.from && it.to == r.to && it.days == r.days
                    }
                )
                renderQuiet()
                GuardianService.refresh(this)
                Toast.makeText(this, R.string.quiet_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editPin() {
        askNewPin(first = true, pending = "")
    }

    /**
     * 分两步设置密码：先输新密码，再输一遍确认。全程用应用自带的数字键盘。
     */
    private fun askNewPin(first: Boolean, pending: String) {
        var dlg: AlertDialog? = null
        dlg = pinPadDialog(
            titleRes = R.string.btn_set_pin,
            hintRes = if (first) R.string.pin_new else R.string.pin_confirm,
            // 位数不固定，多一个「确定」键
            maxLen = null,
            cancelable = true,
            negativeRes = android.R.string.cancel
        ) { entered, pad ->
            if (entered.length < PinPad.MIN_LEN) {
                pad.clear()
                Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_SHORT).show()
                return@pinPadDialog
            }
            dlg?.dismiss()
            when {
                first -> askNewPin(first = false, pending = entered)
                entered == pending -> {
                    prefs.pin = entered
                    refreshPinState()
                    Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    // 不一致就从第一步重来，避免家长对着第二个框反复试
                    askNewPin(first = true, pending = "")
                }
            }
        }
    }

    // ==================================================================
    // 密码框：应用自带的数字键盘，不依赖系统输入法
    // ==================================================================

    /**
     * 弹出一个**自带数字键盘**的密码框。
     *
     * 为什么不用系统输入法：孩子的平板上做过管控，在这类"遮罩/门禁"场景里
     * 系统键盘会**闪一下就消失**（键盘窗口被全屏遮罩挤掉），家长连密码都输不进去。
     * 键盘由 [PinPad] 自己画，不依赖任何输入法，任何 ROM 上都能用。
     * 顺带的好处：第三方输入法记录不到、也上传不了家长密码。
     *
     * @param maxLen 输入到这么多位就自动回调（用于**校验**密码，位数已知）；
     *               传 null 则多出一个「确定」键（用于**设置**密码，位数不定）
     */
    private fun pinPadDialog(
        titleRes: Int,
        hintRes: Int?,
        maxLen: Int?,
        cancelable: Boolean,
        negativeRes: Int?,
        onNegative: (() -> Unit)? = null,
        onComplete: (String, PinPad.Input) -> Unit
    ): AlertDialog {
        val dots = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
            minHeight = dp(40)
        }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (hintRes != null) {
                addView(
                    TextView(this@MainActivity).apply {
                        setText(hintRes)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(themeColor(android.R.attr.textColorSecondary))
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(
                dots,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                host,
                LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(wrap(box))
            .setCancelable(cancelable)
        if (negativeRes != null) builder.setNegativeButton(negativeRes, null)

        val dlg = builder.create()
        dlg.show()
        if (negativeRes != null) {
            // 覆写了点击监听，所以要自己收掉对话框
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dlg.dismiss()
                onNegative?.invoke()
            }
        }
        PinPad.build(host, dots, maxLen, onComplete)
        return dlg
    }

    /** 取当前主题下的文字颜色（浅色/深色模式都能看清） */
    private fun themeColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0xFF1B2233.toInt())
        ta.recycle()
        return c
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun clearPin() {
        if (prefs.pin.isEmpty()) {
            Toast.makeText(this, R.string.pin_unset, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_clear_pin)
            .setMessage(R.string.pin_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.pin = ""
                refreshPinState()
                Toast.makeText(this, R.string.pin_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wrap(v: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                v,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // ==================================================================
    // 权限跳转
    // ==================================================================

    private fun canOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    /**
     * 跳去系统界面（授权页 / 应用详情 / 设备管理器）。
     *
     * 这种跳转会让本页面 onStop，而 onStop 默认是要重新上锁的。
     * 但它是家长主动发起的操作，来回一次就要重输一遍密码太折腾，
     * 所以记一个时间戳让 onStop 放行一次——时间戳会自己过期，
     * 万一界面其实没进后台（跳转被 ROM 拦掉），也不会把标记留到下一次真正的"隐藏"上。
     *
     * @return 是否真的拉起了系统界面
     */
    private fun jumpToSystem(block: () -> Unit): Boolean {
        val ok = runCatching(block).isSuccess
        externalJumpAtElapsed = if (ok) SystemClock.elapsedRealtime() else 0L
        return ok
    }

    private fun openOverlaySettings() {
        if (!jumpToSystem {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        ) openAppDetails()
    }

    /**
     * 打开系统的「通知使用权」列表。
     *
     * 授权之后 [Silence] 才能拿到活跃的媒体会话、直接给播放器下发暂停。
     * 这是可选增强：不授权也能靠音频焦点停住绝大多数视频 App。
     */
    private fun openNotificationAccess() {
        val ok = jumpToSystem {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!ok) {
            // 个别 ROM 没有这个页面，退化成应用详情，家长可以手动在「通知」里找
            Toast.makeText(this, R.string.media_access_manual, Toast.LENGTH_LONG).show()
            openAppDetails()
        }
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        } else {
            openAppDetails()
        }
    }

    /**
     * 是否已加入「电池优化白名单」。
     *
     * 没加入时，Android 的 Doze / App Standby 与国产 ROM 的省电策略都会更容易
     * 限制这个应用在后台的运行 —— 表现就是"通知栏还在，但计时停住了"。
     */
    private fun batteryWhitelisted(): Boolean = runCatching {
        val pm = getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(packageName) ?: false
    }.getOrDefault(false)

    private fun openBatterySettings() {
        val ok = jumpToSystem {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        if (ok) return
        val ok2 = jumpToSystem {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        if (!ok2) openAppDetails()
    }

    /** 华为 / 鸿蒙的「应用启动管理」在系统管家内部，没有标准 Intent，逐个尝试 */
    private fun openHuaweiStartup() {
        val candidates = listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )
        for (c in candidates) {
            if (jumpToSystem {
                    startActivity(
                        Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            ) return
        }
        Toast.makeText(this, R.string.huawei_manual_hint, Toast.LENGTH_LONG).show()
        openAppDetails()
    }

    private fun openAppDetails() {
        jumpToSystem {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ==================================================================
    // 测试
    // ==================================================================

    private fun testRest() {
        if (!prefs.enabled) {
            Toast.makeText(this, R.string.test_need_enable, Toast.LENGTH_LONG).show()
            return
        }
        GuardianService.refresh(this)
        runCatching {
            startService(
                Intent(this, GuardianService::class.java)
                    .setAction(GuardianService.ACTION_TEST_REST)
            )
        }
        Toast.makeText(
            this,
            getString(R.string.test_started, prefs.restMinutes),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun fmtClock(wall: Long): String {
        if (wall <= 0L) return "--:--:--"
        return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wall))
    }

    /** 把毫秒数说成人话：45 秒 / 2 分 13 秒 */
    private fun fmtDuration(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return if (totalSec < 60L) {
            getString(R.string.diag_sec_fmt, totalSec.toInt())
        } else {
            getString(
                R.string.diag_min_sec_fmt,
                (totalSec / 60L).toInt(),
                (totalSec % 60L).toInt()
            )
        }
    }

    /**
     * 自检：先把复活通道全部布置好，然后立刻结束自己的进程，
     * 模拟孩子"强行结束"的情况。约 1~3 秒后守护应自动重新启动。
     */
    private fun selfTestKill() {
        if (!prefs.enabled) {
            Toast.makeText(this, R.string.test_need_enable, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_selftest_kill)
            .setMessage(R.string.selftest_kill_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 不在这里手工记账：让服务复活后自己通过"未正常退出"这个信号识别出来，
                // 这样一次自检同时验证了「复活」和「被杀识别」两件事
                KeepAlive.chainRelaunch(this)
                KeepAlive.scheduleResurrectJob(this)
                Toast.makeText(this, R.string.selftest_kill_go, Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                }, 900L)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 显示上次崩溃的完整堆栈。
     *
     * 家长把内容复制发过来，就能直接定位到是哪一行、什么异常 ——
     * 不用教他怎么开 USB 调试、跑 adb logcat。
     */
    private fun showCrashLog() {
        val text = Crash.read(this)
        if (text.isBlank()) {
            toast(getString(R.string.crash_none))
            return
        }
        val tv = TextView(this).apply {
            setText(text)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setView(sv)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                runCatching {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                }
                toast(getString(R.string.crash_copied))
            }
            .setNeutralButton(R.string.crash_clear) { _, _ ->
                Crash.clear(this)
                crashLine = ""
                renderDiag()
                toast(getString(R.string.crash_none))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQ_NOTIF = 100

        /** 本机最多几条停用时段。太多条重叠会让"到底哪个在生效"变得无法解释。 */
        private const val MAX_LOCAL_QUIET = 8

        /** 自动重启服务的最大尝试次数 */
        private const val MAX_START_ATTEMPTS = 5

        /**
         * 家长主动跳去系统设置页时，回来多久之内不重新要求验证。
         * 正常授权往返最多几十秒；超过这个时间说明人可能已经离开了，重新锁上。
         */
        private const val EXTERNAL_GRACE_MS = 180_000L

        /**
         * 点「去设置」之后多久之内发生的 onStop 才算"这次主动跳转"。
         * 超过这个窗口就当作普通的"隐藏"，照常上锁。
         */
        private const val JUMP_WINDOW_MS = 8_000L
    }
}
