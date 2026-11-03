package com.huajie.guardian

import android.app.Application

/**
 * 只做一件事：装上崩溃记录钩子。
 *
 * 必须在 Application 里装而不是 MainActivity 里：崩溃可能发生在服务、
 * 广播接收器、闹钟唤醒的路径上，那些场景 MainActivity 根本没启动过。
 */
class GuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}
