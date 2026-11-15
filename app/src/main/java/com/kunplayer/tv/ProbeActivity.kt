package com.kunplayer.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kunplayer.tv.model.AudioPick
import com.kunplayer.tv.net.BiliApi
import com.kunplayer.tv.net.BiliException
import com.kunplayer.tv.net.CookieStore
import com.kunplayer.tv.player.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 探针页。唯一目标是回答四个问题，在真机上、用真实网络：
 *   1. 新设备首发请求会不会被 412 卡住（buvid 激活是否有效）；
 *   2. Android 6 的 TLS 能不能和 api.bilibili.com 握手成功；
 *   3. playurl 能不能拿到 dash.audio；
 *   4. 带 Referer 的 ExoPlayer 能不能真的出声。
 *
 * 四个都是「是/否」，不需要 UI 设计。正式的四个页面等这四项全绿再动。
 */
class ProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startButton: Button

    private val buffer = StringBuilder()
    private var running = false
    private var audioPlayer: AudioPlayer? = null

    /** 默认测试视频：雷军《Are you OK》，时长短、码率低，A53 上出声快。
     *  可通过 `am start -n ...ProbeActivity -e bvid BVxxxx` 指定任意稿件，
     *  用于真机用当前登录账号验证取流 / 选轨（比如测大会员无损）。 */
    private var testBvid = "BV1GJ411x7h7"

    /** 是否通过 -e bvid 显式指定了稿件。指定时保留登录态（不清 Cookie），
     *  默认自检模式仍走匿名链路。 */
    private var explicitBvid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra("bvid")?.takeIf { it.isNotBlank() }?.let {
            testBvid = it
            explicitBvid = true
        }
        setContentView(buildUi())
        startButton.requestFocus()

        logLine("KunPlayer 探针 v${BuildConfig.VERSION_NAME}")
        logLine("设备 Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
        logLine("目标稿件 $testBvid（可用 -e bvid 覆盖）")
        logLine("当前登录态由 CookieStore 决定，切账号后重跑即跟随")
        logLine("按「开始自检」，会依次验证：buvid 指纹 → nav → view → playurl → 出声")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        audioPlayer?.release()
        audioPlayer = null
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1215"))
            setPadding(dp(56), dp(40), dp(56), dp(32))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.probe_title)
            setTextColor(Color.parseColor("#F2F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.probe_hint)
            setTextColor(Color.parseColor("#8B979F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(10), 0, 0)
        })

        startButton = Button(this).apply {
            text = getString(R.string.probe_start)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.btn_focus)
            setPadding(dp(36), dp(14), dp(36), dp(14))
            // 聚焦时底色变橙，文字必须跟着变深，否则白字压在橙底上看不清
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                    intArrayOf(Color.parseColor("#0E1215"), Color.parseColor("#F2F5F7"))
                )
            )
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(120).start()
            }
            setOnClickListener { runProbe() }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttonRow.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })
        root.addView(buttonRow)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#C7D0D6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        scroll = ScrollView(this).apply {
            isFocusable = false
            addView(logView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(22) })

        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // --------------------------------------------------------------- 探针链

    private fun runProbe() {
        if (running) return
        running = true
        buffer.setLength(0)
        audioPlayer?.release()
        audioPlayer = null
        // 默认自检走匿名链路（清掉登录态，纯验链路）；显式指定 bvid 时保留登录态，
        // 这样能用当前登录账号验证会员音质差异
        if (!explicitBvid) CookieStore.clear()

        scope.launch {
            withContext(Dispatchers.IO) {
                step("① nav（顺便取 WBI key）") { BiliApi.fetchNav(::logLine) }
                    ?: return@withContext fail("nav 不通，后面全部没意义。先确认这台电视能不能直连 api.bilibili.com。")

                step("② buvid 指纹激活") { BiliApi.activateBuvid(::logLine) }
                    ?: return@withContext fail("buvid 拿不到，说明请求已经被前置拦截（412 或 DNS）。")

                // buvid 写进 Cookie 后重新取一次 nav，验证指纹真的生效
                step("③ 带 buvid 复访 nav（验风控）") { BiliApi.fetchNav(::logLine) }
                    ?: return@withContext fail("带指纹反而失败，重点看 HTTP 码是不是 412。")

                val view = step("④ view（取 aid/cid）") { BiliApi.fetchView(testBvid, ::logLine) }
                    ?: return@withContext fail("视频信息取不到。若 code=-404 说明测试视频已下架，换一个 BV 号。")

                val playUrl = step("⑤ playurl（取 DASH）") {
                    BiliApi.fetchPlayUrl(testBvid, view.cid, ::logLine)
                } ?: return@withContext fail("拿不到 DASH，音频方案不成立。")

                // 探针固定走「自动」档：它验的是链路通不通，不该受用户音质偏好影响
                val pick = step("⑥ 选音轨") { BiliApi.pickAudio(playUrl, 0, ::logLine) }
                    ?: return@withContext fail("DASH 里有声无轨。")

                logLine("")
                logLine("▸ ⑦ 提交播放")
                logLine("  类型 ${pick.kind} · ${pick.detail}")
                logLine("  主 URL ${pick.url.take(72)}…")
                if (pick.backupUrls.isEmpty()) {
                    logLine("  ⚠️ 没有 backup_url（这就是上一版 DashData 漏 @SerialName 的那个坑，本仓库已修）")
                }

                withContext(Dispatchers.Main) { startPlayback(pick) }
            }
            running = false
        }
    }

    private suspend fun startPlayback(pick: AudioPick) {
        val player = AudioPlayer(this@ProbeActivity, { line -> logLine(line) })
        audioPlayer = player
        player.play(pick.url)
        logLine("  提示音立刻听到即为成功；安静 6 秒后给结论")

        delay(6000)
        val pos = player.positionMs
        if (pos > 800) {
            logLine("")
            logLine("✅ 出声成功：6 秒后进度 ${pos}ms / ${player.durationMs}ms")
            logLine("   四项硬门槛全绿，可以把探针页长成正式的播放页了。")
        } else {
            logLine("")
            logLine("⚠️ 6 秒后进度仅 ${pos}ms，看上面的播放器错误码：")
            logLine("   ERROR_CODE_IO_BAD_HTTP_STATUS → CDN 拒绝，检查 Referer")
            logLine("   ERROR_CODE_DECODING_FAILED    → 音轨编码本机不支持，换普通 AAC 音轨")
            logLine("   ERROR_CODE_IO_NETWORK_CONNECTION_FAILED → TLS/网络，可能是 Android 6 信任库")
        }
        logLine("")
        logLine("Cookie 现状：")
        logLine(CookieStore.dump())
    }

    private fun fail(reason: String) {
        logLine("")
        logLine("❌ 中断：$reason")
    }

    private fun <T> step(title: String, block: () -> T): T? {
        logLine("")
        logLine("▸ $title")
        return try {
            block().also { logLine("  ✅ 通过") }
        } catch (e: BiliException) {
            logLine("  ❌ ${e.message}")
            null
        } catch (e: Exception) {
            logLine("  ❌ ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 日志

    /** 可能被 IO 线程调用，所以要切回主线程刷 UI */
    private fun logLine(line: String) {
        // 同步写一份到 logcat：电视上没有可复制的文本框，出问题时只能靠 adb logcat 抓
        android.util.Log.i("KunPlayer", line)
        buffer.append(line).append('\n')
        runOnUiThread {
            logView.text = buffer.toString()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
