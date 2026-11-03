package com.huajie.guardian

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 休息期间让**别的应用的声音安静下来**。
 *
 * 需求来源：孩子正看着视频，到点弹了遮罩，画面被挡住了，声音却还在放。
 * 我们既不能杀别人的进程，也不能直接调用别人的播放器接口，所以分三层来做：
 *
 * ── 第一层：音频焦点（不需要任何权限，覆盖绝大多数视频 App）──────────
 * 这是 Android 官方的标准机制：申请 [AudioManager.AUDIOFOCUS_GAIN] 之后，
 * 正在播放的应用会收到 `AUDIOFOCUS_LOSS` 而**主动暂停**（接电话时音乐自动停，
 * 用的就是这套机制）。B 站、腾讯/爱奇艺/优酷、YouTube、抖音、浏览器网页播放器
 * 都实现了它，所以这一层就够用。
 *
 * 额外好处：焦点在我们手上时，**别的应用想重新开播也会被拦住**，
 * 所以视频不会趁休息期间自己续播。
 *
 * ── 第二层：MediaSession 直接按暂停（需要家长授权「通知使用权」）──────
 * 少数 App（部分小厂的播放器、某些游戏的背景音）不响应音频焦点，声音照旧。
 * 这时可以去问系统的媒体控制器，直接下发 `pause()`。这条路要求
 * 本应用被授予「通知使用权」，属于可选增强，没授权就自动跳过。
 *
 * ── 第三层：把媒体音量压到 0（最后兜底，默认关闭）─────────────────
 * 前两层都失效时的暴力手段。之所以放在最后、且要家长主动开启：
 *  1. 它是**系统级**的，休息结束后万一进程被杀，音量可能不会恢复；
 *  2. 它只管"没声音"，视频其实还在解码耗电。
 * 为降低风险，开了之后我们做了三件事：原音量写进 [Prefs]（同步落盘）、
 * 服务每次启动时检查有没有"残留的静音"并恢复、休息期间持续把音量压住。
 *
 * 三层都由 [GuardianService] 在休息期间按秒驱动，互不依赖，谁生效算谁。
 */
object Silence {

    private const val TAG = "Silence"

    /** 重新压制播放器的间隔。太密没必要，太疏则可能漏掉"自动续播下一集" */
    private const val REAPPLY_MS = 3_000L

    /** 焦点被别人抢走后（来电、闹钟），隔多久再抢回来 */
    private const val REFOCUS_DELAY_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())

    /** 应用上下文，用于取系统服务（applicationContext，不会泄漏） */
    private var appCtx: Context? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 当前是否持有音频焦点 */
    var focusHeld = false
        private set

    /** 是否处于"压制中"（休息进行中且至少开了一项） */
    @Volatile
    private var active = false

    /** 供状态上报读取（后台诊断要知道这轮休息到底压住播放没有） */
    val isSuppressing: Boolean get() = active

    private var lastApplyElapsed = 0L

    /** 最近一次压制时暂停了几个播放器；-1 表示拿不到（没授权 / 该 ROM 不支持） */
    var lastPausedCount = -1
        private set

    /** 展示在诊断区的一句话 */
    var lastNote: String = ""
        private set

    // ==================================================================
    // 对外接口
    // ==================================================================

    /**
     * 休息进行中，由 GuardianService 每秒调用。
     *
     * 内部有节流：真正的"暂停别人"每 [REAPPLY_MS] 做一次，所以调用方不必自己计时。
     * 之所以要**反复**做：视频 App 暂停后可能自动播下一集、或者孩子反复点播放，
     * 一次性的压制挡不住。
     */
    fun applyRest(ctx: Context, prefs: Prefs) {
        val app = ctx.applicationContext
        appCtx = app

        val wantFocus = prefs.pauseMediaOnRest
        val wantMute = prefs.muteOnRest
        if (!wantFocus && !wantMute) {
            // 家长在休息期间把两项都关了 → 立刻解除压制
            if (active) endRest(app, prefs)
            return
        }
        active = true

        if (wantFocus) ensureFocus(app)
        // 家长在休息途中把"兜底静音"关掉 → 立刻把音量还回去，别等到休息结束
        if (wantMute) mute(app, prefs) else unmute(app, prefs)

        val now = SystemClock.elapsedRealtime()
        if (now - lastApplyElapsed < REAPPLY_MS) return
        lastApplyElapsed = now

        if (wantFocus) lastPausedCount = pausePlayingSessions(app)
        // 再压一次音量：防止孩子用音量键把声音调回来
        if (wantMute) mute(app, prefs)
        updateNote(app)
    }

    /** 休息结束 / 守护关闭：放开焦点，恢复音量。可以重复调用。 */
    fun endRest(ctx: Context, prefs: Prefs) {
        val app = ctx.applicationContext
        appCtx = app
        active = false
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        unmute(app, prefs)
        lastPausedCount = -1
        // 归零，这样下一轮休息的第一秒就会立刻压制，不用等节流窗口过去
        lastApplyElapsed = 0L
        if (lastNote.isNotEmpty()) lastNote = describe(app)
    }

    /**
     * 兜底修复：如果上一次运行把媒体音量压成了 0、然后进程被强杀，
     * 音量会一直停在 0（家长会以为设备坏了）。服务每次启动都检查一遍。
     *
     * 如果现在仍在休息期内就不用管——后面的 applyRest 会继续压制，
     * 等休息正常结束时会走 unmute 恢复。
     */
    fun recoverIfStuck(ctx: Context, prefs: Prefs) {
        if (!prefs.mutedByUs) return
        appCtx = ctx.applicationContext
        if (prefs.lockedUntilWall > System.currentTimeMillis()) return
        Log.i(TAG, "发现残留的静音状态，正在恢复音量")
        unmute(ctx.applicationContext, prefs)
    }

    /** 家长是否已经授予「通知使用权」（决定第二层能不能用） */
    fun hasSessionAccess(ctx: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }.getOrDefault(false)

    /** 诊断区展示用 */
    fun describe(ctx: Context): String = buildString {
        append(if (focusHeld) "音频焦点持有中" else "音频焦点未持有")
        append(" · 通知使用权")
        append(if (hasSessionAccess(ctx)) "已授" else "未授")
        when {
            lastPausedCount > 0 -> append(" · 已暂停 $lastPausedCount 个播放器")
            lastPausedCount == 0 -> append(" · 当时没有正在播放的")
            else -> Unit
        }
    }

    // ==================================================================
    // 第一层：音频焦点
    // ==================================================================

    private fun am(ctx: Context): AudioManager? {
        audioManager?.let { return it }
        val m = ctx.getSystemService(AudioManager::class.java) ?: return null
        audioManager = m
        return m
    }

    private fun ensureFocus(ctx: Context): Boolean {
        if (focusHeld) return true
        val m = am(ctx) ?: return false

        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 声明成"媒体"，这样拿到焦点时对方收到的是 AUDIOFOCUS_LOSS（暂停），
                    // 而不是 TRANSIENT（压低音量）—— 我们要的是停，不是小声。
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
            .build()
            .also { focusRequest = it }

        val r = runCatching { m.requestAudioFocus(req) }.getOrElse {
            Log.w(TAG, "requestAudioFocus 失败", it)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocus() {
        // 先把状态改掉：即使下面抛异常，也不会误以为还持有焦点
        focusHeld = false
        val m = audioManager ?: return
        val req = focusRequest ?: return
        runCatching { m.abandonAudioFocusRequest(req) }
    }

    /**
     * 焦点变化回调。
     *
     * 注意：我们不处理"要不要降低音量"这类情形——我们本身不放任何声音，
     * 所以别人来抢焦点时（来电、系统闹钟）让出去就好，[REFOCUS_DELAY_MS] 后再抢回来。
     */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                val ctx = appCtx
                if (active && ctx != null) {
                    handler.postDelayed({
                        if (active) ensureFocus(ctx)
                    }, REFOCUS_DELAY_MS)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusHeld = false
            AudioManager.AUDIOFOCUS_GAIN -> focusHeld = true
            else -> Unit
        }
    }

    // ==================================================================
    // 第二层：直接让播放器暂停
    // ==================================================================

    /**
     * 遍历系统里所有活跃的媒体会话，把正在播放的暂停掉。
     *
     * @return 暂停了几个；-1 表示没有权限或该 ROM 不支持
     */
    private fun pausePlayingSessions(ctx: Context): Int {
        if (!hasSessionAccess(ctx)) return -1
        val msm = runCatching {
            ctx.getSystemService(MediaSessionManager::class.java)
        }.getOrNull() ?: return -1
        val me = ComponentName(ctx, MediaWatchService::class.java)

        return try {
            var n = 0
            for (c in msm.getActiveSessions(me)) {
                val st = runCatching { c.playbackState?.state }.getOrNull()
                if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                    runCatching { c.transportControls.pause() }
                    n++
                }
            }
            n
        } catch (t: Throwable) {
            // 没授权时是 SecurityException；个别 ROM 会抛别的，一律当作不可用
            Log.w(TAG, "getActiveSessions 失败：${t.javaClass.simpleName}")
            -1
        }
    }

    // ==================================================================
    // 第三层：媒体音量兜底
    // ==================================================================

    private fun mute(ctx: Context, prefs: Prefs) {
        val m = am(ctx) ?: return
        if (!prefs.mutedByUs) {
            val cur = runCatching { m.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
            // 本来就是静音（或振动模式）就不用记账，否则恢复时会把用户原本的状态改掉
            if (cur <= 0) return
            // 原音量与"是我们静的音"这个标记必须**一起**同步落盘：
            // 只落标记不落音量，进程被杀后就再也恢复不到原来的大小了
            prefs.commitMute(cur)
        }
        val now = runCatching { m.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (now != 0) runCatching { m.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
    }

    private fun unmute(ctx: Context, prefs: Prefs) {
        if (!prefs.mutedByUs) return
        val saved = prefs.mutedSavedVolume
        // 先清标记再调音量：即使 setStreamVolume 抛异常，也不会留下"永远静音"
        prefs.clearMute()
        if (saved <= 0) return
        val m = am(ctx) ?: return
        runCatching { m.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
    }

    private fun updateNote(ctx: Context) {
        lastNote = describe(ctx)
    }
}
