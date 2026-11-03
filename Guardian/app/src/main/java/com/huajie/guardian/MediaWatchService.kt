package com.huajie.guardian

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 一个**什么都不做**的通知监听服务，存在的唯一理由是拿权限。
 *
 * 背景：休息时要"暂停"别的 App 正在放的视频，Android 上唯一可行的公开接口是
 * [android.media.session.MediaSessionManager.getActiveSessions] —— 但调用它
 * 必须让系统相信本应用有权查看媒体会话，而这个资格来自「通知使用权」。
 *
 * 所以这里挂一个空的 NotificationListenerService：
 *  - 拿到「通知使用权」后，[Silence] 就可以直接对播放器下发 pause()；
 *  - 没有授权时它不会被系统绑定，[Silence] 自动跳过这一层，不影响任何功能。
 *
 * **本服务不读取、不上传、不保存任何通知内容**：onNotificationPosted 等回调
 * 一个都没有重写，系统把通知递过来时不会有任何代码去看它。
 */
class MediaWatchService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Log.i(TAG, "通知使用权已连接，可以直接暂停其它播放器")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        Log.w(TAG, "通知使用权已断开")
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaWatchService"

        /** 系统是否已经把本服务绑定上（等价于家长已授权，但比查设置更快） */
        @Volatile
        var connected = false
    }
}
