package com.huajie.guardian

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 复活任务 —— 闹钟之外的第二条独立复活通道。
 *
 * JobScheduler 的任务和 AlarmManager 的闹钟一样注册在系统侧，
 * 进程被系统回收后，到点系统会重新拉起进程执行 onStartJob()。
 *
 * 两者一起用的原因：不同 ROM 对这两种调度的清理策略不一样，
 * 有的会清闹钟但保留任务，有的反之。都排上，命中概率更高。
 *
 * 与 [GuardianService] 在同一个进程（未声明 android:process），
 * 所以能直接读到 GuardianService.running 这个进程内静态标记。
 */
class ResurrectJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Prefs(this)
        if (prefs.enabled) {
            // 服务可能已经被系统清掉了，补一次启动。
            // GuardianService.start() 内部已经吞掉异常并记录原因，
            // 不能让它抛出去——JobService 一崩，这条通道也废了。
            if (!GuardianService.running) {
                GuardianService.start(this)
            }
            // 顺手把保活通道续期，保持链条不断
            runCatching { KeepAlive.armWatchdog(this) }
                .onFailure { prefs.lastStartError = "armWatchdog: " + it.javaClass.simpleName }
            runCatching { KeepAlive.ensurePeriodicJob(this) }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = Prefs(this).enabled
}
