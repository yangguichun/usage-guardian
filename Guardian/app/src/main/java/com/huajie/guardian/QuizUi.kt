package com.huajie.guardian

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 答题锁的界面渲染。
 *
 * 和密码键盘 [PinPad] 一样，这里**不用系统输入法**：遮罩是全屏悬浮窗，
 * 弹在里面的输入法会被遮罩挤掉，孩子连一个字母都打不进去。
 * 所以"补全单词"这道题用自绘的 26 键字母盘。
 *
 * ## 为什么每秒只重绘文字、不重建按钮
 *
 * [RestUi.render] 由主循环每秒调一次。如果每次都 `removeAllViews()` 重建选项按钮，
 * 孩子"按住→抬起"很可能落在两个不同的 View 上，点击就丢了 ——
 * 表现是"点了没反应，得点两下"。所以按题目 id 做一次缓存：
 * 题目没变就只更新文字，变了才重建按钮。
 */
object QuizUi {

    private const val C_OPT = 0xFF2B3552.toInt()
    private const val C_OPT_FN = 0xFF3D4A6B.toInt()
    private const val C_OPT_FG = 0xFFFFFFFF.toInt()
    private const val C_KEY = 0xFFE3E8F2.toInt()
    private const val C_KEY_FN = 0xFFC5CDDD.toInt()
    private const val C_KEY_FG = 0xFF1B2233.toInt()

    /** 补全单词时已经敲进去的字母（每换一题清零） */
    private val buf = StringBuilder()

    fun render(root: View, ctx: Context, onAnswer: (Boolean) -> Unit, onSwitch: () -> Unit) {
        val panel = root.findViewById<LinearLayout>(R.id.quizPanel) ?: return
        val q = QuizLock.question
        if (QuizLock.phase != QuizLock.Phase.ASK || q == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE

        root.findViewById<TextView>(R.id.tvQuizTag)?.text =
            ctx.getString(R.string.quiz_tag_fmt, QuizBank.subjectLabel(q.subject), QuizLock.asked)
        root.findViewById<TextView>(R.id.tvQuizStem)?.text = q.stem

        val fb = root.findViewById<TextView>(R.id.tvQuizFeedback)
        fb?.text = QuizLock.feedback
        fb?.visibility = if (QuizLock.feedback.isBlank()) View.GONE else View.VISIBLE

        root.findViewById<Button>(R.id.btnQuizSwitch)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { onSwitch() }
        }

        // 题目没变就只更新上面的文字：重建按钮会吞掉正在按下的那一次点击
        val key = "q${q.id}#${QuizLock.asked}"
        if (panel.tag == key) {
            // 同一题内也要刷新字母槽（每敲一个字母都要变）
            if (q.type == "spell") renderSlots(root, q)
            return
        }
        panel.tag = key
        buf.setLength(0)

        val opts = root.findViewById<LinearLayout>(R.id.quizOpts)
        val spell = root.findViewById<LinearLayout>(R.id.quizSpell)
        opts.removeAllViews()
        val host = root.findViewById<LinearLayout>(R.id.letterHost)
        host?.removeAllViews()

        if (q.type == "spell" && q.template.isNotBlank()) {
            opts.visibility = View.GONE
            spell?.visibility = View.VISIBLE
            renderSlots(root, q)
            buildLetterPad(host, q, onAnswer)
        } else {
            spell?.visibility = View.GONE
            opts.visibility = View.VISIBLE
            for (opt in q.options) {
                opts.addView(
                    makeButton(ctx, opt, C_OPT, C_OPT_FG) {
                        // 选择题一旦点下就判，不让孩子一个个试：
                        // 试错成本为零的话，四选一闭着眼点也能过
                        onAnswer(opt == q.answer)
                    }
                )
            }
        }
    }

    /** 画"K i p p ＿ r"：空缺位显示已敲的字母，没敲就显示下划线 */
    private fun renderSlots(root: View, q: Q) {
        val tv = root.findViewById<TextView>(R.id.tvSpellSlots) ?: return
        val tmpl = q.template
        val sb = StringBuilder()
        var k = 0
        for (ch in tmpl) {
            sb.append(' ')
            if (ch == '_') {
                sb.append(if (k < buf.length) buf[k] else '＿')
                k++
            } else {
                sb.append(ch)
            }
        }
        tv.text = sb.toString().trim()
    }

    private fun buildLetterPad(host: LinearLayout?, q: Q, onAnswer: (Boolean) -> Unit) {
        if (host == null) return
        val ctx = host.context
        val blanks = q.template.count { it == '_' }
        val rows = listOf(
            "ABCDEFGHI",
            "JKLMNOPQR",
            "STUVWXYZ"
        )
        for (row in rows) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (ch in row) {
                line.addView(
                    makeKey(ctx, ch.toString(), 1f) {
                        if (buf.length >= blanks) return@makeKey
                        buf.append(ch)
                        check(root = host, q = q, onAnswer = onAnswer)
                    }
                )
            }
            // 第三行只有 8 个字母，补一个删除键凑满 9 格，视觉上不会缺一角
            if (row.length == 8) {
                line.addView(
                    makeKey(ctx, "⌫", 1f, C_KEY_FN) {
                        if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                        check(root = host, q = q, onAnswer = onAnswer)
                    }
                )
            }
            host.addView(line)
        }
    }

    /**
     * 槽位填满就判一次。
     *
     * 判错不清空缓冲 —— 直接判错换题（这是"不会就换一题"的另一半：
     * 敲错了也算不会）。清空让孩子重敲，他只会照着同一个错误再敲一遍。
     */
    private fun check(root: View, q: Q, onAnswer: (Boolean) -> Unit) {
        renderSlots(root.rootView ?: root, q)
        val blanks = q.template.count { it == '_' }
        if (buf.length < blanks) return
        var k = 0
        val guess = StringBuilder()
        for (ch in q.template) {
            if (ch == '_') {
                guess.append(buf[k])
                k++
            } else {
                guess.append(ch)
            }
        }
        onAnswer(guess.toString().equals(q.answer, ignoreCase = true))
    }

    private fun makeButton(ctx: Context, label: String, bg: Int, fg: Int, onClick: () -> Unit) =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 19f
            setTextColor(fg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 5, 0, 5) }
            setOnClickListener { onClick() }
        }

    private fun makeKey(
        ctx: Context,
        label: String,
        weight: Float,
        bg: Int = C_KEY,
        onClick: () -> Unit
    ): TextView {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(blend(bg), px(9)))
            addState(intArrayOf(), rounded(bg, px(9)))
        }
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(C_KEY_FG)
            textSize = 18f
            background = states
            isClickable = true
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(0, px(42), weight).apply {
                setMargins(px(2), px(2), px(2), px(2))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun blend(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + (255 - (color shr 16 and 0xFF)) / 4)
        val g = ((color shr 8 and 0xFF) + (255 - (color shr 8 and 0xFF)) / 4)
        val b = ((color and 0xFF) + (255 - (color and 0xFF)) / 4)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
