package com.kunplayer.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.kunplayer.tv.net.BiliHttp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 封面加载器。为什么不用 Coil/Glide：这里只需要「下载 → 降采样 → 内存缓存」三件事，
 * 一个 120 行的对象就够，省下的 1MB 依赖在 2GB 设备上比那点代码值钱。
 *
 * 两个细节：
 *   1. 用 View.tag 记住当前请求的 url，列表回收时旧图不会覆盖新卡片；
 *   2. 线程池固定 3 条：A53 只有四核，并发再高只会互相抢 IO 和内存带宽。
 */
object CoverLoader {

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()

    private val memory = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val pool = ThreadPoolExecutor(
        3, 3, 30, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r ->
        Thread(r, "cover").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String, targetWidth: Int, targetHeight: Int) {
        view.tag = url
        if (url.isEmpty()) {
            view.setImageDrawable(null)
            return
        }
        val cached = memory.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = download(url, targetWidth, targetHeight) ?: return@execute
            memory.put(url, bmp)
            main.post {
                // 期间卡片可能已被复用给别的曲目
                if (view.tag == url) view.setImageBitmap(bmp)
            }
        }
    }

    /**
     * 只要位图不要 View 的场景（播放页的模糊背景）。回调一定在主线程，
     * 但调用方仍要自己判断「结果回来时这张图是不是还给同一首歌用」。
     */
    fun fetch(url: String, targetWidth: Int, targetHeight: Int, onReady: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            onReady(null)
            return
        }
        val cached = memory.get(url)
        if (cached != null) {
            onReady(cached)
            return
        }
        pool.execute {
            val bmp = download(url, targetWidth, targetHeight)
            if (bmp != null) memory.put(url, bmp)
            main.post { onReady(bmp) }
        }
    }

    private fun download(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val bytes = BiliHttp.client.newCall(BiliHttp.newRequest(url)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes() ?: return null
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) {
            // 封面失败不弹错，卡片留深色底即可
            null
        }
    }

    fun clear() = memory.evictAll()
}
