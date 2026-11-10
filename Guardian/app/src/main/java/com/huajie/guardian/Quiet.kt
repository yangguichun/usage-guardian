package com.huajie.guardian

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 「停用时段」（宵禁）的判定逻辑。
 *
 * 和「用一段时间就休息」是并列的两类限制：
 *  - 休息管**用多久**（累计时长到上限就锁）；
 *  - 停用管**什么时候不能用**（每天固定时段一到就锁，时段结束自动放开）。
 *
 * ## 几个必须记住的语义
 *
 * 1. **时间只到分钟**，用「设备本地时区当天 0 点起算的分钟数」表示。
 *    家长要的是"晚上 10 点开始不许玩"，不是 21:58:37。
 *
 * 2. **`from > to` 表示跨夜**（22:00 → 06:00）。这是最常见的写法，
 *    不能当成非法数据丢掉。跨夜规则里 `to` 属于**次日**。
 *
 * 3. **星期以"时段开始那天"为准**。勾了周五的「22:00–06:00」，
 *    周六凌晨 0–6 点也在停用范围内 —— 否则家长勾了周五、孩子周六早上还能玩。
 *    实现上就是：判断"此刻"是否命中时，先看此刻属于哪个绝对时间，
 *    再分别看"今天开始的规则"和"昨天开始但跨夜到今天"的规则。
 *
 * 4. **`days == 0` 视为每天**。前端"一天都没勾"就是全选，服务端也按这个口径。
 *
 * 5. **规则来自两处，取并集**：服务端下发的（权威、心跳每轮带全量）+ 家长在
 *    本机 App 里自己加的（见 [Prefs.localQuietRulesRaw]，入口有 PIN 校验）。
 *    本地不能删除下发的规则 —— 否则孩子改一下手机就绕过了。
 *    设备端也**不把**本地规则上报回去覆盖服务端的，两边各存各的。
 */
object Quiet {

    private const val TAG = "Quiet"

    /** 分钟数 → `HH:MM` */
    fun hhmm(min: Int): String {
        val m = min.coerceIn(0, 24 * 60)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 星期位掩码 → 中文（和服务端 `days_label` 同口径） */
    fun daysText(days: Int): String {
        val v = days and DAYS_ALL
        if (v == 0 || v == DAYS_ALL) return "每天"
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val picked = (0..6).map { v and (1 shl it) != 0 }
        val out = ArrayList<String>()
        var i = 0
        while (i < 7) {
            if (!picked[i]) { i++; continue }
            var j = i
            while (j + 1 < 7 && picked[j + 1]) j++
            out.add(
                if (j - i >= 2) "周${names[i]}至周${names[j]}"
                else (i..j).joinToString("、") { "周${names[it]}" }
            )
            i = j + 1
        }
        return out.joinToString("、")
    }

    /** 一条规则的完整描述，用于遮罩/通知/诊断区 */
    fun describe(r: Rule): String =
        "${daysText(r.days)} ${hhmm(r.from)}–${hhmm(r.to)}${if (r.crossesMidnight) "（次日）" else ""}" +
            if (r.label.isNotBlank()) "「${r.label}」" else ""

    // ==================================================================
    // 命中判定
    // ==================================================================

    /** 某条规则在"给定日期 + 分钟"这一刻是否处于停用区间内（不看星期） */
    private fun covers(r: Rule, minuteOfDay: Int): Boolean =
        if (r.crossesMidnight) {
            // 跨夜：22:00–06:00 = [from, 24:00) ∪ [00:00, to)
            minuteOfDay >= r.from || minuteOfDay < r.to
        } else {
            minuteOfDay >= r.from && minuteOfDay < r.to
        }

    /** 这一天（0=周一）在掩码里是否生效。`days == 0` 表示每天 */
    private fun dayAllowed(days: Int, weekday: Int): Boolean {
        val v = days and DAYS_ALL
        return v == 0 || (v and (1 shl weekday)) != 0
    }

    /**
     * 判断 `now` 这一刻是否落在任何一条启用中的规则里。
     *
     * @return 命中的规则与"距离结束还有多少毫秒"；没命中返回 null
     */
    fun current(ctx: android.content.Context, prefs: Prefs, now: Long): Hit? {
        val rules = prefs.allQuietRules()
        if (!prefs.enabled || rules.isEmpty()) return null
        // 家长在遮罩上放了这一轮：到点前一律视为"不在停用区间"。
        // 只对这一**段**有效（skip 值就是这一段的结束时刻），下一段照旧锁。
        if (now < prefs.quietSkipUntilWall) return null

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Calendar 的 DAY_OF_WEEK：1=周日 … 7=周六 → 我们的 0=周一 … 6=周日
        val weekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

        var best: Hit? = null
        for (r in rules) {
            if (!r.enabled) continue
            // ① 今天开始的规则
            var hit = if (dayAllowed(r.days, weekday) && covers(r, minute)) {
                val endMin = if (r.crossesMidnight && minute >= r.from) r.to + 24 * 60 else r.to
                r to (endMin - minute)
            } else null // 内层 null 表示这一支没命中
            // ② 昨天开始、跨夜到今天凌晨的那一段
            if (hit == null && r.crossesMidnight && minute < r.to) {
                val yest = (weekday + 6) % 7
                if (dayAllowed(r.days, yest)) hit = r to (r.to - minute)
            }
            val h = hit ?: continue
            // 多条规则重叠时取"最晚结束"的那条：这样遮罩上的倒计时不会提前放开
            if (best == null || h.second > best!!.leftMinutes) {
                best = Hit(h.first, h.second)
            }
        }
        return best
    }

    /** 命中结果：哪条规则 + 距结束还有几分钟 */
    class Hit(val rule: Rule, val leftMinutes: Int) {
        val leftMs: Long get() = leftMinutes.coerceAtLeast(0).toLong() * 60_000L
        fun label(ctx: android.content.Context): String =
            if (rule.label.isNotBlank()) rule.label else describe(rule)
    }

    /**
     * 下一段停用**开始**的时刻（毫秒）；未来 8 天内没有就返回 0。
     *
     * 用来排闹钟：光靠主线程每秒 tick，"到点自动锁屏"在进程被冻结时会失效。
     */
    fun nextStartAt(prefs: Prefs, now: Long): Long {
        val rules = prefs.allQuietRules().filter { it.enabled }
        if (!prefs.enabled || rules.isEmpty()) return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        // 从今天 0 点开始，逐天往前找
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        for (d in 0..8) {
            val dayStart = cal.timeInMillis
            val weekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            for (r in rules) {
                if (!dayAllowed(r.days, weekday)) continue
                val at = dayStart + r.from * 60_000L
                if (at > now) return at
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return 0L
    }

    // ==================================================================
    // 规则持久化
    // ==================================================================

    /** 
     * 一条规则。
     *
     * @param from 0 点起算的分钟数
     * @param to   0 点起算的分钟数；`from > to` 表示跨到次日
     * @param days 星期位掩码，第 0 位=周一；0 表示每天
     */
    class Rule(
        val id: String,
        val enabled: Boolean,
        val label: String,
        val from: Int,
        val to: Int,
        val days: Int
    ) {
        val crossesMidnight: Boolean get() = from > to
    }

    /**
     * 解析服务端下发的规则数组。
     *
     * 脏数据一律丢弃而不是"猜一个合理值"：猜错的结果是孩子在错误的时间被锁，
     * 家长还以为是程序坏了。丢弃时打一行日志，诊断区能看到。
     */
    fun parse(arr: JSONArray?): List<Rule> {
        if (arr == null) return emptyList()
        val out = ArrayList<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val from = o.optInt("from", -1)
            val to = o.optInt("to", -1)
            if (from < 0 || to < 0 || from > 24 * 60 || to > 24 * 60) {
                Log.w(TAG, "丢弃时间越界的规则：from=$from to=$to")
                continue
            }
            if (from == to) {
                Log.w(TAG, "丢弃零长度规则：$from")
                continue
            }
            out.add(
                Rule(
                    id = o.optString("id").ifEmpty { "qr_${from}_$to" },
                    enabled = o.optBoolean("enabled", true),
                    label = o.optString("label").take(20),
                    from = from,
                    to = to,
                    days = o.optInt("days", 0) and DAYS_ALL
                )
            )
        }
        return out
    }

    fun toJson(rules: List<Rule>): String {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("enabled", r.enabled)
                    .put("label", r.label)
                    .put("from", r.from)
                    .put("to", r.to)
                    .put("days", r.days)
            )
        }
        return arr.toString()
    }

    fun fromJson(raw: String): List<Rule> =
        runCatching { parse(if (raw.isBlank()) null else JSONArray(raw)) }.getOrDefault(emptyList())

    /** 所有 7 天 */
    const val DAYS_ALL = 0b111_1111
}
