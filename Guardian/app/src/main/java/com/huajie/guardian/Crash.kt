package com.huajie.guardian

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自记录。
 *
 * 为什么需要它：这个应用装在孩子的平板上，出问题时家长手上只有一台设备，
 * 不可能连电脑跑 `adb logcat`。没有堆栈就只能靠"我感觉是 XX 逻辑错了"来猜，
 * 猜错的代价是家长反复装、反复试。
 *
 * 所以我们自己接住未捕获异常，把**时间、机型、版本、完整堆栈**写进
 * `files/last_crash.txt`，下次打开应用时在「诊断」区显示一行摘要，
 * 点「查看上次崩溃日志」就能看全文并一键复制。
 *
 * 注意：这里只记日志，不做"兜底吞异常继续跑"。进程该被系统结束就结束 ——
 * 半死不活的守护进程比干净退出危险得多。
 */
object Crash {

    private const val FILE_NAME = "last_crash.txt"

    /** 堆栈可能很长，截断一下，避免写盘太久（写盘本身也可能失败） */
    private const val MAX_STACK_CHARS = 60_000

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 装上钩子。只装一次（Application.onCreate 里调） */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            // 交回给系统默认处理：弹"应用已停止"并结束进程。
            // 吞掉不交，等于让一个状态已经乱掉的进程继续跑。
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().take(MAX_STACK_CHARS)

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("时间：").append(time).append('\n')
            append("版本：").append(appVersion(ctx)).append('\n')
            append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程：").append(thread.name).append('\n')
            append("异常：").append(error.javaClass.name)
            append(": ").append(error.message ?: "").append('\n')
            append('\n')
            append(stack)
        }
        file(ctx).writeText(text)
    }

    /** 完整日志；没有返回空串 */
    fun read(ctx: Context): String = runCatching {
        val f = file(ctx)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    /** 诊断区那一行：`10-22 17:01 · UninitializedPropertyAccessException: ...` */
    fun summary(ctx: Context): String {
        val text = read(ctx)
        if (text.isBlank()) return ""
        val lines = text.lineSequence().take(6).toList()
        var time = ""
        var what = ""
        for (l in lines) {
            when {
                l.startsWith("时间：") -> time = l.removePrefix("时间：").take(16)
                l.startsWith("异常：") -> what = l.removePrefix("异常：")
            }
        }
        if (what.isEmpty()) return ""
        return if (time.isEmpty()) what else "$time · $what"
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else 0L
        "${pi.versionName} ($code)"
    }.getOrDefault("?")
}
