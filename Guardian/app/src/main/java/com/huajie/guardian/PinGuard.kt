package com.huajie.guardian

/**
 * 密码尝试限流。
 *
 * 为什么需要它（v1.7 才补上的）：
 * v1.7 之前遮罩上的系统键盘根本弹不出来，孩子连试都没法试；
 * 现在换成了应用自绘的键盘、输入变得顺畅，**反而打开了"一个个试过去"这条路**。
 * 4 位数字只有 1 万种组合，必须限流。
 *
 * 策略：连续输错 [MAX_TRIES] 次 → 冷却 [LOCK_MS]，冷却期内一律不校验（也不再累加计数）。
 * 计数与冷却时间都写进 SharedPreferences，**杀掉应用重启也不会清零**。
 */
object PinGuard {

    /** 允许连续输错几次 */
    const val MAX_TRIES = 5

    /** 触发冷却后锁多久 */
    const val LOCK_MS = 60_000L

    /** 还要等多少毫秒才能再试；0 表示现在可以试 */
    fun lockedLeftMs(prefs: Prefs): Long =
        (prefs.pinLockedUntilWall - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 输错一次；返回是否因此进入了冷却 */
    fun onWrong(prefs: Prefs): Boolean {
        val fails = prefs.pinFails + 1
        if (fails >= MAX_TRIES) {
            prefs.pinFails = 0
            prefs.pinLockedUntilWall = System.currentTimeMillis() + LOCK_MS
            return true
        }
        prefs.pinFails = fails
        return false
    }

    /** 验证通过：清空计数与冷却 */
    fun onOk(prefs: Prefs) {
        prefs.pinFails = 0
        prefs.pinLockedUntilWall = 0L
    }

    /** 剩余冷却秒数（向上取整，至少 1 秒） */
    fun lockedLeftSeconds(prefs: Prefs): Int =
        ((lockedLeftMs(prefs) + 999L) / 1000L).toInt()
}
