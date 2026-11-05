package com.kunplayer.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.audio.AudioBus
import com.kunplayer.tv.cache.Resume
import com.kunplayer.tv.cache.SearchHistory
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.diag.Crash
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.model.Track
import com.kunplayer.tv.net.BiliApi
import com.kunplayer.tv.net.LoginApi
import com.kunplayer.tv.net.SearchApi
import com.kunplayer.tv.player.PlaybackController
import com.kunplayer.tv.player.QueueMode
import com.kunplayer.tv.ui.ColorPalette
import com.kunplayer.tv.ui.CoverLoader
import com.kunplayer.tv.ui.FavFolderPanel
import com.kunplayer.tv.ui.FollowingPanel
import com.kunplayer.tv.ui.HomeStage
import com.kunplayer.tv.ui.Keypad
import com.kunplayer.tv.ui.NowPlaying
import com.kunplayer.tv.ui.QrLogin
import com.kunplayer.tv.ui.ScrollableLayoutManager
import com.kunplayer.tv.ui.SpacePanel
import com.kunplayer.tv.ui.TrackListAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用唯一的 Activity。两层：首页与播放页，都可以是「当前这一页」。
 *
 * 首页是一张歌单：左边选去处，右边挑歌。播放页只有一首歌：左右切歌、确认暂停，
 * 没有可移动的焦点。两页的按键哲学因此相反，这是刻意的——
 * 选歌是在几十行里移动，听歌是在一首歌里做动作。
 *
 * 其余都是浮层：搜索、我的、扫码登录。谁在最上层，按键就归谁。
 */
class MainActivity : Activity() {

    private companion object {
        /** 底部临时提示停留时长 */
        const val HINT_MS = 2_600L

        /** 连打字母时不必每个键都打一次接口：停手 400ms 再发 */
        const val SEARCH_DEBOUNCE_MS = 400L

        /** 左栏铺几条历史。文件里留十条，全铺出来靠滚动翻，见 paintSearchHistory */
        const val HIST_SHOWN = 10

        /** 左栏同时看得见几行。540dp 屏减去输入回显与 231dp 的键盘，五行是上限 */
        const val HIST_ROWS = 5

        /** 一条历史的高度（37 的行 + 4 的间距），用来算滚动区的高度 */
        const val HIST_ROW_DP = 41

        /** 进度条一次按键的跨度，与 NowPlaying 内保持一致 */
        const val SEEK_STEP_MS = 5_000L
    }

    private lateinit var playback: PlaybackController
    private lateinit var home: HomeStage
    private lateinit var stage: NowPlaying

    private lateinit var playerOverlay: View
    private lateinit var hintView: TextView

    private lateinit var searchPanel: View
    private lateinit var searchField: TextView
    private lateinit var searchList: RecyclerView
    private lateinit var searchNote: TextView
    private lateinit var searchHistBox: LinearLayout
    private lateinit var searchHistScroll: ScrollView
    private lateinit var searchHistTitle: TextView
    private val searchAdapter = TrackListAdapter(onClick = ::pickSearch)

    /** 左栏渲染出来的历史词视图，按顺序。焦点上移时拿它判断「是不是已经出左栏了」 */
    private val histViews = ArrayList<TextView>()

    private lateinit var accountPanel: View
    private lateinit var accountName: TextView
    private lateinit var accountSub: TextView
    private lateinit var accountBtn: TextView
    private lateinit var accountDiag: TextView
    private lateinit var accountFace: ImageView
    private lateinit var accountVersion: TextView

    private lateinit var setStyleVal: TextView
    private lateinit var setColorDot: View
    private lateinit var setColorVal: TextView
    private lateinit var setNormVal: TextView
    private lateinit var setBgVal: TextView

    private lateinit var favFolderPanel: FavFolderPanel
    private lateinit var followingPanel: FollowingPanel
    private lateinit var spacePanel: SpacePanel
    private lateinit var colorPalette: ColorPalette

    private lateinit var keypad: Keypad
    private lateinit var login: QrLogin

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = Handler(Looper.getMainLooper())

    private var query = ""
    private var searchJob: Job? = null
    private var accountUser = ""
    private var accountFaceUrl = ""

    /** 播放页是不是当前这一页。首页在它下面，所以两页各自只有一份实例 */
    private var playerOpen = false

    /** 上一次按返回键的时刻。两次之内才退出应用，见 confirmExit */
    private var lastBackAt = 0L

    /** 按键说明只在第一次进播放页时给，回回都给就是噪声 */
    private var keysHintShown = false

    /**
     * 作者主页是不是从播放页点歌手打开的。
     *
     * 正常叠放顺序里作者主页在播放页**下面**（它是播放页的来处）。
     * 从播放页点歌手进去时得反过来盖上去，返回时再退回播放页——
     * 不然用户会看见「什么都没发生」，底下的音乐却还在放。
     */
    private var spaceOverPlayer = false

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bind()
        setupPlayback()
        stage.restore()
        home.show()
        log("启动 首屏 ${System.currentTimeMillis() - t0}ms")
    }

    override fun onStop() {
        super.onStop()
        // 「返回桌面后继续播放」关掉时，一离开前台就暂停。
        //
        // 时机只能在这里：onUserLeaveHint 在部分电视桌面上不回调，onPause 又太早
        // （弹权限框、切输入法都会走到那儿）。onStop 之后这个进程随时会被回收，
        // 播放器却活在 Service 里，所以这一下必须真的把播放器停下来。
        if (::playback.isInitialized && !Settings.backgroundPlay(this) && playback.isPlaying()) {
            playback.toggle()
            log("回桌面暂停（设置：不后台播放）")
        }
        // 进度的最后一点尾巴在这里落盘：进程被回收时不会再有下一次机会
        Resume.flush()
        // 回桌面后进程随时可能被回收，这里就把日志尾巴落掉
        Diag.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        login.release()
        // 只退订，不释放：播放器活在 PlaybackService 里，界面销毁不该把音乐一起关掉
        playback.removeListener(playerListener)
        Diag.flush()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装配

    private fun bind() {
        hintView = findViewById(R.id.hint)
        playerOverlay = findViewById(R.id.player_overlay)

        searchPanel = findViewById(R.id.search_panel)
        searchField = findViewById(R.id.search_field)
        searchList = findViewById(R.id.search_list)
        searchNote = findViewById(R.id.search_note)
        searchHistBox = findViewById(R.id.search_hist_box)
        searchHistScroll = findViewById(R.id.search_hist_scroll)
        searchHistTitle = findViewById(R.id.search_hist_title)
        searchList.layoutManager = ScrollableLayoutManager(this)
        searchList.adapter = searchAdapter
        searchList.itemAnimator = null

        keypad = Keypad(
            container = findViewById<LinearLayout>(R.id.keypad),
            onText = { text ->
                setQuery(text)
                scheduleSearch()
            },
            onSubmit = { text ->
                setQuery(text)
                searchJob?.cancel()
                // 明确提交的这一次才记历史；防抖那些「打到一半」的词不记，
                // 否则历史里会排出 q、qi、qin、qing…… 一屏中间态
                searchJob = scope.launch { runSearch(text, record = true) }
            },
            extraLabel = getString(R.string.search_clear),
            onExtra = {
                Diag.confirm("清空搜索词")
                keypad.clear()
            },
        )

        accountPanel = findViewById(R.id.account_panel)
        accountName = findViewById(R.id.account_name)
        accountSub = findViewById(R.id.account_sub)
        accountBtn = findViewById(R.id.account_btn)
        accountDiag = findViewById(R.id.account_diag)
        accountFace = findViewById(R.id.account_face)
        accountVersion = findViewById(R.id.account_version)
        bindAccountButton()
        paintVersion()

        setStyleVal = findViewById(R.id.set_style_val)
        setColorDot = findViewById(R.id.set_color_dot)
        setColorVal = findViewById(R.id.set_color_val)
        setNormVal = findViewById(R.id.set_norm_val)
        setBgVal = findViewById(R.id.set_bg_val)
        // 四项设置都是「按一下换一个值」。不给每一行单独做开关控件：
        // 电视上左右键改值、确认键换值，两种操作落到同一个结果，行本身就是一个开关
        findViewById<View>(R.id.set_style_row).setOnClickListener { cycleStyle() }
        // 颜色这一行是例外：十色轮一圈要按八下，所以确认键改成开色板一次看全。
        // 左右键仍然逐个轮——老用户的手指已经在那个键上，路径不变
        findViewById<View>(R.id.set_color_row).setOnClickListener { colorPalette.show() }
        findViewById<View>(R.id.set_norm_row).setOnClickListener { toggleNormalize() }
        findViewById<View>(R.id.set_bg_row).setOnClickListener { toggleBackground() }
        colorPalette = ColorPalette(
            activity = this,
            onPick = ::pickColor,
            // 面板一关，里面的格子随面板一起 GONE，焦点必须交回入口那一行
            onClosed = { findViewById<View>(R.id.set_color_row).requestFocus() },
        )
        paintSettings()

        favFolderPanel = FavFolderPanel(
            activity = this,
            scope = scope,
            log = { log(it) },
            hint = { hint(it, autoHide = true) },
            onChanged = { home.invalidate() },
            onClosed = { home.returnFocus() },
        )

        followingPanel = FollowingPanel(
            activity = this,
            scope = scope,
            log = { log(it) },
            onOpenSpace = { mid, name -> openSpace(mid, name) },
            onClosed = { home.returnFocus() },
        )

        spacePanel = SpacePanel(
            activity = this,
            playback = PlaybackController.get(this),
            scope = scope,
            log = { log(it) },
            hint = { hint(it, autoHide = true) },
            onOpenPlayer = {
                if (spaceOverPlayer) {
                    // 从播放页进的主页，又在这儿点了投稿：回播放页看这一首，
                    // 别把人留在主页上——他是从那儿来的
                    backToPlayer()
                } else {
                    openPlayer()
                }
            },
            onClosed = {
                if (spaceOverPlayer) {
                    backToPlayer()
                } else {
                    home.returnFocus()
                }
            },
        )

        login = QrLogin(
            activity = this,
            overlay = findViewById(R.id.login_overlay),
            qrView = findViewById(R.id.login_qr),
            statusView = findViewById(R.id.login_status),
            onLoggedIn = ::onLoggedIn,
            log = ::log,
        )

        home = HomeStage(
            activity = this,
            playback = PlaybackController.get(this),
            scope = scope,
            log = ::log,
            hint = { hint(it, autoHide = true) },
            onOpenPlayer = ::openPlayer,
            onSearch = ::openSearch,
            onAccount = ::openAccount,
            onOpenFavFolder = { create ->
                if (create) favFolderPanel.showCreate() else favFolderPanel.showDelete()
            },
            onOpenFollowing = { followingPanel.show() },
            onOpenSpace = { mid, name -> openSpace(mid, name) },
            onOpenArtist = ::openArtistOf,
        )

        paintSearchField()
        paintAccount()
    }

    private fun setupPlayback() {
        playback = PlaybackController.get(this)
        // 先把 stage 建出来再订阅：播放器可能在任何时刻回调，先订阅后建 stage
        // 中间那几毫秒里来一次 tick，就是一次 lateinit 未初始化的崩溃
        stage = NowPlaying(
            activity = this,
            playback = playback,
            scope = scope,
            log = { log(it) },
            hint = { hint(it, autoHide = true) },
            onToggle = {
                playback.toggle()
                Diag.confirm("确认 播放暂停")
            },
            onCycleMode = ::cycleMode,
            onOpenArtist = ::openArtistOf,
        )
        playback.addListener(playerListener)
    }

    /**
     * 播放器状态订阅。用 listener 而不是构造回调：视图会销毁重建，播放器不会，
     * 所以订阅关系必须能反复挂上和摘掉。界面不在时这个对象也不在，播放器照跑。
     */
    private val playerListener = object : PlaybackController.Listener {
        override fun onTrack(track: Track, index: Int, total: Int) {
            stage.onTrackChanged(track, index, total)
            home.onTrackChanged()
        }

        override fun onTick(positionMs: Long, durationMs: Long, playing: Boolean) {
            stage.onProgress(positionMs, durationMs, playing)
            home.onProgress(positionMs, durationMs, playing)
        }

        override fun onMode(mode: QueueMode) {
            stage.onModeChanged(mode)
            home.paintBar()
        }

        override fun onError(message: String) {
            hint(message, autoHide = true)
        }
    }

    // ------------------------------------------------------------------ 页面切换

    private fun openPlayer() {
        if (playerOpen) return
        playerOpen = true
        home.hide()
        playerOverlay.visibility = View.VISIBLE
        stage.restore()
        if (!keysHintShown) {
            keysHintShown = true
            stage.fadeKeysHint()
        }
        toCover()
    }

    private fun closePlayer() {
        if (!playerOpen) return
        playerOpen = false
        playerOverlay.visibility = View.GONE
        if (spacePanel.isShowing) {
            // 作者主页是这一趟的来处：回到首页等于把用户甩掉，
            // 焦点也该交回它的列表，不是首页的侧栏
            home.show(focusPlaying = false, focusSidebar = false)
            spacePanel.focusBack()
        } else {
            home.show(focusPlaying = true)
        }
    }
    // ------------------------------------------------------------------ 按键总入口

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleKey(event)) return true
        }
        // 不合法的地方在这里：`return false` 不会让事件继续往 View 树走，
        // 只有 super 才会。少了这一句，键盘里的每个键都按不动。
        return super.dispatchKeyEvent(event)
    }

    /**
     * 播放页在最上层：没有浮层压着它。
     *
     * 「浮层」指整屏盖住首页的那种（搜索、我的、扫码）。
     * 作者主页不算——它在 z-order 上位于播放页**下面**，是播放页的来处，
     * 不该盖住播放页的按键。
     */
    private fun playerLayer(): Boolean =
        playerOpen && !login.isShowing &&
            searchPanel.visibility != View.VISIBLE &&
            accountPanel.visibility != View.VISIBLE

    /**
     * 层从高到低的总入口。
     *
     * 顺序必须和屏幕上的叠放顺序一致，也就是 activity_main.xml 里 include 的**反序**：
     *
     *   overlay_login > overlay_following > overlay_fav_folder > panel_account
     *   > panel_search > overlay_now(播放页) > overlay_space(作者主页) > home_stage
     *
     * 之前这一段把作者主页排在了播放页前面，于是从主页点投稿起播之后，
     * 按返回键退掉的是底下那层主页，播放页纹丝不动——真机日志实测过：
     * 「离开 作者主页」而播放页还亮着。用户只会得出一个结论：返回键坏了。
     *
     * 判据是「谁在最上面」，不是「谁开着」：作者主页开着播放页时两层都在，
     * 但只有最上面那层能吃到按键。
     */
    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (login.isShowing) {
            if (code == KeyEvent.KEYCODE_BACK) {
                login.dismiss()
                home.returnFocus()
                return true
            }
            return false
        }
        // 这三层浮层各自接管返回键：浮层里的控件随浮层一起 GONE，
        // 不接返回就等于焦点悬空，之后所有按键都失灵
        if (followingPanel.isShowing) return followingPanel.handleKey(code)
        if (favFolderPanel.isShowing) return favFolderPanel.handleKey(code)
        if (accountPanel.visibility == View.VISIBLE) {
            // 色板压在设置页上面：它开着的时候按键先归它。
            // 返回也只收色板——进了色板要按好几下才能退出去，等于把「退出」变成改色
            if (colorPalette.isShowing) return colorPalette.handleKey(event)
            if (code == KeyEvent.KEYCODE_BACK) {
                closeAccount()
                return true
            }
            // 设置行上左右键也改值。遥控器用户习惯左右调，只留「确认一下换一个」
            // 会让这一页摸不清规律——同样一件事有两种手感，不如两种都给
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                when (currentFocus?.id) {
                    R.id.set_style_row -> {
                        cycleStyle()
                        return true
                    }
                    R.id.set_color_row -> {
                        cycleColor()
                        return true
                    }
                    R.id.set_norm_row -> {
                        toggleNormalize()
                        return true
                    }
                    R.id.set_bg_row -> {
                        toggleBackground()
                        return true
                    }
                }
            }
            return false
        }
        if (searchPanel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) {
                // 字母卡开着：返回是「这个字母不要了」，不是「关掉整个搜索页」。
                // 一次返回只退一层
                if (keypad.dismissPicker()) return true
                closeSearch()
                return true
            }
            // 焦点在历史条上：上下走历史，往右进货键盘，往左没东西
            if (code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
                if (histKey(code)) return true
            }
            // 焦点在键盘上时，方向键先走 Keypad 自己的算术导航。
            // 键宽相等但排列是 3+3+3+4，交给 FocusFinder 的几何搜索会在
            // 字母格与功能键之间乱跳；自己算格号才发现得了「这一下该出左栏了」。
            if (keypad.contains(currentFocus)) {
                val moved = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    -> keypad.move(code)
                    else -> false
                }
                if (moved) return true
                // move() 返回 false 说明上下越界。
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    // 键盘顶上就是历史。没有历史才往右栏够结果——
                    // 交给 FocusFinder 会把焦点交给 RecyclerView 本身，那一层不画高亮，
                    // 表现是「按上之后屏幕上什么都没有」，再按确认也没反应
                    if (!focusLastHistory()) focusSearchResults()
                    return true
                }
                // 最后一排再往下：结果区第一行。键盘到底了，下面没有别的控件，
                // 这个键不交出去就等于告诉用户「下面没了」——而右边整屏都是结果
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusSearchResults()
                    return true
                }
                // 最右一列再往右：结果区第一行。
                // 右边半屏就是结果，按右却卡在「清空」上不动，是最容易被骂的那种「没反应」
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusSearchResults()
                    return true
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && inSearchResults()) {
                keypad.focusFirst()
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && inSearchResults()) {
                keypad.focusFirst()
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP && inSearchResults()) {
                keypad.focusFirst()
                return true
            }
            // 遥控器没有字母键，但插了 USB 键盘或用手机遥控 App 时能直接打。
            // 这一条也让「先打几个字母再挑结果」在调试时可用
            val ch = event.unicodeChar.toChar()
            if (ch.isLetterOrDigit()) {
                keypad.type(ch.lowercaseChar())
                return true
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                keypad.backspace()
                return true
            }
            return false
        }
        // 作者主页从播放页点歌手打开时，它被提到了最上层，按键归它
        if (playerOpen && spaceOverPlayer && spacePanel.isShowing) {
            return spacePanel.handleKey(code)
        }
        // 播放页在作者主页**之上**。作者主页是从这里进来的来处，
        // 它还亮着（在播放页底下），但不因此就抢按键。
        if (playerOpen) return playerKey(event)
        if (spacePanel.isShowing) return spacePanel.handleKey(code)
        // 首页要看见整个 KeyEvent 而不只是键码：按住下键直达底栏靠 repeatCount 判断
        if (home.handleKey(event)) return true
        // 走到这里说明焦点已经在侧栏：首页没有更上一层可退，返回键这时才指向「退出应用」。
        // 按一下就黑屏太容易误触，所以要走 [confirmExit]
        if (code == KeyEvent.KEYCODE_BACK) return confirmExit()
        return false
    }

    /**
     * 连按两次返回才退出。
     *
     * 电视上没有窗口右上角那个叉，返回键是唯一出口，也最容易按错——
     * 从列表退到侧栏之后再按一下就黑屏，用户只会以为应用崩了。
     * 第一次只提示，2.2 秒内再按一次才真的退。
     */
    private fun confirmExit(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < 2_200L) {
            Diag.confirm("退出应用")
            finish()
        } else {
            lastBackAt = now
            hint(getString(R.string.exit_confirm), autoHide = true)
            Diag.confirm("返回键 待确认退出")
        }
        return true
    }

    /**
     * 播放页：方向键只管移动焦点，动作一律点到图标才执行。
     *
     * 上一首 / 下一首 / 播放暂停 / 收藏 / 播放模式都挂在图标上（overlay_now.xml 里
     * 织了一张显式的焦点网），这里只处理两件没法做成图标的事：返回键，
     * 以及进度条上的左右快进快退。
     *
     * 确认键一律放行给焦点上的控件：在这一层再调一次 toggle 就是播两下。
     */
    private fun playerKey(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 列表开着时返回先收列表，再按一次才走：一次返回只退一层
                if (stage.isListOpen()) stage.closeList() else closePlayer()
                true
            }
            // 进度条上左右是快进快退；别处左右是移动焦点，放行给 FocusFinder
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (stage.isSeekFocused) {
                    stage.nudge(-SEEK_STEP_MS)
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (stage.isSeekFocused) {
                    stage.nudge(SEEK_STEP_MS)
                    true
                } else if (stage.isListOpen() && stage.isListFocused()) {
                    // 右栏档位循环的续按。进列表后光标钉在正在播那一行，
                    // 从那儿绕回右下角档位键要按五六下——有歌词时「频谱」档
                    // 会变成实际够不着的一档。右键在列表行上原本无处可去，
                    // 拿它继续轮换，不占任何老路径
                    stage.cyclePane()
                    true
                } else {
                    false
                }
            }
            // 焦点在列表里时上键归列表：往回选歌；
            // 已经到最上面一行再按上，就走到抬头的那个叉上（列表自己走不动了，得有人接手）
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (stage.isListFocused() && stage.listAtTop()) {
                    stage.focusListClose()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    // 从叉往下回到列表：否则焦点会跑到左栏，人还没看列表就被带走了
                    stage.isListCloseFocused() -> {
                        stage.focusCurrentRow()
                        true
                    }
                    // 切到列表档时焦点留在档位键上，按 ↓ 才进曲目表。
                    // 下键在档位键上原本无处可去，拿它当入口不占任何老路径
                    stage.isListOpen() && stage.isPaneButtonFocused() -> {
                        stage.focusCurrentRow()
                        true
                    }
                    else -> false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> false
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                stage.toggleFavorite()
                home.refreshFavorite()
                true
            }
            else -> false
        }
    }

    private fun cycleMode() {
        playback.cycleMode()
        Diag.confirm("切换播放模式")
        hint(
            getString(
                when (playback.mode) {
                    QueueMode.ORDER -> R.string.mode_order
                    QueueMode.ONE -> R.string.mode_one
                    QueueMode.SHUFFLE -> R.string.mode_shuffle
                }
            ),
            autoHide = true,
        )
    }

    private fun toCover() {
        stage.focusPlay()
    }

    // ------------------------------------------------------------------ 搜索

    private fun openSearch() {
        searchPanel.visibility = View.VISIBLE
        keypad.ensureBuilt()
        // 每次打开都从空白开始：上一轮搜过的词、上一轮的结果，一样都不留。
        //
        // 不留的理由不是「干净」，而是留着会骗人：输入框还写着旧词，于是
        // 「有字就收历史」的规则把历史整块挡住——用户重进搜索页看到的是
        // 空荡荡的左栏和上一次的结果，结论只能是「搜索历史没保存」。
        // 文件里其实一条不少（0.27.0 真机上就是这么被当成 bug 的）。
        setQuery("")
        keypad.setInput("")
        searchAdapter.submit(emptyList())
        note(R.string.search_none)
        paintSearchHistory()
        searchPanel.post { keypad.focusFirst() }
        Diag.confirm("打开搜索")
    }

    /**
     * 左栏的历史条。
     *
     * 文件里存 [SearchHistory] 的十条，这里全铺出来，靠外面那层 ScrollView 翻：
     * 左栏一次只放得下五行（键盘占 231dp，上面还有输入回显），但「存了十条只给看五条」
     * 等于没存——第六条往后永远见不到，用户还是会说历史丢了。
     *
     * 每条都是可聚焦的：进搜索页光标落在键盘上，按上就落到最近的那一条，
     * 按确认再搜一次。这是把历史摆出来的全部理由——重搜一个老词不该从空白键盘重新打。
     */
    private fun paintSearchHistory() {
        // 输入框里有字时左栏要留给键盘：刚打开搜索页时显示历史，
        // 一开始打第一个字母历史就收起来。不收的话键盘被挤到屏幕下沿，
        // 打「zhoujielun」时手指按在屏幕外——历史是「进来时」的入口，不是常驻面板。
        //
        // 收起的同时必须把 histViews 清掉。只藏 view 不清账，就会留下四个
        // 「不可见但还登记着」的行：键盘顶行按上时 focusLastHistory() 拿到它们、
        // requestFocus 落空，焦点停在一块 GONE 的容器里——屏幕上没有任何高亮，
        // 之后每个方向键都像按在空处（0.27.0 的实测就是这样）。
        if (query.isNotEmpty()) {
            searchHistScroll.visibility = View.GONE
            searchHistTitle.visibility = View.GONE
            searchHistBox.removeAllViews()
            histViews.clear()
            return
        }
        val words = SearchHistory.load(this).take(HIST_SHOWN)
        searchHistBox.removeAllViews()
        histViews.clear()
        if (words.isEmpty()) {
            searchHistScroll.visibility = View.GONE
            searchHistTitle.visibility = View.GONE
            return
        }
        searchHistScroll.visibility = View.VISIBLE
        searchHistTitle.visibility = View.VISIBLE
        // 高度按实际条数给，最多五行：留死值的话两条历史也占满一屏，键盘被顶出屏幕；
        // 不限高的话第六条起就溢出——文件里存着十条，界面上只看得见五条等于没存
        val d = resources.displayMetrics.density
        val rows = minOf(words.size, HIST_ROWS)
        searchHistScroll.layoutParams = searchHistScroll.layoutParams.apply {
            height = (rows * HIST_ROW_DP * d).toInt()
        }

        for (word in words) {
            val row = layoutInflater.inflate(R.layout.item_hist_word, searchHistBox, false) as TextView
            row.text = word
            row.setOnClickListener { reuseSearch(word) }
            row.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) Diag.enter("搜索历史")
                (v as TextView).setTextColor(
                    if (hasFocus) 0xFF000000.toInt() else getColor(R.color.text_primary)
                )
            }
            searchHistBox.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (37 * resources.displayMetrics.density).toInt(),
                ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() },
            )
            histViews += row
        }
    }

    /** 点一条历史：填回输入框并立刻搜一次，同时把这个词挪到最前 */
    private fun reuseSearch(word: String) {
        setQuery(word)
        keypad.setInput(word)
        SearchHistory.add(this, word)
        paintSearchHistory()
        searchJob?.cancel()
        Diag.confirm("历史再搜 ${word.take(10)}")
        searchJob = scope.launch { runSearch(word, record = true) }
    }

    /**
     * 键盘顶行再往上：落在历史最后一条（离键盘最近的那条）。
     *
     * 返回 requestFocus 的**真实结果**，不是「有没有历史」：历史区藏着的时候
     * histViews 已经被清空，但如果哪天忘了清，这里返回 true 就会把焦点吞掉——
     * 屏幕上一个高亮都没有，而调用方以为「已经交给历史了」，不再往下走。
     */
    private fun focusLastHistory(): Boolean {
        val last = histViews.lastOrNull() ?: return false
        return last.requestFocus()
    }

    /**
     * 历史条上的方向键。
     *
     * 上下在历史之间走；往下走出最后一条就进货键盘——但**落在「清空」而不是最左**，
     * 因为人是从历史往下摸到键盘的，接着要按的多半是重新打一个词，
     * 而落在字母区第一格会让他发现刚才按的两下白按了。
     */
    private fun histKey(code: Int): Boolean {
        val cur = currentFocus as? TextView ?: return false
        val at = histViews.indexOf(cur)
        if (at < 0) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                histViews.getOrNull(at - 1)?.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = histViews.getOrNull(at + 1)
                if (next != null) next.requestFocus() else keypad.focusFirst()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                keypad.focusFirst()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return true
        }
        return false
    }

    private fun closeSearch() {
        // 字母卡是叠在键盘上的独立一层，面板 GONE 带不走它
        keypad.dismissPicker()
        searchPanel.visibility = View.GONE
        // 退出搜索落回侧栏的「搜索」那一项：返回键只退一层，退完该还在原地。
        // 交给 home 的默认落点会回到上一次选过的目的地——搜索是 action 项、
        // 从不成为 dest，光标就跑到别的页上去了
        if (playerOpen) toCover() else home.returnFocus(HomeStage.Dest.SEARCH)
    }

    /** 键盘往上：点亮结果列表的第一行。列表还空着就停在原地 */
    private fun focusSearchResults() {
        if (searchAdapter.itemCount == 0) return
        searchList.post {
            (searchList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
            searchList.post { searchRow(0)?.requestFocus() }
        }
    }

    private fun searchRow(position: Int): View? {
        searchList.findViewHolderForAdapterPosition(position)?.itemView?.let { return it }
        val first = searchList.getChildAt(0) ?: return null
        return searchList.findContainingViewHolder(first)?.itemView
    }

    private fun inSearchResults(): Boolean {
        var view: View? = currentFocus
        while (view != null) {
            if (view === searchList) return true
            view = view.parent as? View
        }
        return false
    }

    /**
     * 换搜索词只从这一个口子进：输入框、左栏的构成（有字就收历史）都跟着这一处走。
     * 分散在三四个地方各写一遍 `query = ...`，迟早漏掉一处——表现是
     * 「历史还摆着，键盘却被挤到屏幕下沿」这种半新半旧的状态。
     */
    private fun setQuery(text: String) {
        query = text
        paintSearchField()
    }

    private fun paintSearchField() {
        if (query.isEmpty()) {
            searchField.text = getString(R.string.search_placeholder)
            searchField.setTextColor(getColor(R.color.text_secondary))
        } else {
            searchField.text = query
            searchField.setTextColor(getColor(R.color.text_primary))
        }
        // 输入行一变，左栏的构成也跟着变（有字就收历史），一处改两处画
        if (searchHistScroll.visibility == View.VISIBLE || query.isEmpty()) paintSearchHistory()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val kw = query.trim()
        if (kw.length < 2) {
            // 一个字母搜出来的东西和没搜一样，还白打一次接口
            searchAdapter.submit(emptyList())
            note(if (kw.isEmpty()) R.string.search_none else R.string.search_empty)
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(kw)
        }
    }

    private suspend fun runSearch(keyword: String, record: Boolean = false) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return

        val result = withContext(Dispatchers.IO) {
            runCatching { SearchApi.search(kw) { log(it) } }
                .onFailure { log("搜索失败 ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
        }
        // 这期间用户又敲了键，这次结果已经过期，丢掉
        if (query.trim() != kw) return

        if (result == null) {
            note(R.string.search_failed)
            return
        }
        log("搜索「$kw」 ${result.size} 首")
        searchAdapter.submit(result)
        if (result.isEmpty()) note(R.string.search_empty) else hideNote()
        // 只有「按了搜索 / 点了历史」才算搜过：这是「再搜一次」的全部来源，搜失败的不记。
        // 防抖搜出来的结果不记——用户打到一半停手看眼结果，历史里就会多一排残缺的词
        if (record) {
            SearchHistory.add(this, kw)
            paintSearchHistory()
            // 明确提交之后把光标直接送进结果区：按了「搜索」的人下一步就是挑一首，
            // 让他再自己找路过去（上？右？)是这一页最没道理的摩擦。
            // 防抖搜不跳——那种时候人还在打字
            if (result.isNotEmpty()) focusSearchResults()
        }
    }

    private fun pickSearch(position: Int, track: Track) {
        // 从结果里挑了一首，说明这个词是真的用上了，进历史
        if (query.isNotBlank()) {
            SearchHistory.add(this, query.trim())
            paintSearchHistory()
        }
        playback.playQueue(searchAdapter.snapshot, position)
        Diag.confirm("搜索起播《${track.title.take(12)}》")
        searchPanel.visibility = View.GONE
        openPlayer()
    }

    private fun note(res: Int) {
        searchNote.text = getString(res)
        searchNote.visibility = View.VISIBLE
    }

    private fun hideNote() {
        searchNote.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 我的

    private fun openAccount() {
        accountPanel.visibility = View.VISIBLE
        paintAccount()
        accountPanel.post { accountBtn.requestFocus() }
        if (LoginApi.isLoggedIn() && accountUser.isEmpty()) refreshAccountName()
        Diag.confirm("打开我的")
    }

    /**
     * 点列表里的歌手列。
     *
     * mid 缺失时拿 bvid 反查一次 view——「正在收听」这一单是播放队列，
     * 队列里可能混着音乐区推荐流与榜单的条目，后者只给作者名字不给 mid。
     * 少了这一步，点下去屏幕上什么都不会发生，看起来就是「打不开主页」。
     *
     * 不用名字去搜人：同名 UP 主太多，搜到的那位很可能不是这一行的人。
     */
    private fun openArtistOf(track: Track) {
        if (track.ownerMid > 0) {
            openSpace(track.ownerMid, track.owner)
            return
        }
        scope.launch {
            log("缺 mid，反查 UP：${track.owner} bvid=${track.bvid}")
            hint(getString(R.string.space_finding), autoHide = true)
            val mid = withContext(Dispatchers.IO) {
                runCatching { BiliApi.fetchView(track.bvid, ::log).owner?.mid ?: 0L }
                    .onFailure { log("反查 UP 失败 ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(0L)
            }
            log("反查结果 mid=$mid")
            if (mid > 0) {
                openSpace(mid, track.owner)
            } else {
                hint(getString(R.string.space_no_mid), autoHide = true)
            }
        }
    }

    /** 作者主页。列表里点歌手、关注列表里点人，都走这里 */
    private fun openSpace(mid: Long, name: String) {
        if (mid <= 0) {
            hint(getString(R.string.space_no_mid), autoHide = true)
            return
        }
        // 关注列表让位：两个都是整屏的浮层，同时开着只能看见上面那个
        followingPanel.dismiss()
        // 从播放页点歌手进来：主页要盖到播放页上面去，看见的才是「人」，
        // 不然点下去屏幕上什么都不变。z-order 在 layout 里是写死的，这里改一次
        if (playerOpen) {
            spaceOverPlayer = true
            findViewById<View>(R.id.space_panel).bringToFront()
        }
        spacePanel.show(mid, name)
    }

    /** 收起作者主页，回到播放页。焦点交给播放键——那是这一页唯一的主按钮 */
    private fun backToPlayer() {
        spaceOverPlayer = false
        spacePanel.dismiss()
        playerOverlay.bringToFront()
        playerOverlay.visibility = View.VISIBLE
        playerOpen = true
        stage.focusPlay()
    }

    private fun closeAccount() {
        // 色板是这一页的子层：整页退掉时它必须一起收，否则下次进设置页会先看到
        // 一张残留色板，且焦点落在那一层上
        colorPalette.dismiss()
        accountPanel.visibility = View.GONE
        if (playerOpen) toCover() else home.returnFocus()
    }

    private fun bindAccountButton() {
        accountBtn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) Diag.enter("我的")
            v.animate().cancel()
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(150)
                .start()
        }
        accountBtn.setOnClickListener { onAccountAction() }
    }

    /**
     * 页脚的应用名与版本号。
     *
     * 版本号取 BuildConfig，与崩溃日志、启动日志同源：日志里写的是哪一版，
     * 屏幕上显示的就是哪一版。想确认电视上装的到底是不是刚推的那个包，
     * 以前只能去系统设置里翻，现在按两下遥控器就能看到。
     *
     * 只在这里赋一次，不进 paintAccount()：名字和版本跟登录状态没关系，
     * 混进去会让「换账号时该刷什么」变得不清楚。
     */
    private fun paintVersion() {
        accountVersion.text = getString(
            R.string.account_version,
            getString(R.string.app_name),
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun paintAccount() {
        if (LoginApi.isLoggedIn()) {
            accountName.text = accountUser.ifEmpty { getString(R.string.nav_account) }
            accountSub.text = getString(R.string.account_logged_sub)
            accountBtn.text = getString(R.string.account_logout)
            // 头像。没拿到就整块收起来：一个空圆比「未登录」三个字更像缺图
            if (accountFaceUrl.isEmpty()) {
                accountFace.visibility = View.GONE
            } else {
                accountFace.visibility = View.VISIBLE
                CoverLoader.load(accountFace, accountFaceUrl, 192, 192)
            }
        } else {
            accountName.text = getString(R.string.account_guest)
            accountSub.text = getString(R.string.account_guest_sub)
            accountBtn.text = getString(R.string.account_login)
            accountFace.visibility = View.GONE
        }
        val crashes = Crash.pending(this)
        accountDiag.visibility = if (crashes > 0) View.VISIBLE else View.GONE
        if (crashes > 0) accountDiag.text = getString(R.string.account_crash, crashes)
        paintSettings()
    }

    // ------------------------------------------------------------------ 播放设置

    /**
     * 四项设置的值。都是「当前值直接写在右边」，不做开关图形——
     * 电视三米外看不出一个 24dp 的拨杆是开还是关，而「开 / 关」两个字不会看错。
     */
    private fun paintSettings() {
        setStyleVal.setText(
            when (Settings.style(this)) {
                Settings.STYLE_WAVE -> R.string.spectrum_wave
                Settings.STYLE_RING -> R.string.spectrum_ring
                else -> R.string.spectrum_bars
            }
        )
        val ci = Settings.colorIndex(this)
        setColorVal.setText(colorLabel(ci))
        // 色点就是那一档颜色本身。只写「紫」不够——十来种颜色各有各的名字，
        // 而用户要选的是「看起来对不对」，不是「这个词叫什么」
        setColorDot.backgroundTintList = ColorStateList.valueOf(Settings.COLORS[ci])
        setNormVal.setText(if (Settings.normalize(this)) R.string.settings_on else R.string.settings_off)
        setBgVal.setText(if (Settings.backgroundPlay(this)) R.string.settings_on else R.string.settings_off)
    }

    private fun colorLabel(i: Int): Int = when (i) {
        1 -> R.string.color_violet
        2 -> R.string.color_amber
        3 -> R.string.color_green
        4 -> R.string.color_pink
        5 -> R.string.color_white
        6 -> R.string.color_red
        7 -> R.string.color_blue
        8 -> R.string.color_gray_light
        9 -> R.string.color_gray_mid
        else -> R.string.color_cyan
    }

    private fun cycleStyle() {
        val v = Settings.cycleStyle(this)
        paintSettings()
        // 两处频谱各重取一次外观。首页底栏那颗小图与播放页那张大图同时生效，
        // 用户不必为了看效果在两页之间来回跑
        stage.refreshSpectrum()
        home.refreshSpectrum()
        hint(
            getString(R.string.settings_style) + "：" + getString(
                when (v) {
                    Settings.STYLE_WAVE -> R.string.spectrum_wave
                    Settings.STYLE_RING -> R.string.spectrum_ring
                    else -> R.string.spectrum_bars
                }
            ),
            autoHide = true,
        )
        Diag.confirm("频谱样式 → $v")
    }

    private fun cycleColor() {
        val v = Settings.cycleColor(this)
        applyColor(v)
    }

    /**
     * 色板上点了一格。
     *
     * 与 [cycleColor] 走同一条收尾：存值、刷新这一行的色点、两处频谱一起换色。
     * 只是这里不弹提示条——人在色板里刚点完，屏幕上那两处频谱就在眼前变，
     * 再飘一行「频谱颜色：紫」是把已经发生的事说一遍。
     */
    private fun pickColor(i: Int) {
        Settings.setColor(this, i)
        paintSettings()
        stage.refreshSpectrum()
        home.refreshSpectrum()
        Diag.confirm("频谱颜色 → $i")
    }

    /** 换色之后的重画。样式与颜色两处共用，省得两边各写一遍再走神 */
    private fun applyColor(v: Int) {
        paintSettings()
        stage.refreshSpectrum()
        home.refreshSpectrum()
        hint(
            getString(R.string.settings_color) + "：" + getString(colorLabel(v)),
            autoHide = true,
        )
        Diag.confirm("频谱颜色 → $v")
    }

    private fun toggleNormalize() {
        val on = !Settings.normalize(this)
        Settings.setNormalize(this, on)
        AudioBus.normalize = on
        paintSettings()
        hint(
            getString(R.string.settings_normalize) + "：" +
                getString(if (on) R.string.settings_on else R.string.settings_off),
            autoHide = true,
        )
        Diag.confirm("响度均衡 → $on")
    }

    private fun toggleBackground() {
        val on = !Settings.backgroundPlay(this)
        Settings.setBackgroundPlay(this, on)
        paintSettings()
        hint(
            getString(R.string.settings_background) + "：" +
                getString(if (on) R.string.settings_on else R.string.settings_off),
            autoHide = true,
        )
        Diag.confirm("后台播放 → $on")
    }

    private fun onAccountAction() {
        if (!LoginApi.isLoggedIn()) {
            login.show()
            return
        }
        LoginApi.logout()
        accountUser = ""
        accountFaceUrl = ""
        home.invalidate()
        paintAccount()
        hint(getString(R.string.account_logout_done), autoHide = true)
        log("已退出登录（buvid 保留）")
    }

    private fun onLoggedIn(uname: String) {
        accountUser = uname
        home.invalidate()
        paintAccount()
        refreshAccountName()
        hint(getString(R.string.login_ok, uname.ifEmpty { "已登录" }), autoHide = true)
        if (accountPanel.visibility == View.VISIBLE) accountPanel.post { accountBtn.requestFocus() }
        else if (playerOpen) toCover()
        else home.returnFocus()
    }

    private fun refreshAccountName() {
        scope.launch {
            val nav = withContext(Dispatchers.IO) {
                runCatching { BiliApi.fetchNav { } }.getOrNull()
            }
            if (nav == null) return@launch
            if (nav.isLogin) {
                accountUser = nav.uname
                accountFaceUrl = nav.face
                paintAccount()
            } else {
                // Cookie 在但服务端不认（过期/被踢）：按未登录处理，别让用户对着一个假登录态
                LoginApi.logout()
                accountUser = ""
                accountFaceUrl = ""
                home.invalidate()
                paintAccount()
            }
        }
    }

    // ------------------------------------------------------------------ 杂项

    /** 底部临时提示：会自动消失，只用于「已加入收藏」这类一秒即走的反馈 */
    private fun hint(text: String, autoHide: Boolean = false) {
        hintView.text = text
        hintView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        ui.removeCallbacksAndMessages(null)
        if (autoHide && text.isNotEmpty()) {
            ui.postDelayed({ hintView.visibility = View.GONE }, HINT_MS)
        }
    }

    private fun log(line: String) {
        Diag.line(line)
    }
}
