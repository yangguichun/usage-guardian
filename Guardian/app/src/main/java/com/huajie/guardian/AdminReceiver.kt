package com.huajie.guardian

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 设备管理器。
 *
 * 激活之后能带来三个很实际的好处，而且**不需要 Root、不需要恢复出厂**：
 *
 *  1. 系统「设置 → 应用 → 使用守护」里的**「强行停止」按钮会变成灰色**，点不动。
 *     这是最关键的一条：强行停止会把应用置为 stopped 状态并清空全部闹钟，
 *     任何保活手段都会失效，所以必须从源头堵住。
 *  2. 卸载前必须先到「设备管理器」里取消激活，多一道门槛。
 *  3. 用户点「取消激活」时，系统会先弹出我们在 [onDisableRequested] 里返回的警告文案。
 *
 * 需要说清楚的边界：这只是**提高门槛**，不是绝对防御。孩子如果知道路径，
 * 仍然可以自己去「设置 → 安全 → 设备管理器」取消激活。要彻底锁死，
 * 只能用「设备所有者(Device Owner)」模式，代价是需要恢复出厂设置，详见 README。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.admin_enabled, Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // 孩子成功取消了设备管理——这是"强行停止"的前置动作（多数 ROM 上，
        // 管理员在时那个按钮是灰的，必须先取消才能点）。所以它本身就该算一次违规。
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val reason = context.getString(R.string.kill_reason_admin_removed)
        prefs.markKill(reason)
        prefs.noteTamper(now, reason)
        Toast.makeText(context, R.string.admin_disabled, Toast.LENGTH_LONG).show()

        // 这一步很关键：此刻进程肯定还活着（我们正在被回调），
        // 所以欠账可以马上兑现成一次休息，不必等下一次启动。
        if (prefs.enabled && prefs.tamperPolicy == Prefs.TAMPER_REST) {
            GuardianService.start(context)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}
