package com.huajie.guardian

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 进程保活调度器 —— 对付"孩子从最近任务里把应用结束掉"。
 *
 * ## 设计原则：不依赖任何"回调"，只依赖系统侧的闹钟
 *
 * v1.3 之前是靠 `onTaskRemoved()` / `onDestroy()` 收到通知后再排复活闹钟。
 * 真机实测发现：国产 ROM（ColorOS 等）结束应用时是**直接 SIGKILL**，
 * 这两个回调**一个都不会执行**，于是复活闹钟根本没机会被排上，
 * 只剩一条 60 秒的心跳做兜底 —— 表现就是"结束之后十几秒毫无动静"。
 *
 * 所以现在改成**永续自检**：
 *
 * ```
 * 系统里永远挂着一个 15 秒的精确闹钟
 *        │
 *   到点触发 GuardianService.onStartCommand()
 *        │
 *   ├── 服务还活着 → 顺手把下一次闹钟排上（自我续期）
 *   └── 服务已经死了 → 直接把前台服务拉起来（复活）
 * ```
 *
 * 因为**每次启动服务都会重排**，这条链就永远不会断，
 * 和 ROM 有没有回调、回调了什么，完全无关。
 *
 * ## 两条通道
 *
 * | 通道 | 周期 | 类型 | 作用 |
 * | --- | --- | --- | --- |
 * | 主通道 `REQ_WATCHDOG` | 15 秒 | `setExactAndAllowWhileIdle` | 正常情况下的快速复活 |
 * | 强通道 `REQ_SAFETY` | 5 分钟 | `setAlarmClock` | 主通道被省电策略延迟时的保险 |
 *
 * **强通道为什么用 `setAlarmClock`**：系统的省电策略（Doze / App Standby / 国产 ROM
 * 的后台管控）会延迟普通闹钟，但"用户设定的闹钟"是唯一不会被延迟的类型，
 * 而且它触发时系统会给应用一个**临时白名单**，允许启动前台服务。
 * 代价是状态栏会常驻一个小闹钟图标 —— 对"不能被破解"这个目标来说值得。
 *
 * **唯一挡不住的情况**：在系统设置里点「强行停止」。它会把应用置为 stopped 状态
 * 并**清空全部闹钟与任务**，任何保活代码都失效。这正是要用「设备管理员」
 * 把那颗按钮变灰的原因（见 [AdminReceiver]）。
 * 启动时可以通过 [isWatchdogArmed] 反推出"闹钟是不是被清空了"，见 [GuardianService]。
 */
object KeepAlive {

    private const val TAG = "GuardianKeepAlive"

    // 闹钟请求码
    private const val REQ_WATCHDOG = 9001
    private const val REQ_SAFETY = 9002
    private const val REQ_DEADLINE = 9003
    /** 「停用时段」边界闹钟：到点自动锁上 / 到点自动放开 */
    private const val REQ_QUIET = 9004
    private const val REQ_RELAUNCH_1 = 9011
    private const val REQ_RELAUNCH_2 = 9012
    private const val REQ_RELAUNCH_3 = 9013

    private const val JOB_RESURRECT = 8001
    private const val JOB_WATCHDOG = 8002

    /** 主保活通道周期：服务每次被唤醒都会重排，所以链不会断 */
    const val WATCHDOG_MS = 15_000L

    /** 强保活通道周期 */
    const val SAFETY_MS = 5 * 60_000L

    /**
     * 两次心跳间隔超过这个值，就认为守护进程期间被结束过。
     * 只用作**辅助**信号——快速被杀又快速重启时间隔很短，判不出来，
     * 真正可靠的是 [Prefs.serviceAlive] 那个"未正常退出"标记。
     */
    const val DEATH_THRESHOLD_MS = 3 * 60_000L

    /** 检测到任务被划掉后，第一条复活通道的延迟 */
    const val RELAUNCH_DELAY_MS = 500L

    // ==================================================================
    // PendingIntent 工具
    // ==================================================================

    private fun serviceIntent(
        ctx: Context,
        action: String = GuardianService.ACTION_HEARTBEAT
    ) = Intent(ctx, GuardianService::class.java).setAction(action)

    /** 取（必要时创建）指向守护服务的 PendingIntent */
    private fun pi(
        ctx: Context,
        req: Int,
        action: String = GuardianService.ACTION_HEARTBEAT
    ): PendingIntent {
        val i = serviceIntent(ctx, action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26+ 用 getForegroundService：闹钟触发时可直接把前台服务拉起来，
            // 不需要先进后台服务、再提升为前台
            PendingIntent.getForegroundService(ctx, req, i, flags)
        } else {
            PendingIntent.getService(ctx, req, i, flags)
        }
    }

    /** 只在已存在时返回，不创建。用于查询"我们挂的闹钟还在不在" */
    private fun existingPi(ctx: Context, req: Int): PendingIntent? {
        val i = serviceIntent(ctx)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(ctx, req, i, flags)
            } else {
                PendingIntent.getService(ctx, req, i, flags)
            }
        }.getOrNull()
    }

    /**
     * 系统里是否还挂着我们的主保活闹钟。
     *
     * `FLAG_NO_CREATE` 查不到 → 这个 PendingIntent 已经被系统清掉了。
     * 一次性闹钟触发后 PendingIntent 依然存在（不会自动取消），
     * 所以"查不到"只可能是被**强行停止**清空的。
     */
    fun isWatchdogArmed(ctx: Context): Boolean =
        runCatching { existingPi(ctx, REQ_WATCHDOG) != null }.getOrDefault(true)

    /** 当前是否被允许使用精确闹钟 */
    fun canScheduleExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            }.getOrDefault(false)
        } else {
            true
        }

    // ==================================================================
    // 排闹钟
    // ==================================================================

    /** 精确闹钟；取不到精确权限时退化为不精确闹钟，至少不会完全失效 */
    private fun setExact(ctx: Context, atWall: Long, req: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            val p = pi(ctx, req)
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, p)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWall, p)
            }
        }.onFailure { Log.e(TAG, "setExact(req=$req) 失败", it) }
    }

    /**
     * 闹钟时钟（`setAlarmClock`）—— 系统不会对它做省电延迟，触发时还会给应用
     * 一个临时白名单，允许在后台启动前台服务。保活链路上的最后一道保险。
     */
    private fun setAlarmClock(ctx: Context, atWall: Long, req: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atWall, show), pi(ctx, req))
        }.onFailure {
            // 部分 ROM 需要 SCHEDULE_EXACT_ALARM 才允许 setAlarmClock，退化即可
            Log.w(TAG, "setAlarmClock 不可用，退化为精确闹钟", it)
            setExact(ctx, atWall, req)
        }
    }

    /**
     * 布置/续期保活通道。**守护服务每次启动都必须调用**——
     * 闹钟是一次性的，不重排链条就断了。
     */
    fun armWatchdog(ctx: Context) {
        val now = System.currentTimeMillis()
        setExact(ctx, now + WATCHDOG_MS, REQ_WATCHDOG)

        // 强通道：只在快到点时才重排，否则每 15 秒重排一次会让状态栏闹钟图标不停闪
        val prefs = Prefs(ctx)
        if (prefs.safetyAtWall - now < 30_000L) {
            val at = now + SAFETY_MS
            setAlarmClock(ctx, at, REQ_SAFETY)
            prefs.safetyAtWall = at
        }
    }

    /**
     * 「使用上限到点」闹钟。
     *
     * ## 为什么需要单独一条
     *
     * 守护的正常节拍是主线程每秒一次 tick（见 [GuardianService]）。但前台服务
     * 只保证进程不被回收，**保证不了它一直在跑**：ColorOS 的「应用速冻」、
     * 部分 ROM 的「睡眠待机优化」会把后台进程整个冻结，tick 随之停摆。
     * 这时通知里的数字不再刷新，更要紧的是"用满 5 分钟该弹遮罩"也永远不会发生。
     *
     * 闹钟是系统侧的，进程被冻结也能投递（投递时系统会临时解冻并给白名单），
     * 所以这里把"上限到点那一刻"直接交给系统。即便 tick 全程没跑，
     * 到点也会把服务叫醒，由 [GuardianService.step] 判定超时并弹遮罩。
     *
     * 用 `setAlarmClock` 而不是普通精确闹钟：只有它不会被 Doze / 国产 ROM 的
     * 后台管控延迟。状态栏本来就有强保活通道的小闹钟图标，不额外增加干扰。
     *
     * **不需要频繁重排**：计时正常时 `now + 剩余时长` 恒等于同一个时刻，
     * 只有熄屏暂停、改参数、休息结束这些情况才会变化，所以用 15 秒阈值挡掉抖动。
     */
    fun armDeadline(ctx: Context, atWall: Long) {
        val prefs = Prefs(ctx)
        val cur = prefs.deadlineAtWall
        if (cur > 0L && kotlin.math.abs(cur - atWall) < 15_000L) return
        prefs.deadlineAtWall = atWall
        // 不能让目标时间落在过去，否则部分 ROM 会立刻触发一次
        setAlarmClock(ctx, maxOf(atWall, System.currentTimeMillis() + 1_000L), REQ_DEADLINE)
    }

    /** 撤掉到点闹钟（熄屏暂停、暂停守护、进入休息时都要撤） */
    fun cancelDeadline(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.deadlineAtWall == 0L) return
        prefs.deadlineAtWall = 0L
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am?.cancel(pi(ctx, REQ_DEADLINE, GuardianService.ACTION_DEADLINE))
        }
    }

    /**
     * 「停用时段边界」闹钟。
     *
     * 停用的两个边界（到点自动锁、到点自动放开）都必须由系统闹钟兜住：
     * 主线程 tick 靠不住 —— 夜里屏幕熄着，进程可能整晚被「应用速冻」冻住，
     * tick 一次都不跑；只有闹钟能把服务叫醒。
     *
     * 用 `setAlarmClock`：22:00 准时锁屏这件事对家长是**承诺**，
     * 被 Doze 或 ROM 后台管控延迟十几分钟就等于没做。
     * 代价是状态栏多一个小闹钟图标（本来强保活通道已经有一个了）。
     *
     * @param atWall 边界的墙钟毫秒。与已排的值相差 15 秒内不重排，
     *               否则每秒 tick 都重排会让状态栏图标不停闪。
     */
    fun armQuiet(ctx: Context, atWall: Long) {
        val prefs = Prefs(ctx)
        val cur = prefs.quietAlarmAtWall
        if (cur > 0L && kotlin.math.abs(cur - atWall) < 15_000L) return
        prefs.quietAlarmAtWall = atWall
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = maxOf(atWall, System.currentTimeMillis() + 1_000L)
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(at, show),
                pi(ctx, REQ_QUIET, GuardianService.ACTION_QUIET)
            )
        }.onFailure {
            Log.w(TAG, "停用边界闹钟 setAlarmClock 不可用，退化为精确闹钟", it)
            runCatching {
                val p = pi(ctx, REQ_QUIET, GuardianService.ACTION_QUIET)
                val at = maxOf(atWall, System.currentTimeMillis() + 1_000L)
                if (canScheduleExact(ctx)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
                }
            }.onFailure { Log.e(TAG, "停用边界闹钟排不上", it) }
        }
    }

    /** 撤掉停用边界闹钟（规则改了、家长关掉停用、守护暂停时都要撤） */
    fun cancelQuiet(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.quietAlarmAtWall == 0L) return
        prefs.quietAlarmAtWall = 0L
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java)
            am?.cancel(pi(ctx, REQ_QUIET, GuardianService.ACTION_QUIET))
        }
    }

    /**
     * 排一条"复活链"：0.5 秒 / 3 秒 / 20 秒 三次机会。
     *
     * 用于 `onTaskRemoved()` / `onDestroy()` 这种"ROM 还愿意回调"的正常情况，
     * 比 15 秒的主通道更快。**但不能只依赖它**——实测国产 ROM 这两个回调
     * 都可能不执行，所以主通道才是根本保障。
     */
    fun chainRelaunch(ctx: Context) {
        val now = System.currentTimeMillis()
        setExact(ctx, now + 500L, REQ_RELAUNCH_1)
        setExact(ctx, now + 3_000L, REQ_RELAUNCH_2)
        setExact(ctx, now + 20_000L, REQ_RELAUNCH_3)
    }

    /** 只排最近的那一条 */
    fun relaunchSoon(ctx: Context, delayMs: Long = RELAUNCH_DELAY_MS) {
        setExact(ctx, System.currentTimeMillis() + delayMs, REQ_RELAUNCH_1)
    }

    // ==================================================================
    // JobScheduler 通道：与闹钟完全独立的第二条复活路径
    // ==================================================================

    private fun jobComponent(ctx: Context) = ComponentName(ctx, ResurrectJobService::class.java)

    /** 排一个"马上检查一次"的任务 */
    fun scheduleResurrectJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        runCatching {
            val info = JobInfo.Builder(JOB_RESURRECT, jobComponent(ctx))
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(5_000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }.onFailure { Log.e(TAG, "scheduleResurrectJob 失败", it) }
    }

    /** 排一个 15 分钟的周期性看门狗，长期盯着服务是否还活着 */
    fun ensurePeriodicJob(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        runCatching {
            val info = JobInfo.Builder(JOB_WATCHDOG, jobComponent(ctx))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }.onFailure { Log.e(TAG, "ensurePeriodicJob 失败", it) }
    }

    /** 家长主动关闭守护时调用，把闹钟与任务全部撤掉，避免它自己又活过来 */
    fun cancelAll(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java)
            for (req in intArrayOf(
                REQ_WATCHDOG, REQ_SAFETY, REQ_DEADLINE,
                REQ_RELAUNCH_1, REQ_RELAUNCH_2, REQ_RELAUNCH_3
            )) {
                am?.cancel(pi(ctx, req))
            }
            // 到点闹钟用了自定义 action，必须按同一个 action 才能取消
            am?.cancel(pi(ctx, REQ_DEADLINE, GuardianService.ACTION_DEADLINE))
            // 停用边界闹钟同理（action 不同，按 requestCode 取消是取消不掉的）
            am?.cancel(pi(ctx, REQ_QUIET, GuardianService.ACTION_QUIET))
        }
        runCatching {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js?.cancel(JOB_RESURRECT)
            js?.cancel(JOB_WATCHDOG)
        }
        val p = Prefs(ctx)
        p.safetyAtWall = 0L
        p.deadlineAtWall = 0L
        p.quietAlarmAtWall = 0L
        // 主动撤掉之后，下次启动就不该再报"闹钟被清空了 = 被强行停止"
        // （那个判据只在"家长没主动关过"的前提下成立）
        p.watchdogArmed = false
    }
}
