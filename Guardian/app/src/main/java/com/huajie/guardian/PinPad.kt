package com.huajie.guardian

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 应用**自带**的数字密码键盘。
 *
 * 为什么不再用系统输入法（v1.7 修的坑）：
 *
 * 强制休息遮罩是以系统悬浮窗（TYPE_APPLICATION_OVERLAY）铺满整个屏幕的。
 * 在这种"盖住全屏"的窗口里点密码框，系统输入法的键盘窗口会被遮罩层挤掉，
 * 表现就是**键盘闪一下就消失了**，家长连一个字都输不进去。
 * 设置界面那个密码框虽然在普通 Activity 里，但在做过管控的平板上，
 * 输入法同样可能弹不出来——一旦弹不出来，家长就彻底进不去应用了。
 *
 * 所以这里干脆自己画一个数字键盘：不依赖任何输入法，任何 ROM 上都能用。
 *
 * 附带的好处：第三方输入法拿不到家长密码（不会被输入法记录、联想或上传）。
 *
 * 实现上刻意只用 TextView + 自绘背景，不碰主题里的按钮样式——
 * 因为这块界面既要被 Activity 加载，也要被 Service 的上下文加载，
 * 后者没有 Material 主题，用主题样式会崩。
 */
object PinPad {

    /** 密码长度范围 */
    const val MIN_LEN = 4
    const val MAX_LEN = 8

    private const val C_BG = 0xFFE3E8F2.toInt()
    private const val C_BG_FN = 0xFFC5CDDD.toInt()
    private const val C_BG_OK = 0xFF2E9E7E.toInt()
    private const val C_FG = 0xFF1B2233.toInt()
    private const val C_FG_OK = 0xFFFFFFFF.toInt()

    /**
     * 一次输入过程的缓冲。
     * 校验场景下 [onComplete] 会在位数达到密码长度时自动触发；
     * 家长可以随时 [clear] 重来。
     */
    class Input internal constructor(
        private val display: TextView,
        private val maxLen: Int?,
        private val onComplete: (String, Input) -> Unit
    ) {
        private val buf = StringBuilder()

        val value: String get() = buf.toString()

        fun push(d: Char) {
            // 上限取"目标位数"，这样即使密码是历史遗留的超长密码也能输满位数自动校验；
            // 没指定目标位数（设置密码）时最多 8 位。
            val cap = maxLen ?: MAX_LEN
            if (buf.length >= cap) return
            buf.append(d)
            render()
            val n = maxLen
            if (n != null && n > 0 && buf.length == n) onComplete(buf.toString(), this)
        }

        fun backspace() {
            if (buf.isEmpty()) return
            buf.deleteCharAt(buf.length - 1)
            render()
        }

        fun clear() {
            buf.setLength(0)
            render()
        }

        private fun render() {
            display.text = if (buf.isEmpty()) "" else "●".repeat(buf.length)
        }
    }

    /**
     * 把键盘挂到 [host] 上，已输入的位数用圆点显示在 [display]。
     *
     * @param maxLen 输入到这么多位就自动回调（用于**校验**密码，位数已知）；
     *               传 null 则多出一个「确定」键（用于**设置**密码，位数不确定）。
     */
    fun build(
        host: LinearLayout,
        display: TextView,
        maxLen: Int?,
        onComplete: (String, Input) -> Unit
    ): Input {
        lateinit var input: Input
        input = Input(display, maxLen, onComplete)
        host.removeAllViews()

        val ctx = host.context
        val digitRows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("清空", "0", "删除")
        )
        for (row in digitRows) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (label in row) {
                val fn = label.length > 1
                line.addView(
                    makeKey(ctx, label, if (fn) C_BG_FN else C_BG, C_FG, 1f) {
                        when (label) {
                            "清空" -> input.clear()
                            "删除" -> input.backspace()
                            else -> input.push(label[0])
                        }
                    }
                )
            }
            host.addView(line)
        }

        if (maxLen == null) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            line.addView(makeKey(ctx, "确定", C_BG_OK, C_FG_OK, 3f) {
                onComplete(input.value, input)
            })
            host.addView(line)
        }

        input.clear()
        return input
    }

    private fun makeKey(
        ctx: Context,
        label: String,
        bg: Int,
        fg: Int,
        weight: Float,
        onPress: () -> Unit
    ): TextView {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(blend(bg), px(10)))
            addState(intArrayOf(), rounded(bg, px(10)))
        }

        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(fg)
            textSize = if (label.length > 1) 16f else 21f
            background = states
            isClickable = true
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, px(46), weight).apply {
                setMargins(px(3), px(3), px(3), px(3))
            }
            setOnClickListener { onPress() }
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    /** 按键按下时稍微提亮，给一点反馈（不依赖主题的 ripple） */
    private fun blend(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + (255 - (color shr 16 and 0xFF)) / 4)
        val g = ((color shr 8 and 0xFF) + (255 - (color shr 8 and 0xFF)) / 4)
        val b = ((color and 0xFF) + (255 - (color and 0xFF)) / 4)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
