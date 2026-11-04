package com.huajie.guardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 强制休息的**兜底界面**。
 *
 * 为什么需要它：部分国产 ROM（OPPO / vivo / 小米）会拦截或延迟
 * TYPE_APPLICATION_OVERLAY 悬浮窗的添加，导致遮罩加不上。
 * 这时退化成这个真正的全屏 Activity，同样能把屏幕挡死。
 *
 * 只要能用悬浮窗，服务就不会启动这个 Activity（悬浮窗更防逃逸）。
 */
class LockActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())

    private var finishingSelf = false

    private val poll = object : Runnable {
        override fun run() {
            // 停用时段优先：这段时间里 lockedUntilWall 可能是 0，
            // 但界面必须继续挡着，所以判断依据不能只看休息
            if (GuardianService.sInQuiet) {
                RestUi.render(
                    root, this@LockActivity,
                    GuardianService.sQuietLeftMs, 0,
                    getString(R.string.quiet_plan, GuardianService.sQuietLabel),
                    RestUi.Mode.QUIET
                )
                handler.postDelayed(this, 500L)
                return
            }
            if (GuardianService.sDayLocked) {
                RestUi.render(
                    root, this@LockActivity,
                    GuardianService.sDayLeftMs, 0,
                    getString(
                        R.string.day_lock_plan,
                        prefs.dayLimitMinutes,
                        (prefs.todayUsedMs / 60_000L).toInt()
                    ),
                    RestUi.Mode.DAY
                )
                handler.postDelayed(this, 500L)
                return
            }
            val left = prefs.lockedUntilWall - System.currentTimeMillis()
            if (left <= 0L) {
                finishSelf()
                return
            }
            GuardianService.sRestLeftMs = left
            GuardianService.sResting = true
            RestUi.render(root, this@LockActivity, left, prefs.restCount, prefs.restPlan)
            handler.postDelayed(this, 500L)
        }
    }

    /** 此刻是否应该继续把屏幕挡着（休息 / 停用时段 / 每日额度，任一成立即可） */
    private fun keepLocked(): Boolean =
        GuardianService.sInQuiet || GuardianService.sDayLocked ||
            prefs.lockedUntilWall > System.currentTimeMillis()

    private val restEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishSelf()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // 锁屏上也能显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }

        setContentView(R.layout.overlay_lock)
        root = findViewById(R.id.restRoot)

        hideSystemBars()

        RestUi.bind(
            root = root,
            prefs = prefs,
            onSkip = {
                // 这里原来自己复制了一份"跳过"逻辑（清 prefs + finish 自己），
                // 和 GuardianService.skipRest() 是两份实现 —— 升级到 2.4.1 后
                // 服务那份会把本轮起点前移、这份漏了，于是点完跳过下一秒又被判超时，
                // 遮罩立刻弹回来。现在统一交给服务处理，两边不会再走偏。
                val wasQuiet = GuardianService.sInQuiet
                val wasDay = GuardianService.sDayLocked
                GuardianService.skipFromMask(this)
                Toast.makeText(
                    this,
                    when {
                        wasDay -> getString(R.string.day_unlock_done, RestUi.DAY_UNLOCK_MIN)
                        wasQuiet -> getString(R.string.quiet_skipped)
                        else -> getString(R.string.rest_skipped)
                    },
                    Toast.LENGTH_LONG
                ).show()
                finishSelf()
            },
            onPauseGuard = {
                prefs.enabled = false
                prefs.resetSession()
                prefs.lockedUntilWall = 0L
                GuardianService.sResting = false
                GuardianService.sInQuiet = false
                Toast.makeText(this, "已暂停守护", Toast.LENGTH_LONG).show()
                GuardianService.stop(this)
                finishSelf()
            },
            onMessage = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )

        // 休息结束的即时通知
        val filter = IntentFilter(GuardianService.ACTION_REST_END)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(restEndReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(restEndReceiver, filter)
        }

        // 进入时立刻渲染一次，避免半秒空白
        if (GuardianService.sDayLocked) {
            RestUi.render(
                root, this,
                GuardianService.sDayLeftMs, 0,
                getString(
                    R.string.day_lock_plan,
                    prefs.dayLimitMinutes,
                    (prefs.todayUsedMs / 60_000L).toInt()
                ),
                RestUi.Mode.DAY
            )
        } else if (GuardianService.sInQuiet) {
            RestUi.render(
                root, this,
                GuardianService.sQuietLeftMs, 0,
                getString(R.string.quiet_plan, GuardianService.sQuietLabel),
                RestUi.Mode.QUIET
            )
        } else {
            RestUi.render(
                root, this,
                (prefs.lockedUntilWall - System.currentTimeMillis()).coerceAtLeast(0L),
                prefs.restCount,
                prefs.restPlan
            )
        }
    }

    override fun onResume() {
        super.onResume()
        finishingSelf = false
        isForeground = true
        handler.removeCallbacks(poll)
        handler.post(poll)
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        handler.removeCallbacks(poll)
        // 孩子一旦切走（Home / 最近任务），马上把自己拉回来
        if (!finishingSelf && keepLocked()) {
            handler.postDelayed(relaunch, 120L)
        }
    }

    override fun onDestroy() {
        isForeground = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(restEndReceiver) }
        super.onDestroy()
    }

    private val relaunch = Runnable {
        if (!finishingSelf && keepLocked()) {
            runCatching {
                startActivity(
                    Intent(this, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 返回键、菜单键、最近任务键、音量键一律吞掉 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH,
            // 休息期间不该再调声音：否则孩子按一下音量键就把"兜底静音"顶掉了
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 什么都不做：不许退出
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!finishingSelf && keepLocked()) {
            handler.postDelayed(relaunch, 120L)
        }
    }

    private fun finishSelf() {
        finishingSelf = true
        handler.removeCallbacksAndMessages(null)
        finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    companion object {
        /** 兜底界面当前是否在前台，服务据此决定要不要再拉一次 */
        @Volatile var isForeground = false
    }
}
