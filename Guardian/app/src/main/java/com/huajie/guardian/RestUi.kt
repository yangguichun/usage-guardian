package com.huajie.guardian

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 「强制休息」界面的公共逻辑。
 *
 * 同一个布局 overlay_lock.xml 会被两处使用：
 *  - GuardianService：以系统悬浮窗形式加载（首选，最防逃逸）
 *  - LockActivity：  以全屏 Activity 形式加载（兜底，防国产 ROM 拦截悬浮窗）
 * 所以绑定和渲染逻辑必须共用，避免两边不一致。
 *
 * 密码输入一律使用 [PinPad]（应用自绘的数字键盘），**不使用系统输入法**：
 * 遮罩是铺满全屏的系统悬浮窗，弹在它里面的系统键盘会被遮罩挤掉，
 * 表现就是"键盘闪一下就没了"。
 */
object RestUi {

    /** 家长验过密码后，操作按钮的有效时间（秒）。超时重新验证，防止家长走开后孩子顺手点。 */
    private const val ACTION_WINDOW_S = 60

    /**
     * 每日额度用完时，家长用密码「临时放行」的分钟数。
     *
     * 固定 15 分钟而不是让家长现场填：解锁场景几乎都是"临时做一下作业"，
     * 多一步输入只会让家长在遮罩前手忙脚乱；而且短时长也限制了孩子
     * 反复找家长解锁的空间。
     */
    const val DAY_UNLOCK_MIN = 15

    /**
     * 遮罩的三种形态。
     *
     * 共用同一个布局，只换文案 —— 多写一份布局，两边（悬浮窗 / LockActivity）
     * 的绑定逻辑只好跟着复制一份，很快就不同步了。
     *
     * DAY 是 v2.5.0 的「每日总时长用完」：它和 REST 的区别不只是文案，
     * 更关键的是**没有到点自动解除**，只能等第二天 0 点或家长放行。
     */
    enum class Mode { REST, QUIET, DAY, QUIZ, QUIZWAIT }

    fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    /**
     * 超过一小时也能正确显示的格式（[fmt] 只到分钟，用于休息倒计时）。
     *
     * 每日额度锁要一直显示到明天 0 点，最长能剩十几个小时，
     * 用 [fmt] 会变成 "742:31" 这种看着像坏了的东西。
     */
    fun fmtHms(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /**
     * 家长入口的文案、颜色与可见性。
     *
     * 「每日额度用完」这一屏不会自己解除，如果入口还像休息屏那样只是一行
     * 深色小字，家长根本不知道该去哪儿输密码，只能干等到第二天 0 点 ——
     * 所以这一屏把按钮改成明确的「家长解锁（临时放行 15 分钟）」并换成实色。
     *
     * 没设密码时也把按钮放出来（点了会提示去设置），否则家长面对的是
     * 一个完全没有解释的死屏；其余两种模式维持"没密码就不给入口"。
     */
    private fun syncParentEntry(root: View, ctx: Context, mode: Mode) {
        val btn = root.findViewById<Button>(R.id.btnParentAction) ?: return
        val pinPanel = root.findViewById<View>(R.id.pinPanel)
        val hasPin = Prefs(ctx).pin.isNotEmpty()

        btn.text = if (mode == Mode.DAY) {
            ctx.getString(R.string.day_parent_unlock, DAY_UNLOCK_MIN)
        } else {
            ctx.getString(R.string.parent_action)
        }
        val show = pinPanel?.visibility != View.VISIBLE && (hasPin || mode == Mode.DAY)
        btn.visibility = if (show) View.VISIBLE else View.GONE
        btn.backgroundTintList = ColorStateList.valueOf(
            if (mode == Mode.DAY) 0xFF2E7D6B.toInt() else 0xFF25304D.toInt()
        )
        root.findViewById<TextView>(R.id.tvParentLead)?.visibility =
            if (mode == Mode.DAY) View.VISIBLE else View.GONE
    }

    /**
     * 每日额度锁的剩余时间。
     *
     * 这里不能用 [fmtHms]：「08:23:45」这种 8 位字符串在 76sp 下比 360dp 的手机还宽，
     * 会被系统折成两行；而且家长看到「到明天 0 点还有多久」，本来就要的是
     * 「8 小时 23 分」，不是秒表。
     */
    fun fmtDayLeft(ms: Long): String {
        val min = (ms.coerceAtLeast(0L) + 59_999L) / 60_000L
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分"
            h > 0 -> "$h 小时"
            else -> "$m 分"
        }
    }

    /** 绑定遮罩上的全部交互 */
    fun bind(
        root: View,
        prefs: Prefs,
        onSkip: () -> Unit,
        onPauseGuard: () -> Unit,
        onMessage: (String) -> Unit
    ) {
        val ctx = root.context
        val btnParent = root.findViewById<Button>(R.id.btnParentAction)
        val tvHint = root.findViewById<TextView>(R.id.tvRestHint)
        val pinPanel = root.findViewById<View>(R.id.pinPanel)
        val tvDots = root.findViewById<TextView>(R.id.tvPinDots)
        val keypadHost = root.findViewById<LinearLayout>(R.id.keypadHost)
        val tvTimer = root.findViewById<TextView>(R.id.tvPinTimer)
        val pinActions = root.findViewById<View>(R.id.pinActions)
        val btnSkip = root.findViewById<Button>(R.id.btnSkipRest)
        val btnPause = root.findViewById<Button>(R.id.btnPauseGuard)

        val handler = Handler(Looper.getMainLooper())

        /** 数字键盘实例，供"收起面板/输入错误"时清空缓冲 */
        var input: PinPad.Input? = null

        pinPanel.visibility = View.GONE
        pinActions.visibility = View.GONE
        tvTimer.visibility = View.GONE
        // 没设密码时默认不给入口（孩子连点都点不到）；
        // 但「每日额度」那一屏是例外，见 syncParentEntry
        syncParentEntry(root, ctx, Mode.REST)

        /** 收起密码面板，回到"只能看到倒计时"的状态 */
        fun relock() {
            handler.removeCallbacksAndMessages(null)
            pinPanel.visibility = View.GONE
            pinActions.visibility = View.GONE
            tvTimer.visibility = View.GONE
            keypadHost.visibility = View.VISIBLE
            tvDots.visibility = View.VISIBLE
            tvHint.visibility = View.VISIBLE
            // 按当前模式重新决定入口（每日额度那屏的按钮样式不同）
            syncParentEntry(root, ctx, root.tag as? Mode ?: Mode.REST)
            input?.clear()
        }

        input = PinPad.build(keypadHost, tvDots, prefs.pin.length) { entered, pad ->
            pad.clear()
            val left = PinGuard.lockedLeftMs(prefs)
            if (left > 0L) {
                // 冷却期内一律不校验，避免孩子靠"多试几次"蒙对
                onMessage(ctx.getString(R.string.pin_locked_fmt, PinGuard.lockedLeftSeconds(prefs)))
            } else if (entered == prefs.pin) {
                PinGuard.onOk(prefs)
                // 通过：收起键盘，露出两个操作按钮，并给一个操作时限
                keypadHost.visibility = View.GONE
                tvDots.visibility = View.GONE
                tvTimer.visibility = View.VISIBLE
                pinActions.visibility = View.VISIBLE

                var leftSec = ACTION_WINDOW_S
                fun tickText() {
                    tvTimer.text = ctx.getString(R.string.parent_action_window, leftSec)
                }
                tickText()
                val tick = object : Runnable {
                    override fun run() {
                        leftSec -= 1
                        if (leftSec <= 0) {
                            onMessage(ctx.getString(R.string.parent_action_timeout))
                            relock()
                            return
                        }
                        tickText()
                        handler.postDelayed(this, 1000L)
                    }
                }
                handler.postDelayed(tick, 1000L)
            } else {
                val cooled = PinGuard.onWrong(prefs)
                onMessage(
                    if (cooled) {
                        ctx.getString(R.string.pin_locked_fmt, PinGuard.lockedLeftSeconds(prefs))
                    } else {
                        ctx.getString(
                            R.string.pin_wrong_tries,
                            PinGuard.MAX_TRIES - prefs.pinFails
                        )
                    }
                )
            }
        }

        btnParent.setOnClickListener {
            if (prefs.pin.isEmpty()) {
                onMessage(ctx.getString(R.string.pin_unset_cannot_unlock))
                return@setOnClickListener
            }
            val left = PinGuard.lockedLeftMs(prefs)
            if (left > 0L) {
                onMessage(ctx.getString(R.string.pin_locked_fmt, PinGuard.lockedLeftSeconds(prefs)))
                return@setOnClickListener
            }
            tvHint.visibility = View.GONE
            btnParent.visibility = View.GONE
            pinPanel.visibility = View.VISIBLE
            pinActions.visibility = View.GONE
            tvTimer.visibility = View.GONE
            keypadHost.visibility = View.VISIBLE
            tvDots.visibility = View.VISIBLE
            input?.clear()
        }

        btnSkip.setOnClickListener {            handler.removeCallbacksAndMessages(null)
            onSkip()
        }
        btnPause.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            onPauseGuard()
        }
    }

    /**
     * 按模式切换文案。
     *
     * 「休息」和「停用时段」都会锁屏，但对孩子说的话不该一样：前者是"你用了太久"，
     * 后者是"现在不是玩手机的时间"。文案写错会让孩子觉得"我才刚拿起来凭什么说我用久了"。
     */
    fun applyMode(root: View, ctx: Context, mode: Mode, intervalMin: Int = 10) {
        root.findViewById<TextView>(R.id.tvEmoji)?.text = when (mode) {
            Mode.QUIET -> "🌙"
            Mode.DAY -> "🔒"
            Mode.QUIZ -> "📖"
            Mode.QUIZWAIT -> "⏳"
            else -> "😴"
        }
        root.findViewById<TextView>(R.id.tvTitle)?.setText(
            when (mode) {
                Mode.QUIET -> R.string.quiet_title
                Mode.DAY -> R.string.day_lock_title
                Mode.QUIZ -> R.string.quiz_title
                Mode.QUIZWAIT -> R.string.quiz_wait_title
                else -> R.string.rest_title
            }
        )
        root.findViewById<TextView>(R.id.tvRestHint)?.apply {
            text = when (mode) {
                Mode.QUIET -> ctx.getString(R.string.quiet_hint)
                Mode.DAY -> ctx.getString(R.string.day_lock_hint)
                Mode.QUIZ -> ctx.getString(R.string.quiz_hint, intervalMin)
                Mode.QUIZWAIT -> ctx.getString(R.string.quiz_wait_hint)
                else -> ctx.getString(R.string.rest_hint)
            }
        }
        // 「跳过本次休息」在其余模式下意思都不对：
        // 停用时段叫「提前结束本次停用」，每日额度叫「临时放行」，答题屏叫「放行本次答题」
        root.findViewById<Button>(R.id.btnSkipRest)?.text = when (mode) {
            Mode.QUIET -> ctx.getString(R.string.quiet_skip)
            Mode.DAY -> ctx.getString(R.string.day_unlock_temp, DAY_UNLOCK_MIN)
            Mode.QUIZ, Mode.QUIZWAIT -> ctx.getString(R.string.quiz_pass_btn)
            else -> ctx.getString(R.string.skip_rest)
        }
        // 记在 view 上：bind() 里的"超时重新上锁"要用当前模式恢复入口外观
        root.tag = mode
        syncParentEntry(root, ctx, mode)
    }

    /** 刷新倒计时与次数 */
    /**
     * 渲染倒计时。
     *
     * [plan] 是"这一轮为什么休这么久"的一句话（如「使用时长达到上限 · 本次 5 分钟」、
     * 「违规惩罚 ×3，本次 15 分钟」）。没有就自动隐藏那一行。
     * [mode] 决定用哪套文案（休息 / 停用时段）。
     */
    fun render(
        root: View,
        ctx: Context,
        restLeftMs: Long,
        restCount: Int,
        plan: String = "",
        mode: Mode = Mode.REST,
        intervalMin: Int = 10
    ) {
        applyMode(root, ctx, mode, intervalMin)
        val tvCd = root.findViewById<TextView>(R.id.tvCountdown)
        if (tvCd != null) {
            when (mode) {
                // 答题屏没有倒计时：答完就解除，答不完则进 QUIZWAIT 才出现秒表。
                // 显示"00:00"会让孩子以为到点就自动解，一直盯着数字等。
                Mode.QUIZ -> tvCd.visibility = View.GONE
                Mode.DAY -> {
                    tvCd.visibility = View.VISIBLE
                    // 最长能剩十几个小时，字号得降下来，否则一行放不下
                    tvCd.text = fmtDayLeft(restLeftMs)
                    tvCd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                }
                else -> {
                    tvCd.visibility = View.VISIBLE
                    tvCd.text = fmt(restLeftMs)
                    tvCd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
                }
            }
        }

        // 答题面板：只有"正在答题"这一种形态显示
        if (mode == Mode.QUIZ) {
            QuizUi.render(
                root, ctx,
                onAnswer = { ok -> GuardianService.quizAnswerFromMask(ctx, ok) },
                onSwitch = { GuardianService.quizSwitchFromMask(ctx) }
            )
        } else {
            root.findViewById<View>(R.id.quizPanel)?.visibility = View.GONE
        }

        root.findViewById<TextView>(R.id.tvRestCount)?.apply {
            visibility = View.VISIBLE
            text = when (mode) {
                // 停用时段没有"第几次"的概念，显示一句更贴切的
                Mode.QUIET -> ctx.getString(R.string.quiet_count_fmt, fmt(restLeftMs))
                Mode.DAY -> ctx.getString(R.string.day_lock_resume)
                // 答题屏上方已经有"数学 · 第 1 题"，这一行再写一句就是重复
                Mode.QUIZ -> ""
                Mode.QUIZWAIT -> ctx.getString(R.string.quiz_wait_count, fmt(restLeftMs))
                else -> ctx.getString(R.string.rest_count_fmt, restCount)
            }
            if (text.isNullOrBlank()) visibility = View.GONE
        }
        root.findViewById<TextView>(R.id.tvRestPlan)?.apply {
            text = plan
            visibility = if (plan.isBlank()) View.GONE else View.VISIBLE
        }
    }
}
