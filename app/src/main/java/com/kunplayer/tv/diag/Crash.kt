package com.kunplayer.tv.diag

import android.content.Context
import android.os.Build
import com.kunplayer.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落本地文件。
 *
 * 电视上没有崩溃平台可看，logcat 一旦被系统回收就什么都没了，用户只会说「它闪退」。
 * 所以这里把异常连同**崩溃前最后几十条焦点/确认记录**一起写成一个文件：
 * 只有堆栈的话，一个空指针到底是他按了搜索、还是切了页签，根本无从判断。
 *
 * 只保留最近 5 份：重复崩溃会迅速把 /data 写满，而 /data 满了系统会直接清应用数据，
 * 那等于把登录态一起丢掉——比崩溃本身更难解释。
 */
object Crash {

    private const val DIR = "crashes"
    private const val KEEP = 5

    /** 必须最先装上：装在 CookieStore/BiliHttp 之后，那两步里出的错就抓不到 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // 崩溃线程上写文件，出错也不能让它把原本的异常盖掉：runCatching 兜住再交回去
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): Int = folder(context).listFiles { f -> f.isFile }?.size ?: 0

    /** 账户页要显示的一行摘要：只取最新那份的第一行异常，不把整个堆栈摊给用户看 */
    fun lastSummary(context: Context): String {
        val latest = folder(context).listFiles { f -> f.isFile }
            ?.maxByOrNull { it.lastModified() } ?: return ""
        return runCatching {
            latest.readLines().firstOrNull { it.startsWith("异常 ") }?.removePrefix("异常 ") ?: ""
        }.getOrDefault("")
    }

    fun clear(context: Context) {
        folder(context).listFiles()?.forEach { runCatching { it.delete() } }
        Diag.line("崩溃日志已清除")
    }

    private fun folder(context: Context): File = File(Diag.root(context), DIR).apply { mkdirs() }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("==== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ====")
            appendLine("应用 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("设备 ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("线程 ${thread.name} (${thread.id})")
            appendLine("异常 ${error.javaClass.name}: ${error.message}")
            appendLine()
            appendLine(stackOf(error))
            appendLine("—— 崩溃前最后动作 ——")
            appendLine(runCatching { Diag.tail(40) }.getOrDefault(""))
        }
        val file = File(folder(context), "crash-$stamp.log")
        file.writeText(text)
        Diag.flush()

        // 超出部分按修改时间从旧到新删，只留最近 KEEP 份
        val all = folder(context).listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return
        all.dropLast(KEEP).forEach { runCatching { it.delete() } }
    }

    private fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }
}
