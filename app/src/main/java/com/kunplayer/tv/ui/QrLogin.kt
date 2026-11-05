package com.kunplayer.tv.ui

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.kunplayer.tv.R
import com.kunplayer.tv.net.BiliApi
import com.kunplayer.tv.net.LoginApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫码登录浮层：生成二维码 → 轮询 → 落地 Cookie。
 *
 * 轮询节奏 1.5 秒：B 站二维码有效期 180 秒，1.5 秒一次是 120 次请求；
 * 再密没有意义（手机上点确认到服务端记账本身就有延迟），再疏会让用户
 * 确认完还盯着「已扫码」两秒以上，感觉像没生效。
 *
 * 过期自动换一张：二维码放电视上，用户去拿手机、走到客厅的时间足够它过期一次，
 * 这时候让用户按返回再进来是没必要的摩擦。
 */
class QrLogin(
    private val activity: Activity,
    private val overlay: View,
    private val qrView: ImageView,
    private val statusView: TextView,
    private val onLoggedIn: (String) -> Unit,
    private val log: (String) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var isShowing = false
        private set

    fun show() {
        if (isShowing) return
        isShowing = true
        overlay.visibility = View.VISIBLE
        log("显示扫码浮层")
        overlay.requestFocus()
        setStatus(activity.getString(R.string.login_wait))
        job = scope.launch { loop() }
    }

    fun dismiss() {
        isShowing = false
        job?.cancel()
        job = null
        overlay.visibility = View.GONE
    }

    fun release() {
        dismiss()
        scope.cancel()
    }

    private suspend fun loop() {
        var qr = generate() ?: return
        var drawn = false

        while (isShowing && scope.isActive) {
            if (!drawn) {
                val bitmap = withContext(Dispatchers.Default) { QrCode.bitmap(qr.url, 520) }
                if (bitmap == null) {
                    setStatus(activity.getString(R.string.login_failed, "二维码绘制失败"))
                    return
                }
                qrView.setImageBitmap(bitmap)
                log("二维码已生成 ${qr.url.take(48)}…")
                setStatus(activity.getString(if (drawn) R.string.login_expired else R.string.login_scan))
                drawn = true
            }

            delay(1500)

            val outcome = try {
                withContext(Dispatchers.IO) { LoginApi.qrPoll(qr.qrcodeKey) }
            } catch (t: Throwable) {
                log("扫码轮询失败 ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            val poll = outcome.poll

            when (poll.code) {
                LoginApi.OK -> {
                    if (finishLogin(poll.url, outcome.setCookies)) return
                    setStatus(activity.getString(R.string.login_failed, "Cookie 未落地"))
                    return
                }

                LoginApi.WAIT_CONFIRM -> setStatus(activity.getString(R.string.login_scanned))

                LoginApi.EXPIRED -> {
                    log("二维码过期，换一张")
                    setStatus(activity.getString(R.string.login_expired))
                    qr = generate() ?: return
                    drawn = false
                }

                else -> setStatus(activity.getString(R.string.login_scan))
            }
        }
    }

    private suspend fun generate(): com.kunplayer.tv.model.QrGen? =
        try {
            withContext(Dispatchers.IO) { LoginApi.qrGenerate() }
        } catch (t: Throwable) {
            log("二维码生成失败 ${t.javaClass.simpleName}: ${t.message}")
            setStatus(activity.getString(R.string.login_failed, t.message ?: "网络错误"))
            null
        }

    private suspend fun finishLogin(crossDomainUrl: String, setCookies: List<String>): Boolean {
        val ok = LoginApi.applyCookies(crossDomainUrl, setCookies, log)
        if (!ok) return false
        // 登录态要靠 nav 复核一遍：Cookie 写进去了不代表服务端认（过期/风控都会 200 但 isLogin=false）
        val name = withContext(Dispatchers.IO) {
            runCatching { BiliApi.fetchNav(log).uname }.getOrNull()
        }
        log("扫码登录成功：${name ?: "（未取到昵称）"}")
        dismiss()
        onLoggedIn(name.orEmpty())
        return true
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }
}
