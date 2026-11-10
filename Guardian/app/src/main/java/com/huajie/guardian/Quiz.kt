package com.huajie.guardian

import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * 一道题。
 *
 * 字段名沿用服务端下发时的短键（`s`/`t`/`q`/`o`/`a`/`w`/`h`），
 * 因为心跳和取题接口里一条题要重复几十次，短键能省下可观的流量；
 * 到了客户端再映射成可读的名字，之后的代码就不用记这些缩写。
 *
 * - [type] `choice`：四选一（语文拼音、英语词义、数学算式都用它）
 * - [type] `spell` ：补全单词，[template] 里用 `_` 表示空缺，[answer] 是完整答案
 */
class Q(
    val id: Long,
    val subject: String,
    val type: String,
    val stem: String,
    val options: List<String>,
    val answer: String,
    val template: String,
    val hint: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("s", subject)
        .put("t", type)
        .put("q", stem)
        .put("o", JSONArray(options))
        .put("a", answer)
        .put("w", template)
        .put("h", hint)

    companion object {
        fun fromJson(o: JSONObject): Q? {
            val stem = o.optString("q").trim()
            if (stem.isEmpty()) return null
            val arr = o.optJSONArray("o")
            val opts = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) opts.add(arr.optString(i))
            }
            val type = o.optString("t").ifEmpty { "choice" }
            val answer = o.optString("a")
            if (answer.isEmpty()) return null
            // 选择题的答案必须在选项里，否则界面上根本没有可点的正确答案 ——
            // 宁可丢掉这一题，也不要让孩子面对一道永远答不对的题
            if (type == "choice" && !opts.contains(answer)) return null
            return Q(
                id = o.optLong("id", 0L),
                subject = o.optString("s").ifEmpty { "math" },
                type = type,
                stem = stem,
                options = opts,
                answer = answer,
                template = o.optString("w"),
                hint = o.optString("h")
            )
        }
    }
}

/**
 * 题库的本地缓存与选题。
 *
 * ## 为什么不把题写死在 App 里
 *
 * 题目要"慢慢维护"，写死在客户端就意味着一改题就要发版。所以题目一律从
 * 服务端下载后缓存在 [Prefs] 里；服务端不可达时用缓存里的旧题，
 * 连缓存都没有（从没配过服务端）才退回本地生成的算术题 ——
 * 保证"开了这个功能就一定出得来题"，而不是弹一个空白遮罩。
 *
 * ## 缓存更新的判据是版本号
 *
 * 服务端每次心跳带 `quiz_version`，它是题库内容的哈希（增删题、改题、开关题库都会变）。
 * 版本没变就一个字节都不下，变了才整批替换 —— 家长刚加的题，下一次心跳就能用上。
 */
object QuizBank {

    /** 服务端一次最多给多少题（设备侧只用来填满候选题池，不需要全部） */
    const val FETCH_LIMIT = 200

    fun subjectLabel(s: String): String = when (s) {
        "cn" -> "语文"
        "math" -> "数学"
        "en" -> "英语"
        else -> "综合"
    }

    /** 读缓存 */
    fun cached(p: Prefs): List<Q> {
        val raw = p.quizCacheJson
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Q>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Q.fromJson(o)?.let { out.add(it) }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * 存缓存。
     *
     * 题目本身很小，但一次 200 道也有几十 KB；存进 SharedPreferences 的
     * 单条字符串上限没硬限制，但为了不撑爆 `apply()` 的同步写，这里限到
     * [FETCH_LIMIT] 条以内（服务端不会给更多）。
     */
    fun saveCache(p: Prefs, version: String, list: List<Q>) {
        val arr = JSONArray()
        for (q in list.take(FETCH_LIMIT)) arr.put(q.toJson())
        p.quizCacheJson = arr.toString()
        p.quizCacheVersion = version
    }

    /**
     * 挑一道题：随机，尽量不连着出同一道。
     *
     * 不用"顺序轮播"：孩子一天会看到十几次答题屏，顺序出题两天就能背下答案；
     * 也不用"随机 + 记录已出题"：那需要持久化一张越来越长的表，
     * 而题库本身就在变化，记了也会失效。
     */
    fun pick(list: List<Q>, lastId: Long): Q? {
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val r = Random()
        var q = list[r.nextInt(list.size)]
        var guard = 0
        while (q.id == lastId && guard++ < 8) {
            q = list[r.nextInt(list.size)]
        }
        return q
    }

    /**
     * 本地兜底题：20 以内加减法。
     *
     * 只在"从没从服务端拿到过题"时用到。故意做成现算而不是写死一张表 ——
     * 现算的题过多久都不会重样，也不用往 APK 里塞题库。
     */
    fun localFallback(): List<Q> {
        val r = Random()
        val out = ArrayList<Q>(40)
        var id = -1L
        repeat(40) {
            val a = r.nextInt(11) + 1
            val b = r.nextInt(11) + 1
            val plus = r.nextBoolean()
            if (plus) {
                out.add(arith(id--, "$a + $b = ?", a + b))
            } else {
                val hi = maxOf(a, b)
                val lo = minOf(a, b)
                out.add(arith(id--, "$hi − $lo = ?", hi - lo))
            }
        }
        return out
    }

    private fun arith(id: Long, stem: String, answer: Int): Q {
        // 干扰项要和正确答案一样"看起来合理"：±1、±2，越界就换一边。
        // 用 99 这样的数字当干扰项，孩子一眼就能排除，等于没干扰。
        val set = LinkedHashSet<Int>()
        set.add(answer)
        var d = 1
        while (set.size < 4) {
            if (answer + d in 0..20) set.add(answer + d)
            if (set.size < 4 && answer - d in 0..20) set.add(answer - d)
            d++
            if (d > 20) break
        }
        while (set.size < 4) set.add(set.size + 21)
        val opts = set.toMutableList()
        java.util.Collections.shuffle(opts)
        return Q(
            id = id,
            subject = "math",
            type = "choice",
            stem = stem,
            options = opts.map { it.toString() },
            answer = answer.toString(),
            template = "",
            hint = ""
        )
    }
}

/**
 * 当前这一次「答题锁」的状态。
 *
 * 放在静态对象里而不是服务实例里：遮罩有两套实现（Service 的悬浮窗、
 * 兜底的 LockActivity），它们必须看到同一份状态，否则兜底界面弹出来时
 * 会显示"休息中"而不是那道题。
 *
 * ★ 这份状态**不持久化**，进程被杀就没了。这是有意的：
 * 孩子杀掉进程等于放弃了这次答题，重启后守护会按"还欠着一道题"重新出题
 * （判据是 [Prefs.quizBaseMs]，它只在你答对之后才前移）。
 */
object QuizLock {

    enum class Phase {
        /** 正在答题 */
        ASK,

        /** 两题都没答对，休息一段时间（倒计时结束自动放开） */
        WAIT
    }

    @Volatile
    var phase: Phase? = null
        private set

    @Volatile
    var question: Q? = null
        private set

    /** 本轮已经出过几道题（第 2 道之后就不再换，直接罚时） */
    @Volatile
    var asked: Int = 0
        private set

    /** WAIT 阶段的结束时刻（墙钟） */
    @Volatile
    var waitUntilWall: Long = 0L
        private set

    /** 上一题答错时的提示（"正确答案是 cat"），显示在题目下方 */
    @Volatile
    var feedback: String = ""
        private set

    val active: Boolean get() = phase != null

    fun ask(q: Q, feedbackText: String = "") {
        phase = Phase.ASK
        question = q
        asked += 1
        feedback = feedbackText
        waitUntilWall = 0L
    }

    /**
     * 进入罚时。
     *
     * 不能叫 `wait()`：`QuizLock` 是 object，`fun wait(Long)` 会和
     * `Object.wait(long)` 撞 JVM 签名，Kotlin 报 "Accidental override"。
     */
    fun startPenalty(untilWall: Long) {
        phase = Phase.WAIT
        question = null
        waitUntilWall = untilWall
    }

    fun reset() {
        phase = null
        question = null
        asked = 0
        waitUntilWall = 0L
        feedback = ""
    }
}
