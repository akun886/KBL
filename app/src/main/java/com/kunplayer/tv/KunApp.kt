package com.kunplayer.tv

import android.app.Application
import com.kunplayer.tv.audio.AudioBus
import com.kunplayer.tv.cache.AccountStore
import com.kunplayer.tv.cache.Resume
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.diag.Crash
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.net.BiliHttp
import com.kunplayer.tv.net.CollectionApi
import com.kunplayer.tv.net.CookieStore
import com.kunplayer.tv.net.LyricsApi

class KunApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 崩溃捕获必须是第一件事：装在 CookieStore/BiliHttp 之后，那两步里出的错就抓不到
        Crash.install(this)
        Diag.init(this)
        // 两者都必须早于任何网络调用：Cookie 决定 buvid 是否复用，缓存目录决定封面是否命中
        CookieStore.init(this)
        // 登录过的账号档案：切账号时整套 Cookie 从这儿换
        AccountStore.init(this)
        BiliHttp.init(cacheDir)
        // 歌词缓存要自己的目录：它是解析后的文本，和 OkHttp 的响应缓存不是一回事
        LyricsApi.init(filesDir)
        // 合集（多 P 稿件）的分 P 表同样按 bvid 落盘，冷启动后重听不必再问一次
        CollectionApi.init(filesDir)
        // 断点续播的进度表
        Resume.init(filesDir)
        // 响度均衡在播放器的音频管线里做，开关从这里灌进去
        AudioBus.normalize = Settings.normalize(this)
        val pending = Crash.pending(this)
        if (pending > 0) Diag.line("待查崩溃日志 ${pending} 份")
    }
}
