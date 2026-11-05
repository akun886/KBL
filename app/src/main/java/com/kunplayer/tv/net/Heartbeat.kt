package com.kunplayer.tv.net

import com.kunplayer.tv.model.Track

/**
 * 观看心跳。历史记录不是「播放过就自动进历史」，而是靠这个接口一秒一秒地记账——
 * 不心跳，历史页永远是空的，哪怕歌已经放了一小时。
 *
 * 三个约束：
 *   1. 必须 POST 且带 csrf=bili_jct，否则 -111；
 *   2. `played_time` 是累计播放秒数，必须递增，原地打转服务端不会记账；
 *   3. 15 秒一次就够：更密是浪费，更疏会让「听了 20 秒」这种短播不进历史。
 *
 * 全部 fire-and-forget：心跳失败不能让播放卡一下，也不该在电视上弹提示。
 */
object Heartbeat {

    private const val URL = "https://api.bilibili.com/x/click-interface/web/heartbeat"

    const val INTERVAL_SEC = 15

    private var warned = false

    fun beat(track: Track, cid: Long, playedSec: Int, startTs: Long, log: (String) -> Unit) {
        if (!LoginApi.isLoggedIn()) return
        if (track.aid <= 0 || cid <= 0) return
        val csrf = LoginApi.csrf()
        if (csrf.isEmpty()) return

        val form = linkedMapOf(
            "aid" to track.aid.toString(),
            "cid" to cid.toString(),
            "mid" to LoginApi.mid().toString(),
            "csrf" to csrf,
            "played_time" to playedSec.toString(),
            "realtime" to playedSec.toString(),
            "start_ts" to startTs.toString(),
            "type" to "3",
            "dt" to "2",
            "play_type" to "0",
            "refer_url" to "https://www.bilibili.com/",
            "outer" to "0",
            "spmid" to "333.1387",
        )

        val r = BiliHttp.postForm(URL, form)
        if (!r.isOk || !r.body.contains("\"code\":0")) {
            // 只抱怨一次，避免每 15 秒刷一行同样的日志
            if (!warned) {
                warned = true
                log("  心跳失败（历史不会记录）：HTTP ${r.code} ${r.preview}")
            }
        } else if (playedSec == 0) {
            warned = false
            log("  ♥ 心跳已开始记账 ${track.bvid}")
        }
    }
}
