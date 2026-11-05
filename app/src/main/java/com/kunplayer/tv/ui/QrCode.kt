package com.kunplayer.tv.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码绘制。用 zxing 的 core（约 200KB，纯 Java，无 Android 依赖）。
 *
 * 不手写编码器的原因很实际：QR 要过 Reed-Solomon 纠错、掩码评估、格式信息三套规则，
 * 抄错一位的表现是「手机上扫不出来」，而电视上没有任何反馈能告诉你错在哪。
 * 这个包只做一件事，值得为它多 200KB。
 */
object QrCode {

    /**
     * @param sizePx 输出正方形边长。必须和 ImageView 的像素尺寸接近，
     *               否则会被拉伸插值，模块边缘糊掉反而更难扫。
     */
    fun bitmap(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty()) return null
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        } catch (t: Throwable) {
            return null
        }
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                pixels[base + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
