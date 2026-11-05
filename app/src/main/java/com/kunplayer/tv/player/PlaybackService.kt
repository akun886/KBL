package com.kunplayer.tv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.kunplayer.tv.MainActivity
import com.kunplayer.tv.R
import com.kunplayer.tv.model.Track

/**
 * 前台播放服务。
 *
 * 存在的理由只有一个：MIUI TV 杀后台非常积极，按 HOME 回桌面后进程很快就被回收，
 * 播放会直接断掉。转成前台服务后系统会挂一条常驻通知，进程不再被随手清掉——
 * 于是「待机十分钟回来还在播」才成立。
 *
 * 播放器本身不在这里，它在 PlaybackController 单例里；这个 Service 只做两件事：
 * 订阅播放状态、把它投影成一条通知 + 一个 MediaSession。
 * 这样界面销毁重建都不影响播放，Service 被系统重启也不会丢队列。
 */
class PlaybackService : Service(), PlaybackController.Listener {

    companion object {
        private const val TAG = "KunPlayer"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFY_ID = 1001

        const val ACTION_TOGGLE = "com.kunplayer.tv.action.TOGGLE"
        const val ACTION_NEXT = "com.kunplayer.tv.action.NEXT"
        const val ACTION_PREV = "com.kunplayer.tv.action.PREV"

        /**
         * 由 PlaybackController 在第一次起播时调用。
         * 不放在 Activity 里，是因为起播入口有好几个（Hero、卡片、搜索结果），
         * 漏掉一处就会出现「从这条路径播的歌，回桌面就断」。
         */
        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            // API 26 起后台启动服务会抛 IllegalStateException；这里都是用户按出来的前台时机，
            // 真被拒也只是没有常驻通知，不该让播放本身失败
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var playback: PlaybackController
    private var session: MediaSession? = null

    private var foreground = false
    private var lastPlaying: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        playback = PlaybackController.get(this)
        playback.addListener(this)
        session = MediaSession(this, "KunPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!playback.isPlaying()) playback.toggle()
                }

                override fun onPause() {
                    if (playback.isPlaying()) playback.toggle()
                }

                override fun onSkipToNext() = playback.next()

                override fun onSkipToPrevious() = playback.prev()

                override fun onSeekTo(pos: Long) = playback.seekTo(pos)
            })
            isActive = true
        }
        log("服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> playback.toggle()
            ACTION_NEXT -> playback.next()
            ACTION_PREV -> playback.prev()
        }
        // 已经有歌就直接转前台；没有则停在这等第一首——播放页还没开始，不该先挂一条通知
        if (playback.hasTrack()) enterForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playback.removeListener(this)
        session?.isActive = false
        session?.release()
        session = null
        log("服务结束")
        super.onDestroy()
    }

    // --------------------------------------------------------- 播放状态

    override fun onTrack(track: Track, index: Int, total: Int) {
        enterForeground()
        lastPlaying = null
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.owner)
                .build()
        )
        refresh()
    }

    override fun onTick(positionMs: Long, durationMs: Long, playing: Boolean) {
        if (playing == lastPlaying) return
        lastPlaying = playing
        pushState(positionMs, durationMs, playing)
        refresh()
    }

    private fun pushState(positionMs: Long, durationMs: Long, playing: Boolean) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO
                )
                .setState(state, positionMs.coerceAtLeast(0), 1f)
                .build()
        )
    }

    // --------------------------------------------------------- 通知

    private fun enterForeground() {
        if (foreground) return
        val snap = playback.snapshot()
        val track = snap.track ?: return
        ensureChannel()
        startForeground(NOTIFY_ID, build(track, snap.playing))
        foreground = true
        log("转前台服务")
    }

    private fun refresh() {
        if (!foreground) return
        val snap = playback.snapshot()
        val track = snap.track ?: return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, build(track, snap.playing))
    }

    private fun build(track: Track, playing: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }

        return builder
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(track.title)
            .setContentText(track.owner)
            .setContentIntent(openApp())
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(Notification.PRIORITY_LOW)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREV))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "暂停", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "播放", ACTION_TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun action(icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(icon, title, command(action)).build()

    private fun command(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.now_channel),
                // 播放控制本来就在通知里，弹横幅只会挡住正在看的画面
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}
