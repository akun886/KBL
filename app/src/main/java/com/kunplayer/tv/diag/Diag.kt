package com.kunplayer.tv.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunplayer.tv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 本地运行日志：焦点去了哪儿、在哪儿按了确认、按了几下键。
 *
 * 为什么电视端比手机更需要在本地记这个：遥控器只有五个方向键加一个确认，
 * 用户没有任何「滑一下就到」的捷径。一个控件如果要点六下才够得着，手机上只是稍烦，
 * 电视上就是「这功能坏了」。所以唯一值得记的指标是**从焦点落到某个区域到按下确认，
 * 中间隔了几次方向键、多少毫秒**——这两个数一超阈值，布局就该改。
 *
 * 落盘但不联网：这个应用没有服务端。所谓「上报」就是下次 adb 进来能捞到文件，
 * 也正因为不会传出去，崩溃时把最近几十行一起写进崩溃文件才不会觉得越界。
 */
object Diag {

    private const val TAG = "KunPlayer"
    private const val FILE = "diag.log"
    private const val MAX_BYTES = 128 * 1024L
    private const val KEEP_LINES = 200
    private const val BATCH = 32

    /** 给崩溃捕获拿的上下文：崩溃时说不出「用户刚才在干什么」，光有堆栈基本没用 */
    private val recent = ArrayDeque<String>()
    private val buffered = ArrayList<String>()
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-io").apply { isDaemon = true }
    }
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var file: File? = null
    private var zone: String? = null
    private var zoneSince = 0L
    private var zoneMoves = 0

    /** 这个包能不能被调试（run-as / logcat）。决定日志要不要额外打一份到 logcat */
    private var debuggable = false

    fun init(context: Context) {
        file = File(root(context), FILE)
        debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        line("—— 启动 ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MODEL} ——")
        line("日志位置 ${file?.absolutePath}")
    }

    /**
     * 日志目录：应用私有 files/。
     *
     * 试过写外置的 /sdcard/Android/data/<包名>，这台机器上不可行——
     * 小米把这个目录建成了 root:sdcard_rw 771，应用既不是属主也不在组里，
     * 连 files 子目录都建不出来；要写得通就得申请存储权限，为一个日志弹权限框不值。
     *
     * 所以私有目录 + 自用包保留 debuggable：`adb exec-out run-as <包名> cat files/diag.log`
     * 就能取。这也是这个包不打算上架的直接后果，上架版必须关掉 debuggable，
     * 那时的诊断数据就得靠服务端上报——本应用没有服务端，所以此路只对自用包成立。
     */
    fun root(context: Context): File = context.filesDir

    /** 普通日志：同时进 logcat（调试包）和本地文件，开发时不必每次 adb pull */
    fun line(text: String) {
        // 不再单独打一次 Log：write() 在调试包里已经负责这件事，
        // 两边都打会让每条日志在 logcat 里出现两遍
        synchronized(lock) { write(text) }
    }

    /** 焦点进入一个区域。同一区域连续回调不算移动，否则一屏能看到十几条一样的 */
    fun enter(next: String) {
        synchronized(lock) {
            if (next == zone) return
            val prev = zone
            // 只记停留时长与按键数：这是「这个区域好不好用」唯一能量化的口径
            if (prev != null) {
                val stayed = SystemClock.elapsedRealtime() - zoneSince
                if (stayed >= 400) write("离开 $prev · 停留 ${stayed}ms · ${zoneMoves}键")
            }
            zone = next
            zoneSince = SystemClock.elapsedRealtime()
            zoneMoves = 0
        }
    }

    /** 方向键。只在按键真被消费时调用，被吞掉的键不计入导航成本 */
    fun move() {
        synchronized(lock) { zoneMoves++ }
    }

    /** 确认键落到什么上面。`what` 用业务语言写，不要写控件 id */
    fun confirm(what: String) {
        synchronized(lock) {
            val since = if (zone != null) SystemClock.elapsedRealtime() - zoneSince else 0L
            write("确认 $what @${zone ?: "-"} · 自进入 ${since}ms · ${zoneMoves}键")
            // 关键事件立刻落盘。
            //
            // 之前所有日志攒够 32 行才写文件，排查时「日志里没有某条记录」
            // 根本不能推出「那个动作没执行」——这坑踩过一次：
            // 作者主页点投稿明明起了播，日志里却查不到，
            // 白白花时间怀疑点击回调没接上。
            // 确认是低频事件（一次用户交互才一条），单独落盘完全划得来。
            flushLocked()
        }
    }

    /** 崩溃时把最近发生的事整段交出去，调用方不必自己拼时间线 */
    fun tail(count: Int = 40): String = synchronized(lock) { recent.toList().takeLast(count).joinToString("\n") }

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    // 以下两个都要求已经持有 lock

    private fun write(text: String) {
        val stamped = "${clock.format(Date())} $text"
        recent.addLast(stamped)
        while (recent.size > KEEP_LINES) recent.removeFirst()
        buffered += stamped
        // 额外打一份到 logcat：现场排查时用 logcat 跟一遍比 cat 文件快得多。
        //
        // 判据用「包是否可调试」而不是 BuildConfig.DEBUG——后者在 release 包里是 false，
        // 可这个包恰恰是刻意开着 debuggable 的自用包（见 app/build.gradle.kts）。
        // 按 DEBUG 判断的结果是：唯一能在电视上跑的那份包，恰恰一条日志都不进 logcat，
        // 排查只能等攒够 32 行去 cat 文件，而「日志里没有」又不能证明「动作没执行」。
        // 这里改成跟 debuggable 走，让现场那台电视的 logcat 直接能看见。
        if (debuggable) Log.i(TAG, text)
        if (buffered.size >= BATCH) flushLocked()
    }

    private fun flushLocked() {
        if (buffered.isEmpty()) return
        val batch = ArrayList(buffered)
        buffered.clear()
        val target = file ?: return
        io.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    // 只留一代旧文件：电视的 /data 分区本来就小，日志把空间吃满会被系统清数据
                    val old = File(target.parentFile, "$FILE.1")
                    if (old.exists()) old.delete()
                    target.renameTo(old)
                }
                target.appendText(batch.joinToString("\n", postfix = "\n"))
            }
        }
    }
}
