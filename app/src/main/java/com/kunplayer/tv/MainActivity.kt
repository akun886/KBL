package com.kunplayer.tv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
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
import com.kunplayer.tv.model.NavData
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
    private lateinit var accountSwitch: TextView
    private lateinit var accountDiag: TextView
    private lateinit var accountFace: ImageView
    private lateinit var accountVersion: TextView

    /** 设置页现在是第几层。根页只选类，子页才放具体的行 */
    private enum class SetPage { ROOT, SPECTRUM, LYRICS, QUALITY, KEYWORDS, MINE }

    private var setPage = SetPage.ROOT

    private lateinit var pageRoot: View
    private lateinit var pageSpectrum: View
    private lateinit var pageLyrics: View
    private lateinit var pageQuality: View
    private lateinit var pageKeywords: View
    private lateinit var pageMine: View

    private lateinit var rowSpectrum: View
    private lateinit var rowLyrics: View
    private lateinit var rowQuality: View
    private lateinit var rowKeywords: View
    private lateinit var rowBg: View
    private lateinit var rowMine: View

    private lateinit var vSpectrum: TextView
    private lateinit var vLyrics: TextView
    private lateinit var vQuality: TextView
    private lateinit var vKeywords: TextView
    private lateinit var vBg: TextView
    private lateinit var vMine: TextView

    private lateinit var setStyleVal: TextView
    private lateinit var setColorDot: View
    private lateinit var setColorVal: TextView
    private lateinit var setNormVal: TextView
    private lateinit var setLyricOnVal: TextView
    private lateinit var setLyricColorDot: View
    private lateinit var setLyricColorVal: TextView
    private lateinit var setLyricFontVal: TextView
    private lateinit var setLyricSizeVal: TextView
    private lateinit var lyricPreview: TextView
    private lateinit var setQualityVal: TextView

    /** 关键词十行。下标就是按钮的位置，两处一一对应 */
    private val kwRows = ArrayList<View>()
    private val kwLabels = ArrayList<TextView>()

    private lateinit var kwScrim: View
    private lateinit var kwTitle: TextView
    private lateinit var kwEcho: TextView
    private lateinit var kwReset: View
    private lateinit var kwKeypad: Keypad

    /** 正在改第几个词。-1 = 浮层没开 */
    private var kwEditing = -1
    private var kwBuffer = ""

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

    /** 会员身份。来自 nav 的 vip.label（「年度大会员」这类原话），未登录为空 */
    private var accountVip = ""

    /** 播放页是不是当前这一页。首页在它下面，所以两页各自只有一份实例 */
    private var playerOpen = false

    /** 上一次按返回键的时刻。两次之内才退出应用，见 confirmExit */
    private var lastBackAt = 0L

    /** 按键说明只在第一次进播放页时给，回回都给就是噪声 */
    private var keysHintShown = false

    /**
     * 作者主页是从哪儿打开的。
     *
     * 这一页能被三个地方点开：首页列表里的歌手列、关注列表里的人、播放页上的歌手名。
     * 返回键必须回**来的那一处**——0.29.8 之前不论从哪儿进来都回首页，
     * 从关注列表点进一个人，返回一下人就被扔回了首页侧栏，还得重新翻一遍关注。
     *
     * 这同时决定了它盖在谁上面：从播放页进来时得盖到播放页之上（不然点下去
     * 屏幕上什么都不变），从首页/关注进来时按 layout 里的自然顺序就够。
     */
    private enum class SpaceFrom { HOME, FOLLOWING, PLAYER }

    private var spaceFrom = SpaceFrom.HOME

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
        accountSwitch = findViewById(R.id.account_switch)
        accountDiag = findViewById(R.id.account_diag)
        accountFace = findViewById(R.id.account_face)
        accountVersion = findViewById(R.id.account_version)
        bindAccountButton()
        paintVersion()

        bindSettingsPages()
        colorPalette = ColorPalette(
            activity = this,
            onPick = ::pickColor,
            // 面板一关，里面的格子随面板一起 GONE，焦点必须交回入口那一行
            onClosed = { returnToSettingsRow() },
        )
        paintSettings()

        favFolderPanel = FavFolderPanel(
            activity = this,
            scope = scope,
            log = { log(it) },
            hint = { hint(it, autoHide = true) },
            onChanged = { home.invalidate() },
            // 它是「我的收藏」这一页的工具条，退出来回原地，不是回上一次选过的目的地
            onClosed = { home.returnFocus(HomeStage.Dest.FAV) },
        )

        followingPanel = FollowingPanel(
            activity = this,
            scope = scope,
            log = { log(it) },
            onOpenSpace = { mid, name -> openSpace(mid, name) },
            // 返回退出关注列表时，光标回到「关注」这一项——它是这次进来的入口
            onClosed = { home.returnFocus(HomeStage.Dest.FOLLOW) },
        )

        spacePanel = SpacePanel(
            activity = this,
            playback = PlaybackController.get(this),
            scope = scope,
            log = { log(it) },
            hint = { hint(it, autoHide = true) },
            onOpenPlayer = {
                if (spaceFrom == SpaceFrom.PLAYER) {
                    // 从播放页进的主页，又在这儿点了投稿：回播放页看这一首，
                    // 别把人留在主页上——他是从那儿来的
                    backToPlayer()
                } else {
                    openPlayer()
                }
            },
            onClosed = ::closeSpace,
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
            // 从播放页退回来：焦点落在「还在播的那首」的封面上，比把眼睛扔进
            // 三十行列表里更接得上刚才那一眼。只落焦点，不改底栏档位
            home.show(focusOnNowPlaying = true)
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
                // 登录是从设置页「我的」按出来的：设置页还开着就把焦点还给它，
                // 别把光标扔到首页侧栏（那看起来像设置页已经关了）
                if (accountPanel.visibility == View.VISIBLE) {
                    accountBtn.requestFocus()
                } else {
                    home.returnFocus(HomeStage.Dest.ACCOUNT)
                }
                return true
            }
            return false
        }
        // 这三层浮层各自接管返回键：浮层里的控件随浮层一起 GONE，
        // 不接返回就等于焦点悬空，之后所有按键都失灵
        if (followingPanel.isShowing) return followingPanel.handleKey(code)
        if (favFolderPanel.isShowing) return favFolderPanel.handleKey(code)
        if (accountPanel.visibility == View.VISIBLE) {
            // 色板与关键词键盘都压在设置页上面：它们开着的时候按键先归它们。
            // 返回也只收最上面那层——进了色板要按好几下才能退出去，
            // 等于把「退出」变成一次改色
            if (colorPalette.isShowing) return colorPalette.handleKey(event)
            if (kwScrim.visibility == View.VISIBLE) return kwKey(event)
            if (code == KeyEvent.KEYCODE_BACK) {
                // 一次返回只退一层：子页先回根页，根页才退出设置。
                // 退之前把「正在离开的是哪一页」记死：回来时光标必须落在这一页那一行。
                // 不这么做就得靠 showPage 里的推断，而它在别的路径上被覆盖过，
                // 表现是「从我的退出来，光标停在歌词设置」这类错位
                if (setPage != SetPage.ROOT) {
                    backTarget = setPage
                    showPage(SetPage.ROOT, focus = true)
                } else {
                    closeAccount()
                }
                return true
            }
            // 设置行上左右键也改值。遥控器用户习惯左右调，只留「确认一下换一个」
            // 会让这一页摸不清规律——同样一件事有两种手感，不如两种都给
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                when (currentFocus?.id) {
                    R.id.set_style_row -> { cycleStyle(); return true }
                    R.id.set_color_row -> { cycleColor(); return true }
                    R.id.set_norm_row -> { toggleNormalize(); return true }
                    R.id.set_row_bg -> { toggleBackground(); return true }
                    R.id.set_lyric_on_row -> { toggleLyrics(); return true }
                    R.id.set_lyric_color_row -> { cycleLyricsColor(); return true }
                    R.id.set_lyric_font_row -> { cycleLyricsFont(); return true }
                    R.id.set_lyric_size_row -> { cycleLyricsSize(); return true }
                    R.id.set_quality_row -> { cycleQuality(); return true }
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
        if (playerOpen && spaceFrom == SpaceFrom.PLAYER && spacePanel.isShowing) {
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
        // 退出搜索落回左上角那颗搜索钮：它就是这次进来的入口，「返回只退一层」
        // 意味着退完该还在原地。搜索不在侧栏里了，不能再按目的地去找落点
        if (playerOpen) toCover() else home.focusSearch()
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
        // 每次从外面进来都落在第一行：上一次待过的子页是「上一次」的进度，
        // 隔了一次开合还记着它，人一进来光标就停在中段某行，像是按错了
        backTarget = SetPage.SPECTRUM
        showPage(SetPage.ROOT, focus = true)
        paintAccount()
        if (LoginApi.isLoggedIn() && accountUser.isEmpty()) refreshAccountName()
        Diag.confirm("打开设置")
    }

    /** 色板收起后把焦点交回打开它的那一行 */
    private fun returnToSettingsRow() {
        val target = when (setPage) {
            SetPage.LYRICS -> R.id.set_lyric_color_row
            else -> R.id.set_color_row
        }
        findViewById<View>(target).requestFocus()
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

    /** 作者主页。列表里点歌手、关注列表里点人、播放页上点歌手名，都走这里 */
    private fun openSpace(mid: Long, name: String) {
        if (mid <= 0) {
            hint(getString(R.string.space_no_mid), autoHide = true)
            return
        }
        // 先认来处再动任何东西：后面每一步（收关注、盖播放页）都按这个判断
        spaceFrom = when {
            followingPanel.isShowing -> SpaceFrom.FOLLOWING
            playerOpen -> SpaceFrom.PLAYER
            else -> SpaceFrom.HOME
        }
        // 关注列表让位：两个都是整屏浮层，同时开着只能看见上面那个。
        // 这里不用 dismissAndReturnFocus——那会把焦点交给首页，而下一步就要盖主页；
        // 回来时由 resume() 自己把焦点还给原来那一行
        if (spaceFrom == SpaceFrom.FOLLOWING) followingPanel.dismiss()
        // 从播放页点歌手进来：主页要盖到播放页上面去，看见的才是「人」，
        // 不然点下去屏幕上什么都不变。z-order 在 layout 里是写死的，这里改一次
        if (spaceFrom == SpaceFrom.PLAYER) {
            findViewById<View>(R.id.space_panel).bringToFront()
        }
        spacePanel.show(mid, name)
    }

    /**
     * 收起作者主页：回它**来的那一层**，焦点也交回那一层。
     *
     * 三处来处各有各的「原地」：
     *   · 播放页 → 回播放页，焦点给播放键；
     *   · 关注 → 回关注列表，焦点钉回刚点的那一行（人不是翻了半天才决定点谁的）；
     *   · 首页 → 回首页侧栏。
     *
     * 0.29.8 之前这里只有「播放页 / 首页」两种结果：从关注点进一个人再返回，
     * 人被送回首页侧栏，还得从头再翻一遍关注列表——这就是「返回跳回首页」那个 bug。
     */
    private fun closeSpace() {
        val from = spaceFrom
        spaceFrom = SpaceFrom.HOME
        spacePanel.dismiss()
        when (from) {
            SpaceFrom.PLAYER -> {
                playerOverlay.bringToFront()
                playerOverlay.visibility = View.VISIBLE
                playerOpen = true
                stage.focusPlay()
            }
            SpaceFrom.FOLLOWING -> followingPanel.resume()
            SpaceFrom.HOME -> home.returnFocus()
        }
    }

    /** 收起作者主页，回到播放页。焦点交给播放键——那是这一页唯一的主按钮 */
    private fun backToPlayer() {
        spaceFrom = SpaceFrom.HOME
        spacePanel.dismiss()
        playerOverlay.bringToFront()
        playerOverlay.visibility = View.VISIBLE
        playerOpen = true
        stage.focusPlay()
    }

    private fun closeAccount() {
        // 子层是这一页的一部分：整页退掉时它们必须一起收，否则下次进设置会先看到
        // 一张残留色板 / 一块键盘，且焦点落在那一层上
        colorPalette.dismiss()
        closeKeywordEditor()
        setPage = SetPage.ROOT
        showPage(SetPage.ROOT, focus = false)
        accountPanel.visibility = View.GONE
        // 退出设置：光标回侧栏「设置」这一项，而不是上一次选过的目的地
        if (playerOpen) toCover() else home.returnFocus(HomeStage.Dest.ACCOUNT)
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
        accountSwitch.setOnClickListener { switchAccount() }
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
            accountName.text = accountUser.ifEmpty { getString(R.string.account_guest) }
            // 副标题写会员身份。没拿到 nav（刚启动、或接口挂了）时退回一句状态说明，
            // 不能空着——空着看起来像「没登录」
            accountSub.text = accountVip.ifEmpty { getString(R.string.account_logged_sub) }
            accountBtn.text = getString(R.string.account_logout)
            accountSwitch.visibility = View.VISIBLE
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
            // 没登录时「切换账号」和「扫码登录」是同一件事，只留一颗
            accountSwitch.visibility = View.GONE
            accountFace.visibility = View.GONE
        }
        val crashes = Crash.pending(this)
        accountDiag.visibility = if (crashes > 0) View.VISIBLE else View.GONE
        if (crashes > 0) accountDiag.text = getString(R.string.account_crash, crashes)
        paintSettings()
    }

    // ------------------------------------------------------------------ 设置

    /** 根页六行 + 五个子页的一次性装配 */
    private fun bindSettingsPages() {
        pageRoot = findViewById(R.id.set_page_root)
        pageSpectrum = findViewById(R.id.set_page_spectrum)
        pageLyrics = findViewById(R.id.set_page_lyrics)
        pageQuality = findViewById(R.id.set_page_quality)
        pageKeywords = findViewById(R.id.set_page_keywords)
        pageMine = findViewById(R.id.set_page_mine)

        rowSpectrum = findViewById(R.id.set_row_spectrum)
        rowLyrics = findViewById(R.id.set_row_lyrics)
        rowQuality = findViewById(R.id.set_row_quality)
        rowKeywords = findViewById(R.id.set_row_keywords)
        rowBg = findViewById(R.id.set_row_bg)
        rowMine = findViewById(R.id.set_row_mine)

        vSpectrum = findViewById(R.id.set_row_spectrum_val)
        vLyrics = findViewById(R.id.set_row_lyrics_val)
        vQuality = findViewById(R.id.set_row_quality_val)
        vKeywords = findViewById(R.id.set_row_keywords_val)
        vBg = findViewById(R.id.set_row_bg_val)
        vMine = findViewById(R.id.set_row_mine_val)

        // 根页六行：五行走子页，一行当场翻。
        // 每一行都只做一件事，所以用「按一下」而不是左右键——左右留给子页里改值
        rowSpectrum.setOnClickListener { showPage(SetPage.SPECTRUM) }
        rowLyrics.setOnClickListener { showPage(SetPage.LYRICS) }
        rowQuality.setOnClickListener { showPage(SetPage.QUALITY) }
        rowKeywords.setOnClickListener { showPage(SetPage.KEYWORDS) }
        rowBg.setOnClickListener { toggleBackground() }
        rowMine.setOnClickListener {
            showPage(SetPage.MINE)
            if (LoginApi.isLoggedIn() && accountUser.isEmpty()) refreshAccountName()
        }

        setStyleVal = findViewById(R.id.set_style_val)
        setColorDot = findViewById(R.id.set_color_dot)
        setColorVal = findViewById(R.id.set_color_val)
        setNormVal = findViewById(R.id.set_norm_val)
        setLyricOnVal = findViewById(R.id.set_lyric_on_val)
        setLyricColorDot = findViewById(R.id.set_lyric_color_dot)
        setLyricColorVal = findViewById(R.id.set_lyric_color_val)
        setLyricFontVal = findViewById(R.id.set_lyric_font_val)
        setLyricSizeVal = findViewById(R.id.set_lyric_size_val)
        lyricPreview = findViewById(R.id.lyric_preview)
        setQualityVal = findViewById(R.id.set_quality_val)
        kwReset = findViewById(R.id.kw_reset)

        // 子页里的行：都是「按一下换一个值」，左右键也能换（见 handleKey）
        findViewById<View>(R.id.set_style_row).setOnClickListener { cycleStyle() }
        // 颜色这一行是例外：十色轮一圈要按八下，所以确认键改成开色板一次看全
        findViewById<View>(R.id.set_color_row).setOnClickListener {
            colorPalette.show(ColorPalette.TARGET_SPECTRUM)
        }
        findViewById<View>(R.id.set_norm_row).setOnClickListener { toggleNormalize() }
        findViewById<View>(R.id.set_lyric_on_row).setOnClickListener { toggleLyrics() }
        findViewById<View>(R.id.set_lyric_color_row).setOnClickListener {
            colorPalette.show(ColorPalette.TARGET_LYRIC)
        }
        findViewById<View>(R.id.set_lyric_font_row).setOnClickListener { cycleLyricsFont() }
        findViewById<View>(R.id.set_lyric_size_row).setOnClickListener { cycleLyricsSize() }
        findViewById<View>(R.id.set_quality_row).setOnClickListener { cycleQuality() }
        kwReset.setOnClickListener { resetKeywords() }

        buildKeywordRows()
        buildKeywordEditor()
    }

    /**
     * 切到某一页。
     *
     * 五页在同一个 FrameLayout 里叠着，切换就是五个 visibility；不复用 RecyclerView
     * 也不做 Fragment：每页只有三四行静态控件，那套机制省的代码比它自己还多。
     *
     * @param focus 切完把光标放到这一页第一行。返回键回根页时用它——
     *              不交焦点的话屏幕上一个高亮都没有，之后每个方向键都像按在空处。
     */
    private fun showPage(page: SetPage, focus: Boolean = true) {
        // 从根页进子页时记下是哪一个：按返回时焦点要落回那一行，
        // 而不是一律回到第一行（人是从第三行进去的，回来还在第三行）
        if (page != SetPage.ROOT && setPage == SetPage.ROOT) backTarget = page
        setPage = page
        pageRoot.visibility = if (page == SetPage.ROOT) View.VISIBLE else View.GONE
        pageSpectrum.visibility = if (page == SetPage.SPECTRUM) View.VISIBLE else View.GONE
        pageLyrics.visibility = if (page == SetPage.LYRICS) View.VISIBLE else View.GONE
        pageQuality.visibility = if (page == SetPage.QUALITY) View.VISIBLE else View.GONE
        pageKeywords.visibility = if (page == SetPage.KEYWORDS) View.VISIBLE else View.GONE
        pageMine.visibility = if (page == SetPage.MINE) View.VISIBLE else View.GONE
        if (!focus) return
        // 焦点要 post 一层再交：这一页刚由 GONE 翻成 VISIBLE，这一帧还没测量完，
        // 立刻 requestFocus 会落空——然后系统自己挑一个（实测挑中 ScrollView 本身，
        // 那一层不画高亮，屏幕上看起来就是「焦点没了」，之后方向键全像按在空处）
        val target: View? = when (page) {
            SetPage.ROOT -> rowOf(backTarget)
            SetPage.SPECTRUM -> findViewById(R.id.set_style_row)
            SetPage.LYRICS -> findViewById(R.id.set_lyric_on_row)
            SetPage.QUALITY -> findViewById(R.id.set_quality_row)
            SetPage.KEYWORDS -> kwRows.firstOrNull()
            SetPage.MINE -> if (LoginApi.isLoggedIn()) accountSwitch else accountBtn
        }
        target?.post { target.requestFocus() }
        Diag.enter("设置-${page.name}")
    }

    /** 从子页返回时该回到根页哪一行。记住刚才那一页，人按返回才回得来 */
    private var backTarget = SetPage.SPECTRUM

    private fun rowOf(page: SetPage): View? = when (page) {
        SetPage.SPECTRUM -> rowSpectrum
        SetPage.LYRICS -> rowLyrics
        SetPage.QUALITY -> rowQuality
        SetPage.KEYWORDS -> rowKeywords
        SetPage.MINE -> rowMine
        SetPage.ROOT -> rowSpectrum
    }

    /**
     * 各处的值。「当前值直接写在右边」，不做开关图形——电视三米外看不出
     * 一个 24dp 的拨杆是开还是关，而「开 / 关」两个字不会看错。
     */
    private fun paintSettings() {
        val style = getString(styleLabel(Settings.style(this)))
        val color = getString(colorLabel(Settings.colorIndex(this)))
        val onOff = getString(if (Settings.lyricsOn(this)) R.string.settings_on else R.string.settings_off)

        // 根页：只说「这一类现在是什么」，不重复子页里每一行的值
        vSpectrum.text = getString(R.string.settings_value_pair, style, color)
        vLyrics.text = getString(R.string.settings_value_pair, onOff, getString(lyricsSizeLabel()))
        vQuality.text = getString(qualityLabel(Settings.quality(this)))
        vKeywords.text = getString(R.string.settings_keywords_count, Settings.KEYWORD_SLOTS)
        vBg.text = getString(if (Settings.backgroundPlay(this)) R.string.settings_on else R.string.settings_off)
        vMine.text = if (LoginApi.isLoggedIn() && accountUser.isNotEmpty()) {
            accountUser
        } else {
            getString(R.string.account_guest)
        }

        // 子页：每一行自己那一项
        setStyleVal.text = style
        setColorVal.text = color
        // 色点就是那一档颜色本身。只写「紫」不够——十来种颜色各有各的名字，
        // 而用户要选的是「看起来对不对」，不是「这个词叫什么」
        setColorDot.backgroundTintList = ColorStateList.valueOf(Settings.COLORS[Settings.colorIndex(this)])
        setNormVal.text = getString(if (Settings.normalize(this)) R.string.settings_on else R.string.settings_off)
        setLyricOnVal.text = onOff
        setLyricColorVal.text = getString(colorLabel(Settings.lyricsColorIndex(this)))
        setLyricColorDot.backgroundTintList =
            ColorStateList.valueOf(Settings.lyricsColor(this))
        setLyricFontVal.text = getString(lyricsFontLabel())
        setLyricSizeVal.text = getString(lyricsSizeLabel())
        setQualityVal.text = getString(qualityLabel(Settings.quality(this)))
        paintLyricPreview()
        paintKeywordValues()
    }

    private fun styleLabel(v: Int): Int = when (v) {
        Settings.STYLE_WAVE -> R.string.spectrum_wave
        Settings.STYLE_RING -> R.string.spectrum_ring
        else -> R.string.spectrum_bars
    }

    private fun lyricsFontLabel(): Int = when (Settings.lyricsFont(this)) {
        Settings.LYRIC_FONT_HEI -> R.string.lyrics_font_hei
        Settings.LYRIC_FONT_SONG -> R.string.lyrics_font_song
        else -> R.string.lyrics_font_default
    }

    private fun lyricsSizeLabel(): Int = when (Settings.lyricsSize(this)) {
        Settings.LYRIC_SIZE_SMALL -> R.string.lyrics_size_small
        Settings.LYRIC_SIZE_LARGE -> R.string.lyrics_size_large
        Settings.LYRIC_SIZE_HUGE -> R.string.lyrics_size_huge
        else -> R.string.lyrics_size_normal
    }

    private fun qualityLabel(v: Int): Int = when (v) {
        Settings.QUALITY_LOSSLESS -> R.string.quality_lossless
        Settings.QUALITY_HIGH -> R.string.quality_high
        Settings.QUALITY_NORMAL -> R.string.quality_normal
        Settings.QUALITY_LOW -> R.string.quality_low
        else -> R.string.quality_auto
    }

    /**
     * 歌词预览。
     *
     * 颜色、字体、字号三件事都是「看起来对不对」，而右边的词（白 / 黑体 / 大）
     * 只能说明改了什么、说明不了改成什么样。这一行按当前设置现画，改一次同屏就能看到。
     */
    private fun paintLyricPreview() {
        lyricPreview.visibility = if (Settings.lyricsOn(this)) View.VISIBLE else View.GONE
        lyricPreview.setTextColor(Settings.lyricsColor(this))
        lyricPreview.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            22f * Settings.lyricsScale(this),
        )
        lyricPreview.typeface = when (Settings.lyricsFont(this)) {
            Settings.LYRIC_FONT_HEI -> Typeface.create("sans-serif", Typeface.NORMAL)
            Settings.LYRIC_FONT_SONG -> Typeface.create("serif", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
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
     * 与 [cycleColor] 走同一条收尾：存值、刷新这一行的色点、该重画的地方一起换色。
     * 只是这里不弹提示条——人在色板里刚点完，屏幕上那处预览就在眼前变，
     * 再飘一行「颜色：紫」是把已经发生的事说一遍。
     *
     * @param target [ColorPalette.TARGET_SPECTRUM] 改频谱，[ColorPalette.TARGET_LYRIC] 改歌词
     */
    private fun pickColor(target: Int, i: Int) {
        if (target == ColorPalette.TARGET_LYRIC) {
            Settings.setLyricsColor(this, i)
            paintSettings()
            stage.refreshLyrics()
            home.refreshLyrics()
            Diag.confirm("歌词颜色 → $i")
        } else {
            Settings.setColor(this, i)
            paintSettings()
            stage.refreshSpectrum()
            home.refreshSpectrum()
            Diag.confirm("频谱颜色 → $i")
        }
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

    // ------------------------------------------------------------------ 歌词设置

    /**
     * 歌词开关。
     *
     * 关掉是把两个页面上「摆歌词」这件事收起来，不是把字幕接口断掉：
     * 歌词本身有磁盘缓存，留着下次打开是零成本，断掉反而要重新等一次网络。
     */
    private fun toggleLyrics() {
        val on = !Settings.lyricsOn(this)
        Settings.setLyricsOn(this, on)
        paintSettings()
        stage.refreshLyrics()
        home.refreshLyrics()
        hint(
            getString(R.string.settings_lyrics_on) + "：" +
                getString(if (on) R.string.settings_on else R.string.settings_off),
            autoHide = true,
        )
        Diag.confirm("显示歌词 → $on")
    }

    /** 左右键逐个轮色。十色一圈要按八下，所以确认键仍然开色板一次看全 */
    private fun cycleLyricsColor() {
        val next = (Settings.lyricsColorIndex(this) + 1) % Settings.COLORS.size
        Settings.setLyricsColor(this, next)
        paintSettings()
        stage.refreshLyrics()
        home.refreshLyrics()
        Diag.confirm("歌词颜色 → $next")
    }

    private fun cycleLyricsFont() {
        val v = Settings.cycleLyricsFont(this)
        paintSettings()
        stage.refreshLyrics()
        home.refreshLyrics()
        hint(getString(R.string.settings_lyrics_font) + "：" + getString(lyricsFontLabel()), autoHide = true)
        Diag.confirm("歌词字体 → $v")
    }

    private fun cycleLyricsSize() {
        val v = Settings.cycleLyricsSize(this)
        paintSettings()
        stage.refreshLyrics()
        home.refreshLyrics()
        hint(getString(R.string.settings_lyrics_size) + "：" + getString(lyricsSizeLabel()), autoHide = true)
        Diag.confirm("歌词大小 → $v")
    }

    // ------------------------------------------------------------------ 音质

    /**
     * 换音质档。
     *
     * 只作废已经解好的流地址，不打断正在播的这首——切档不该让音乐停一下。
     * 下一首（或再点同一首）重新取流时按新档选轨，日志里有「选轨 档位=」可以核对。
     */
    private fun cycleQuality() {
        val v = Settings.cycleQuality(this)
        playback.invalidateStream()
        paintSettings()
        hint(getString(R.string.quality_now, getString(qualityLabel(v))), autoHide = true)
        Diag.confirm("音质 → $v（下一首生效）")
    }

    // ------------------------------------------------------------- 搜索关键词

    /**
     * 十行关键词。行模板是 set_row.xml，按 Settings.KEYWORD_SLOTS 铺——
     * 槽位个数只有一处真源，按钮那排和这一页不会一多一少。
     */
    private fun buildKeywordRows() {
        val box = findViewById<LinearLayout>(R.id.kw_rows)
        box.removeAllViews()
        kwRows.clear()
        kwLabels.clear()
        for (i in 0 until Settings.KEYWORD_SLOTS) {
            val row = layoutInflater.inflate(R.layout.set_row, box, false)
            // 词放在左边主字位：这一页要回答的是「一共十个什么词」，
            // 序号只在心里对位用，不做主字。右值留空，右边那道箭头说明可以改
            val label = row.findViewById<TextView>(R.id.set_row_label)
            row.findViewById<TextView>(R.id.set_row_value).visibility = View.GONE
            val lp = row.layoutParams as LinearLayout.LayoutParams
            lp.topMargin = if (i == 0) 0 else (4 * resources.displayMetrics.density).toInt()
            row.layoutParams = lp
            row.setOnClickListener { openKeywordEditor(i) }
            row.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) Diag.enter("关键词 ${i + 1}")
            }
            box.addView(row)
            kwRows += row
            kwLabels += label
        }
    }

    private fun paintKeywordValues() {
        val words = Settings.keywords(this)
        kwLabels.forEachIndexed { i, v -> v.text = words.getOrNull(i).orEmpty() }
    }

    private fun openKeywordEditor(index: Int) {
        if (index !in 0 until Settings.KEYWORD_SLOTS) return
        kwEditing = index
        // 起手是空的：遥控器上只能打 a-z，把「国语」两个字塞进 buffer 之后
        // 再按字母会变成「国语yueyu」——用户根本删不掉那两个字
        kwBuffer = ""
        kwTitle.text = getString(R.string.kw_editor_title, index + 1)
        paintKeywordEcho()
        kwKeypad.ensureBuilt()
        kwKeypad.setInput("")
        kwScrim.visibility = View.VISIBLE
        kwScrim.post { kwKeypad.focusFirst() }
        Diag.enter("关键词编辑 ${index + 1}")
    }

    /**
     * 输入回显。空的时候显示这个词现在是什么（灰色），
     * 有字就显示正在打的串（白色）——和搜索页那个输入框同一个规矩。
     */
    private fun paintKeywordEcho() {
        if (kwBuffer.isEmpty()) {
            val now = Settings.keywords(this).getOrNull(kwEditing).orEmpty()
            kwEcho.text = if (now.isEmpty()) getString(R.string.kw_editor_hint) else now
            kwEcho.setTextColor(getColor(R.color.text_secondary))
        } else {
            kwEcho.text = kwBuffer
            kwEcho.setTextColor(getColor(R.color.text_primary))
        }
    }

    private fun closeKeywordEditor() {
        kwEditing = -1
        kwBuffer = ""
        kwScrim.visibility = View.GONE
    }

    /** 键盘浮层的按键：返回只退这一层，方向键归九宫格 */
    private fun kwKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            // 字母卡开着时先收卡：一次返回只退一层
            if (kwKeypad.dismissPicker()) return true
            val back = kwRows.getOrNull(maxOf(kwEditing, 0))
            closeKeywordEditor()
            back?.requestFocus()
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            if (kwKeypad.contains(currentFocus)) {
                // 键盘到边界时 move() 返回 false：这一层没有别的东西可去，
                // 吃掉按键，别让焦点跑到浮层背后的设置页上
                kwKeypad.move(code)
                return true
            }
            return false
        }
        val ch = event.unicodeChar.toChar()
        if (ch.isLetterOrDigit()) {
            kwKeypad.type(ch.lowercaseChar())
            return true
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            kwKeypad.backspace()
            return true
        }
        return false
    }

    private fun buildKeywordEditor() {
        kwScrim = findViewById(R.id.kw_scrim)
        kwTitle = findViewById(R.id.kw_title)
        kwEcho = findViewById(R.id.kw_echo)
        kwKeypad = Keypad(
            container = findViewById(R.id.kw_keypad),
            onText = {
                kwBuffer = it
                paintKeywordEcho()
            },
            onSubmit = { saveKeyword(it) },
            submitLabel = getString(R.string.kw_save),
            // 四颗功能键铺满一行：空格 / 删除 / 确定 / 取消。
            // 取消不给返回键当替身——遥控器上那颗键的意思和「这次不改了」不一样，
            // 一个是退，一个是「我知道我没改」
            extraLabel = getString(R.string.fav_folder_cancel),
            onExtra = {
                val back = kwRows.getOrNull(maxOf(kwEditing, 0))
                closeKeywordEditor()
                back?.requestFocus()
            },
        )
    }

    private fun saveKeyword(text: String) {
        val at = kwEditing
        val word = text.trim()
        if (at < 0) {
            closeKeywordEditor()
            return
        }
        val back = kwRows.getOrNull(at)
        closeKeywordEditor()
        if (word.isEmpty()) {
            // 什么都不打就按确定 = 不改了。不写一句「没输入」——
            // 屏幕上的值没变，就是最直接的答复
            back?.requestFocus()
            return
        }
        Settings.setKeyword(this, at, word)
        paintKeywordValues()
        paintSettings()
        // 首页那排按钮是同一份数据的另一处渲染，改完立刻重铺
        home.refreshKeywords()
        back?.requestFocus()
        hint(getString(R.string.kw_saved, at + 1, word), autoHide = true)
        Diag.confirm("关键词 ${at + 1} → $word")
    }

    private fun resetKeywords() {
        Settings.resetKeywords(this)
        paintKeywordValues()
        paintSettings()
        home.refreshKeywords()
        hint(getString(R.string.kw_reset_done), autoHide = true)
        Diag.confirm("关键词恢复默认")
    }

    private fun onAccountAction() {
        if (!LoginApi.isLoggedIn()) {
            login.show()
            return
        }
        LoginApi.logout()
        accountUser = ""
        accountFaceUrl = ""
        accountVip = ""
        home.invalidate()
        paintAccount()
        hint(getString(R.string.account_logout_done), autoHide = true)
        log("已退出登录（buvid 保留）")
    }

    /**
     * 切换账号：先退掉当前这个，再直接弹扫码。
     *
     * 不做「多账号列表」：B 站这套 Cookie 是单会话的（一个 buvid + 一个 SESSDATA），
     * 存两份就要自己管切换时的收藏/历史串号问题，而这个应用只有一个人用。
     * 换人 = 退出 + 重扫，一步不少，但每一步都是用户看得懂的动作。
     */
    private fun switchAccount() {
        LoginApi.logout()
        accountUser = ""
        accountFaceUrl = ""
        accountVip = ""
        home.invalidate()
        paintAccount()
        log("切换账号：已退出旧账号，等待扫码")
        login.show()
    }

    private fun onLoggedIn(uname: String) {
        accountUser = uname
        home.invalidate()
        paintAccount()
        refreshAccountName()
        hint(getString(R.string.login_ok, uname.ifEmpty { "已登录" }), autoHide = true)
        // 登录浮层是从「我的」那一页按出来的：它还开着，焦点交回那一页，
        // 别把人甩回首页（设置页还压在下面，看上去像登录完被踢出来了）
        if (accountPanel.visibility == View.VISIBLE) {
            accountPanel.post { accountSwitch.requestFocus() }
        } else if (playerOpen) {
            toCover()
        } else {
            home.returnFocus(HomeStage.Dest.ACCOUNT)
        }
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
                accountVip = vipTextOf(nav)
                paintAccount()
            } else {
                // Cookie 在但服务端不认（过期/被踢）：按未登录处理，别让用户对着一个假登录态
                LoginApi.logout()
                accountUser = ""
                accountFaceUrl = ""
                accountVip = ""
                home.invalidate()
                paintAccount()
            }
        }
    }

    /**
     * 会员身份那一行写什么。
     *
     * 优先用 nav 自己给的 label（「年度大会员」「大会员」这类原话）——按 status
     * 拼词分不出年度和月度，还会在「大会员但已过期」时写错。label 缺失时才退回
     * status/vipStatus 的粗判断；两者都没有就写「普通用户」，这是真实的信息，
     * 不是占位符。
     */
    private fun vipTextOf(nav: NavData): String {
        val label = nav.vip?.label?.text.orEmpty().trim()
        if (label.isNotEmpty()) return label
        val active = nav.vipStatus == 1 || (nav.vip?.status ?: 0) == 1
        return getString(if (active) R.string.account_vip_member else R.string.account_vip_normal)
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
