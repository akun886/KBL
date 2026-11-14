package com.kunplayer.tv.ui

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.cache.RecCache
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.model.Track
import com.kunplayer.tv.net.CollectionApi
import com.kunplayer.tv.net.FavApi
import com.kunplayer.tv.net.FavState
import com.kunplayer.tv.net.FeedApi
import com.kunplayer.tv.net.HistoryApi
import com.kunplayer.tv.net.LoginApi
import com.kunplayer.tv.net.LyricsApi
import com.kunplayer.tv.net.RecommendApi
import com.kunplayer.tv.net.SearchApi
import com.kunplayer.tv.player.PlaybackController
import com.kunplayer.tv.player.QueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：左边一列目的地，右边是这一目的地的歌单，底下挂着当前这首。
 *
 * 这一页回答的是「今天听什么」——所以它有列表、有封面、有播放全部。
 * 播放页回答的是「这首歌怎么样」，所以那一页只有一首歌。两个问题不一样，
 * 页面就不该是同一个形状。
 *
 * 焦点只有两片：侧栏和列表。方向键交给系统按几何位置走，不自己派发——
 * 这一页和播放页相反：播放页上方向键是「动作」，因为那一页没有可去的地方；
 * 这里有八个入口加几十行歌，方向键就该是「移动」。
 */
class HomeStage(
    private val activity: Activity,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val hint: (String) -> Unit,
    private val onOpenPlayer: () -> Unit,
    private val onSearch: () -> Unit,
    private val onAccount: () -> Unit,
    /** 收藏夹管理。true = 新建，false = 删除 */
    private val onOpenFavFolder: (Boolean) -> Unit,
    /** 关注列表 */
    private val onOpenFollowing: () -> Unit,
    /** 作者主页 */
    private val onOpenSpace: (Long, String) -> Unit,
    /**
     * 点歌手列，但这一行没有 UP 的 mid。
     *
     * 音乐区推荐流、部分榜单条目只给作者名字不给 mid。这时候不能默默
     * 退化成「只把光标停在这一行」——用户已经按下了确认，屏幕上什么都没发生，
     * 看起来就是坏了。交给外面拿 bvid 反查一次。
     */
    private val onOpenArtist: (Track) -> Unit,
) {

    /**
     * 侧栏的一项。action = true 的项不换列表，只是去开一个浮层（搜索、我的、关注）。
     */
    enum class Dest(
        val label: Int,
        val title: Int,
        val sub: Int,
        /** 侧栏里跟在名字前面的小图标。九个都要有：只给前几项加，
         *  后面几项光秃秃地挂着，看起来像是图标没加载完 */
        val icon: Int,
        val action: Boolean = false,
        /**
         * 暂时不建这一项。
         *
         * 「推荐 UP 主」在 0.25.0 下线：那一页的数据来自推荐池聚合，
         * 而推荐池现在是长合集搜索的结果——搜出来的一小时以上的合集，
         * 出歌的人几乎全是搬运号，按曲数排出来的榜首没有意义。
         * 功能本身没删：聚合、关注关系、人物行都还在，这一位改回 false 就回来了。
         */
        val hidden: Boolean = false,
    ) {
        /**
         * 「推荐音乐」排在「音乐」前面（0.29.0）。
         *
         * 侧栏第一项是用户每天第一眼看到、也是方向键第一下落到的地方。这个应用是
         * 「随机听点没听过的」，随机长合集比音乐区推荐流更接近这个用途；
         * 音乐区那一单留着，但它退回第二位。
         */
        /**
         * `sub` 只在承载「布局表达不出来的约束」时才填，其余一律 0。
         *
         * 页头本来就是「26sp 标题 + 一个列表」，标题自己说明这一页是什么；
         * 再补一句「播放器队列」「最近听过」只是把标题换个说法念一遍。多这一行
         * 13sp 灰字，整页就从界面变成说明书。留下来的两条是真约束——推荐池怎么捞
         * （随机关键词 · 一小时以上）、推荐 UP 按什么排——这两个从界面上看不出来。
         */
        REC_MUSIC(R.string.nav_rec_music, R.string.home_title_rec_music, 0, R.drawable.ic_nav_rec),
        MUSIC(R.string.nav_music, R.string.home_title_music, 0, R.drawable.ic_nav_music),
        REC_UP(R.string.nav_rec_up, R.string.home_title_rec_up, R.string.home_sub_rec_up, R.drawable.ic_nav_up, hidden = true),
        QUEUE(R.string.nav_queue, R.string.home_title_queue, 0, R.drawable.ic_nav_queue),
        POP(R.string.nav_pop, R.string.home_title_pop, 0, R.drawable.ic_nav_pop),
        NEW(R.string.nav_new, R.string.home_title_new, 0, R.drawable.ic_nav_new),
        FAV(R.string.nav_fav, R.string.home_title_fav, 0, R.drawable.ic_nav_fav),
        FOLLOW(R.string.nav_follow, 0, 0, R.drawable.ic_nav_follow, action = true),
        HIST(R.string.nav_hist, R.string.home_title_hist, 0, R.drawable.ic_nav_hist),
        /**
         * 搜索在 0.30.0 从侧栏挪到了左上角 LOGO 后面。
         *
         * 它是「动作」不是「去处」：从来不成为 dest，点一下开浮层。摆在十条目的地
         * 中间，每天都要在一列「去哪儿听」里找一行「干一件事」，两件事混在一列里。
         * 挪到品牌位后面之后，侧栏只剩目的地，搜索成了随时够得着的常驻按钮。
         *
         * hidden 留位：action 分支与 fetch 的 when 还在按它穷尽，删掉这一项
         * 就得在四处同步改。这一行改回 false 它就回到侧栏。
         */
        SEARCH(R.string.nav_search, 0, 0, R.drawable.ic_nav_search, action = true, hidden = true),
        ACCOUNT(R.string.nav_account, 0, 0, R.drawable.ic_nav_account, action = true),
    }

    private val root: View = activity.findViewById(R.id.home_stage)
    private val backdrop: ImageView = activity.findViewById(R.id.home_bg)
    private val navBox: LinearLayout = activity.findViewById(R.id.home_nav)
    private val listView: RecyclerView = activity.findViewById(R.id.home_list)
    private val coverBox: View = activity.findViewById(R.id.home_cover_box)
    private val cover: ImageView = activity.findViewById(R.id.home_cover)
    private val title: TextView = activity.findViewById(R.id.home_title)
    private val sub: TextView = activity.findViewById(R.id.home_sub)
    private val playAll: TextView = activity.findViewById(R.id.home_play_all)
    private val refresh: ImageView = activity.findViewById(R.id.home_refresh)
    private val count: TextView = activity.findViewById(R.id.home_count)
    private val note: TextView = activity.findViewById(R.id.home_note)

    /** 品牌位后面那颗搜索钮。侧栏不再有「搜索」这一项，入口只剩这里 */
    private val searchBtn: ImageView = activity.findViewById(R.id.home_search)

    /** 十个快速筛选词。内容与设置里的「搜索关键词」同一份真源 */
    private val chipBox: LinearLayout = activity.findViewById(R.id.home_chips)

    private val chipViews = ArrayList<TextView>()

    /** 当前筛的是第几个词。-1 = 没筛，这一页是随机长合集 */
    private var chipAt = -1

    /** 正在跑的那一次关键词搜索。连按两颗按钮只留最后一颗的结果 */
    private var chipJob: Job? = null

    private val bar: View = activity.findViewById(R.id.home_bar)
    private val barCoverBox: View = activity.findViewById(R.id.home_bar_cover_box)
    private val barCover: ImageView = activity.findViewById(R.id.home_bar_cover)
    private val barTitle: TextView = activity.findViewById(R.id.home_bar_title)
    private val barArtist: TextView = activity.findViewById(R.id.home_bar_artist)
    private val barLyric: TextView = activity.findViewById(R.id.home_bar_lyric)
    private val barSpectrum: SpectrumView = activity.findViewById(R.id.home_bar_spectrum)
    private val barArtistRow: View = activity.findViewById(R.id.home_bar_artist_row)
    private val barBigSpectrum: SpectrumView = activity.findViewById(R.id.home_bar_spectrum_big)
    private val barPaneBox: View = activity.findViewById(R.id.home_bar_pane)
    private val barFav: ImageView = activity.findViewById(R.id.home_bar_fav)
    private val barPrev: ImageView = activity.findViewById(R.id.home_bar_prev)
    private val barPlay: ImageView = activity.findViewById(R.id.home_bar_play)
    private val barNext: ImageView = activity.findViewById(R.id.home_bar_next)
    private val barMode: ImageView = activity.findViewById(R.id.home_bar_mode)
    private val barFill: View = activity.findViewById(R.id.home_bar_fill)

    private val favTools: View = activity.findViewById(R.id.home_fav_tools)
    private val favNew: TextView = activity.findViewById(R.id.home_fav_new)
    private val favDel: TextView = activity.findViewById(R.id.home_fav_del)

    private val adapter = TrackListAdapter(
        onClick = ::pick,
        onArtistClick = { track ->
            if (track.ownerMid > 0) onOpenSpace(track.ownerMid, track.owner) else onOpenArtist(track)
        },
        onFocus = { focusedTrack ->
            focusedTrackOfRow = focusedTrack
            Diag.enter("首页列表")
        },
        // 收藏状态读本地索引，绑定里不发请求
        isFav = { FavState.isFavorited(activity, it.bvid) },
    )

    /** 推荐 UP 主那一页的列表。和曲目表不是同一张，切换目的地时整表换掉 */
    private val upAdapter = UpAdapter(
        onOpen = { mid, name ->
            Diag.confirm("推荐UP主 打开 $name")
            onOpenSpace(mid, name)
        },
        onFocus = { Diag.enter("推荐UP主列表") },
    )

    private val cols: View = activity.findViewById(R.id.home_cols)

    private val navViews = LinkedHashMap<Dest, TextView>()

    /** 列表里光标停在的那一首，MENU 收藏的是它而不是正在播的那首 */
    private var focusedTrackOfRow: Track? = null

    /** 背景模糊当前铺的是哪一首。用来挡住「回来晚了」的旧图 */
    private var backdropKey = ""

    /**
     * 底栏是从哪一头上来的。按上键时按原路回去——从列表长按下来的人不想被送回侧栏，
     * 从侧栏下来的人也不想被扔进三十行里的某一行。
     */
    private enum class BarFrom { SIDEBAR, LIST }

    private var barFrom = BarFrom.LIST

    /** 从列表跳到底栏时，光标原来停在第几行。回来时钉回那一行 */
    private var barReturnRow = -1

    /**
     * 每个目的地只拉一次，来回切不重复打接口；进程内缓存，不做落盘。
     *
     * 缓存仍按「节」存：现在每个目的地都是一节，但收藏那边天然是「一个收藏夹一节」，
     * 统一成同一个类型，渲染就只有一条路。
     */
    private val cache = HashMap<Dest, List<Track>>()

    /**
     * 冷启动落在「推荐音乐」。这一页是「随机听点没听过的」，也是每天开机第一眼
     * 该待的地方；焦点跟着 dest 走，进界面就在侧栏第一项上。
     */
    private var dest = Dest.REC_MUSIC

    /** 冷启动只认第一次进这一页：读盘顶上去，不重新搜。之后每次主动进来照旧换一批 */
    private var recBootstrapped = false

    /**
     * 推荐池。推荐音乐与推荐 UP 主**共用同一次拉取**——
     * 两个入口本来就该是同一批歌和出这批歌的人，各拉一份只会出现
     * 「推荐音乐里有这首，推荐 UP 里却没有这个人」。
     *
     * 它单独放在 cache 外面，因为 cache 是「一次拉完永不变」的语义，
     * 而推荐池要有保鲜期：榜单和新投稿本身在变，冻住就等于天天推同一批。
     */
    private var pool: List<Track>? = null
    private var poolAt = 0L

    /** 推荐池的保鲜期。五分钟：短到一天能换几轮，长到来回切不重复打接口 */
    private val recTtl = 5 * 60 * 1000L

    /**
     * 正在跑的那一次长合集搜索。
     *
     * 一个词三路请求，连按刷新会并发好几轮，既容易被风控，回来的顺序也乱。
     * 新的进来就把旧的取消：用户要的是「最新那一批」，不是「每一批都过一遍」。
     */
    private var recJob: Job? = null

    /** 「换一批」转圈的那只动画。null = 没在转 */
    private var spin: ObjectAnimator? = null

    /**
     * 底栏那行歌词。
     *
     * 只存「当前这首」的一份，按 cid 认（同 bvid 的合集里每一 P 词不同）。
     * 歌词接口对同一首有磁盘缓存，切回来是读文件，不必再打网络。
     */
    private var barLines: List<LyricsApi.Line> = emptyList()
    private var barLyricKey = Long.MIN_VALUE
    private var barLineAt = -2
    private var barLyricJob: Job? = null

    /**
     * 底栏那一格显示什么。三档循环，和播放页右栏「歌词 → 列表 → 频谱」同一个思路：
     * 一个显式档位，一个键换一档，跨页面保持。
     *
     *   LYRIC     歌名 + 歌手 + 当前那句歌词（有词时自动档默认）
     *   SPECTRUM  歌名 + 歌手 + 一行窄频谱（没词时自动档默认）
     *   IMMERSIVE 歌名与歌手收起，切换格撑到两格高，柱子从底边往上长满
     *
     * 三档下切换格都常驻、都可聚焦——它是「歌名 ↓」的唯一入口，谁也不能把它 GONE 掉。
     */
    private enum class BarPane { LYRIC, SPECTRUM, IMMERSIVE }

    /**
     * null = 自动档：有词给词，没词给频谱。用户按一下切换格才落成显式的一档并写盘；
     * 换歌不清回 null——跨页面、跨进程都该停在上次选的那档。
     */
    private var barPane: BarPane? = null

    private fun effectiveBarPane(): BarPane {
        val lyricsAllowed = Settings.lyricsOn(activity)
        val chosen = barPane
            ?: if (lyricsAllowed && barLines.isNotEmpty()) BarPane.LYRIC else BarPane.SPECTRUM
        // 设置里关掉了歌词：显式选着「歌词」这一档也不能把一屏空白端出来
        return if (chosen == BarPane.LYRIC && (!lyricsAllowed || barLines.isEmpty())) {
            BarPane.SPECTRUM
        } else {
            chosen
        }
    }

    /** 文字块（歌名行 + 间距 + 歌手行）的实际总高（px），常态下量一次 */
    private var barTextH = 0

    val isShowing: Boolean get() = root.visibility == View.VISIBLE

    init {
        buildNav()
        buildChips()
        barPane = when (Settings.barPane(activity)) {
            0 -> BarPane.LYRIC
            1 -> BarPane.SPECTRUM
            2 -> BarPane.IMMERSIVE
            else -> null
        }

        listView.layoutManager = ScrollableLayoutManager(activity)
        listView.adapter = adapter
        listView.itemAnimator = null

        searchBtn.setOnClickListener {
            Diag.confirm("首页-搜索")
            onSearch()
        }
        searchBtn.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("搜索按钮")
        }

        playAll.setOnClickListener { playAll() }
        playAll.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("播放全部")
            playAll.animate().cancel()
            playAll.animate()
                .scaleX(if (hasFocus) 1.04f else 1f)
                .scaleY(if (hasFocus) 1.04f else 1f)
                .setDuration(150)
                .start()
        }

        // 「换一批」。每一次按下去都是一次全新的三路搜索，两秒左右才回来——
        // 这期间转圈是唯一的「我听见了」，光把提示字改成「加载中」，
        // 焦点在按钮上的人会以为又按坏了
        refresh.setOnClickListener { reloadLong() }
        refresh.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("换一批")
        }

        // 底栏这一排是七个焦点位：封面、歌名、上一首、播放、下一首、模式、收藏。
        // 0.21 之前中间三颗是裸 ImageView，没有 clickable、代码里也从没引用过——
        // 长得像按钮却按不动，是电视上最坏的一种误导。
        // 焦点只管焦点，不再顺手改界面：以前焦点落封面会自动展开大频谱，
        // 结果「确认进播放页」和「看频谱」挤在同一格上，切换格还被一起 GONE 掉。
        barCoverBox.setOnClickListener { openPlayerOrHint() }
        barCoverBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 封面")
        }
        barTitle.setOnClickListener { openPlayerOrHint() }
        barTitle.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 歌名")
        }
        barPrev.setOnClickListener { stepQueue(-1) }
        barPrev.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 上一首")
        }
        barPlay.setOnClickListener { toggleFromBar() }
        barPlay.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 播放")
        }
        barNext.setOnClickListener { stepQueue(1) }
        barNext.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 下一首")
        }
        barMode.setOnClickListener { cycleModeFromBar() }
        barMode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 模式")
        }
        barFav.setOnClickListener { toggleFavorite(playback.currentTrack()) }
        barFav.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 收藏")
        }

        // 收藏夹管理只对「我的收藏」这一页有意义
        favNew.setOnClickListener { onOpenFavFolder(true) }
        favDel.setOnClickListener { onOpenFavFolder(false) }

        // 跑马灯只在 selected 状态下滚。TextView 默认不可聚焦时不会自己选中，
        // 于是歌词会静静停在那里、超长部分直接被裁掉——「滚动显示」就没了
        barLyric.isSelected = true
        paintLyricStyle()

        // 切换格：确认轮换，左右也轮换。三档下它都常驻，不进底栏那排横向键的链子，
        // 从歌名按下来才进得去、按上回歌名——老路径一格都不多走
        barPaneBox.setOnClickListener { cycleBarPane() }
        barPaneBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) Diag.enter("底栏 切换格")
        }
        bar.viewTreeObserver.addOnGlobalLayoutListener {
            // 只在常态量：沉浸档里歌名是 GONE、歌手行已被撑到两格高，
            // 这时候再拿它俩相加会把自己越算越大
            if (effectiveBarPane() != BarPane.IMMERSIVE && barTitle.height > 0) {
                // 常态两格总高：歌名行 + 歌手行的上间距 + 歌手行，全按真实布局尺寸算
                val rowLp = barArtistRow.layoutParams as? LinearLayout.LayoutParams
                barTextH = barTitle.height + barArtistRow.height + (rowLp?.topMargin ?: 0)
            }
            sizeBarSpectrum()
        }

        paintPaneHint()
        paintBar()
    }

    // ------------------------------------------------------------ 侧栏

    private fun buildNav() {
        for (d in Dest.values()) {
            // 下线的目的地不建行，也就进不去；但它仍在枚举里，所有 when 照旧穷尽
            if (d.hidden) continue
            // 分组标题：不聚焦，只做视觉分段。汽水把「我的音乐」和「更多」
            // 分成两段，八项平铺成一条会读不出层次
            if (d == Dest.FAV) section(R.string.nav_group_mine)
            if (d == Dest.ACCOUNT) section(R.string.nav_group_more)

            val item = LayoutInflater.from(activity)
                .inflate(R.layout.item_nav, navBox, false) as TextView
            item.text = activity.getString(d.label)
            item.setCompoundDrawablesRelative(navIcon(d), null, null, null)
            item.setOnClickListener { activate(d) }
            item.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) Diag.enter("侧栏-${activity.getString(d.label)}")
                paintNavIcon(v as TextView, d, hasFocus)
            }
            navBox.addView(item)
            navViews[d] = item
        }
    }

    /**
     * 侧栏图标。矢量图自带 24dp 的固有尺寸，直接塞进 36dp 高的行里会顶满，
     * 与 14sp 的字并排显得头重——统一压到 18dp 再交给 TextView。
     *
     * 每个 item 都要 mutate 出一个独立副本：drawable 缓存是共享的，
     * 不 mutate 的话给「音乐」刷白，其余八项的图标会一起变白。
     */
    private fun navIcon(d: Dest): Drawable? {
        val dr = activity.getDrawable(d.icon)?.mutate() ?: return null
        val side = (18 * activity.resources.displayMetrics.density + 0.5f).toInt()
        dr.setBounds(0, 0, side, side)
        return dr
    }

    /** 图标跟着文字一起变色：选中或光标停着的时候是白的，其余是次要灰。
     *  图标不跟状态，白字配灰图标，一眼看过去像是禁用项 */
    private fun paintNavIcon(item: TextView, d: Dest, focused: Boolean) {
        val lit = focused || d == dest
        item.compoundDrawablesRelative.getOrNull(0)?.setTint(
            activity.getColor(if (lit) R.color.text_primary else R.color.text_secondary)
        )
    }

    private fun section(labelRes: Int) {
        val label = TextView(activity)
        label.text = activity.getString(labelRes)
        label.setTextColor(activity.getColor(R.color.text_secondary))
        label.textSize = 10f
        label.letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.marginStart = 14
        lp.topMargin = 6
        lp.bottomMargin = 2
        navBox.addView(label, lp)
    }

    private fun activate(d: Dest) {
        // 空列表上按确认，光标还在原处。回到「我的收藏」时它本来就该在侧栏
        if (d.action) {
            when (d) {
                Dest.SEARCH -> onSearch()
                Dest.ACCOUNT -> onAccount()
                Dest.FOLLOW -> onOpenFollowing()
                else -> Unit
            }
            return
        }
        // 就在这一页上再按一次：这是「换一批」。只有推荐类有这个语义——
        // 别处再按一次什么都不发生，那才是对的
        if (d == dest && d.isRecommend()) {
            // 「推荐音乐」本来就每次进来重搜，不用清什么；「推荐 UP 主」得把
            // 保鲜期戳掉，否则再按一次还是缓存里那一份
            if (d == Dest.REC_UP) poolAt = 0L
            Diag.confirm("刷新-${activity.getString(d.label)}")
            select(d, focusList = false)
            return
        }
        select(d, focusList = false)
        Diag.confirm("首页-${activity.getString(d.label)}")
    }

    private fun paintNav() {
        for ((d, view) in navViews) {
            view.isSelected = d == dest
            paintNavIcon(view, d, view.isFocused)
        }
    }

    // ------------------------------------------------------------ 快速筛选词

    /**
     * 十个筛选按钮。
     *
     * 它们替代的是原来那行「随机关键词 · 一小时以上」——那句话在解释「这一页是怎么来的」，
     * 而这一页真正缺的是「我只想听粤语」这个动作。同样占一行高度，一行字换成十个入口。
     *
     * 词本身不写死在代码里：设置页「搜索关键词」改完，这里按同一份
     * [Settings.keywords] 重铺，界面上看到的就是真正会发出去的那十个词。
     */
    private fun buildChips() {
        chipBox.removeAllViews()
        chipViews.clear()
        for (word in Settings.keywords(activity)) {
            val chip = TextView(activity)
            chip.text = word
            chip.setTextColor(activity.getColor(R.color.text_secondary))
            chip.textSize = 12f
            chip.gravity = android.view.Gravity.CENTER
            chip.background = activity.getDrawable(R.drawable.bg_chip)
            chip.isClickable = true
            chip.isFocusable = true
            chip.isFocusableInTouchMode = true
            chip.setPadding(dp(9), 0, dp(9), 0)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26),
            ).apply { marginEnd = dp(6) }
            chip.setOnClickListener { quickSearch(chipViews.indexOf(chip)) }
            chip.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) Diag.enter("筛选词-${(v as TextView).text}")
                paintChip(v as TextView, hasFocus)
            }
            chipBox.addView(chip, lp)
            chipViews += chip
        }
    }

    private fun paintChip(v: TextView, hasFocus: Boolean) {
        val at = chipViews.indexOf(v)
        val picked = at >= 0 && at == chipAt
        v.setTextColor(
            when {
                hasFocus -> 0xFF000000.toInt()
                picked -> activity.getColor(R.color.accent)
                else -> activity.getColor(R.color.text_secondary)
            }
        )
        v.background = activity.getDrawable(if (hasFocus) R.drawable.bg_chip_focus else R.drawable.bg_chip)
    }

    private fun paintChips() {
        for (v in chipViews) paintChip(v, v.isFocused)
    }

    /**
     * 设置里改过关键词：整排重铺。
     *
     * 页面内容不动——改的是按钮上的字，正在看的那一屏没有理由跟着刷新。
     * 但如果当前正筛着某个词、而这个词刚好被改了，高亮就作废（见下面的夹取），
     * 否则屏幕上会标着一个已经不存在的词。
     */
    fun refreshKeywords() {
        buildChips()
        if (chipAt >= chipViews.size) chipAt = -1
        paintChips()
    }

    /**
     * 按一个词立刻搜一屏。
     *
     * 走的是普通搜索（不是「一小时以上」那条长合集搜索）：筛选词里有「无损」「Hi-Res」
     * 这种指向短音频的词，硬套一小时闸门会搜出空页。搜到什么就听什么，这一页的
     * 标题仍然只写「推荐音乐」——按钮自己已经在说「现在筛的是哪个词」。
     */
    private fun quickSearch(at: Int) {
        val word = chipViews.getOrNull(at)?.text?.toString().orEmpty()
        if (word.isEmpty()) return
        val again = at == chipAt
        chipAt = at
        paintChips()
        // 上一次长合集还在跑就停掉：它回来会把这一屏换成随机那批
        recJob?.cancel()
        recJob = null
        spinRefresh(false)
        // 标题跟着换成这个词：底下已经是「国语」的结果了，标题还写「推荐音乐」
        // 就是在说假话——这一屏此刻不是推荐，是这个词的搜索
        title.setText(word)
        note.text = activity.getString(R.string.home_loading)
        chipJob?.cancel()
        Diag.confirm("快速搜索「$word」${if (again) "重搜" else ""}")
        chipJob = scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { SearchApi.search(word, log = log) }
                    .onFailure { log("快速搜索失败 ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            // 这期间已经切走、或者又点了别的词：这份结果不该上屏
            if (dest != Dest.REC_MUSIC || chipAt != at) return@launch
            if (got == null) {
                paintNote(R.string.home_empty)
                return@launch
            }
            render(got)
            if (got.isEmpty()) paintNote(R.string.home_empty)
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    // ------------------------------------------------------------ 取数

    /** 进入某个目的地。focusList 为 true 时把焦点交给列表第一行（选中正在播的那首） */
    fun select(d: Dest, focusList: Boolean) {
        if (d.action) return
        dest = d
        paintNav()
        title.setText(d.title)
        // 没有副标题就把这一行整个收掉。留一个空 TextView 不算「没显示」：
        // 它的 marginTop 6dp 还在，标题和下面那排按钮之间会多出一条说不清的空隙
        if (d.sub != 0) {
            sub.setText(d.sub)
            sub.visibility = View.VISIBLE
        } else {
            sub.text = ""
            sub.visibility = View.GONE
        }
        playAll.isEnabled = true
        // 「换一批」只在推荐音乐里出现。它按下去换的是这一页的内容，
        // 别的页没有可换的东西——留着就是一颗按不动的按钮
        refresh.visibility = if (d == Dest.REC_MUSIC) View.VISIBLE else View.GONE
        // 筛选词同理：它们是这一页的取数方式，别的页没有可筛的东西
        chipBox.visibility = if (d == Dest.REC_MUSIC) View.VISIBLE else View.GONE
        if (d == Dest.REC_MUSIC) {
            // 重新进这一页 = 回到「随机那一批」。筛选是当场那一次的动作，
            // 不是这个页面的长期状态：留着高亮会让人以为明天开机还是只放粤语
            chipAt = -1
            chipJob?.cancel()
            chipJob = null
            paintChips()
        }
        // 走到别的页就停手：转圈的动画和还在路上的那一次搜索都不该跟着走。
        // 不停的话，图标在看不见的地方一直转，动画对象也一直留着
        if (d != Dest.REC_MUSIC) {
            recJob?.cancel()
            recJob = null
            spinRefresh(false)
        }
        // 收藏夹管理只在这一页出现：别的地方没有收藏夹可管
        favTools.visibility = if (d == Dest.FAV) View.VISIBLE else View.GONE

        // 队列是播放器的镜像，不是一份可以冻住的榜单：每次进来都读实时值。
        // 存进 cache 就会在换过歌单之后继续显示上一轮——「正在收听」和「流行」
        // 长得一样本来只是因为它就是从流行起播的那一队列，缓存再冻一次，
        // 连「换一页起播」都不再反映。
        if (d == Dest.QUEUE) {
            render(playback.queueSnapshot())
            if (focusList) focusList()
            return
        }

        // 「推荐音乐」：冷启动先端出上一次换的那一批，不自动重搜。
        //
        // 开机第一眼要的是「昨天那批还在」，不是先空两三秒再整屏换掉；重搜是
        // 用户主动要的，入口是「换一批」和在侧栏上再确认一次。只有从没换过
        // （读盘为空）才在冷启动拉一次——一屏空白比等两秒更糟。之后每次主动
        // 进来仍重抽关键词，换到新的一批随手落盘。
        if (d == Dest.REC_MUSIC) {
            if (!recBootstrapped) {
                recBootstrapped = true
                val last = RecCache.load(activity)
                if (last.isNotEmpty()) {
                    renderRecommend(Dest.REC_MUSIC, last)
                    if (focusList) focusList()
                    return
                }
            }
            loadLong(focusRefresh = !focusList, focusListAfter = focusList)
            return
        }

        // 「推荐 UP 主」仍然吃那个有保鲜期的曲池：它要的是「谁在出歌」，
        // 数据几分钟内不会变，重复拉只是打接口。
        if (d.isRecommend()) {
            val ready = pool
            if (ready != null && System.currentTimeMillis() - poolAt < recTtl) {
                renderRecommend(d, ready)
                if (focusList) focusList()
                return
            }
            note.text = activity.getString(R.string.home_loading)
            scope.launch {
                val got = withContext(Dispatchers.IO) {
                    runCatching { songPool(force = false) }
                        .onFailure { log("推荐池失败 ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                }
                if (dest != d) return@launch
                // 拉失败了但手里还有上一轮的池子，就把旧的接着用——
                // 电视上「一屏旧歌」永远好过「一屏错误」
                val show = got ?: pool.orEmpty()
                renderRecommend(d, show)
                if (got == null && show.isEmpty()) paintNote(R.string.home_empty)
                if (focusList && show.isNotEmpty()) focusList()
            }
            return
        }

        cache[d]?.let {
            render(it)
            if (focusList) focusList()
            return
        }

        if (!loggedIn() && (d == Dest.FAV || d == Dest.HIST)) {
            render(emptyList())
            paintNote(if (d == Dest.FAV) R.string.fav_need_login else R.string.history_need_login)
            return
        }

        note.text = activity.getString(R.string.home_loading)
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { fetch(d) }
                    .onFailure { log("首页 ${d.name} 失败 ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            // 加载期间用户可能已经切走了：这份结果只进缓存，不上屏
            val data = got.orEmpty()
            if (got != null) cache[d] = data
            if (dest != d) return@launch
            render(data)
            if (data.isEmpty() && got == null) paintNote(R.string.home_empty)
            if (focusList && data.isNotEmpty()) focusList()
        }
    }

    private fun fetch(d: Dest): List<Track> = when (d) {
        Dest.MUSIC -> FeedApi.musicRegion(log)
        Dest.QUEUE -> playback.queueSnapshot()  // select() 里已短路，这里只为穷尽 when
        Dest.REC_MUSIC, Dest.REC_UP -> songPool(force = false)  // select() 里已短路，这里只为穷尽 when
        Dest.POP -> FeedApi.popRank(log)
        Dest.NEW -> FeedApi.newest(log)
        Dest.FAV -> FavApi.all(LoginApi.mid(), log)
        Dest.HIST -> HistoryApi.recent()
        Dest.SEARCH, Dest.ACCOUNT, Dest.FOLLOW -> emptyList()
    }

    /** 两个推荐入口的共同特征：都不走 cache 那条「一次拉完永不变」的路 */
    private fun Dest.isRecommend(): Boolean = this == Dest.REC_MUSIC || this == Dest.REC_UP

    /**
     * 拉一批长合集：随机抽三个关键词、三路搜索、只留一小时以上的，端出三十首。
     *
     * 「进入页面」和「按换一批」走的是同一条路，区别只在焦点：进来时交给那颗按钮
     * （这一页唯一的新动作就是它），按按钮刷新时焦点本来就在上面。
     *
     * 刷新不整屏清空——上一批先留着，只在底下写一行「加载中」。清空再等两三秒，
     * 看起来像是把歌弄丢了；新的一到就整批换掉，也不和新旧混着显示。
     */
    private fun loadLong(focusRefresh: Boolean, focusListAfter: Boolean) {
        // 上一轮还在跑就取消：连按几下不该同时打三路 × 好几轮，
        // 回来的顺序还会乱（后发的先到，屏幕上就是两批混着）
        recJob?.cancel()
        // 「换一批」是「不要筛了，重抽」：筛词高亮跟着落下，否则屏幕上还标着粤语，
        // 底下已经是随机那批，人会以为筛选坏了
        chipAt = -1
        chipJob?.cancel()
        paintChips()
        // 筛词退场，标题也退回本来的名字（见 quickSearch 里为什么改它）
        title.setText(Dest.REC_MUSIC.title)
        note.text = activity.getString(R.string.home_loading)
        spinRefresh(true)
        if (focusRefresh) refresh.requestFocus()
        recJob = scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { FeedApi.recommendCollections(log) }
                    .onFailure { log("长合集失败 ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            // 这期间已经切走：这份结果直接丢掉，屏幕不归它了
            if (dest != Dest.REC_MUSIC) return@launch
            spinRefresh(false)
            if (got == null) {
                // 手里还有上一批就留着：电视上一屏旧歌永远好过一屏错误
                if (adapter.trackCount == 0) {
                    paintNote(R.string.home_empty)
                } else {
                    note.text = ""
                    hint(activity.getString(R.string.home_refresh_failed))
                }
                return@launch
            }
            renderRecommend(Dest.REC_MUSIC, got)
            // 这一批就是「上一次的刷新结果」：下次冷启动直接端上屏
            RecCache.save(activity, got)
            if (focusListAfter && got.isNotEmpty()) focusList()
        }
    }

    /** 按「换一批」。焦点留在按钮上，再按一次就是再换一批 */
    private fun reloadLong() {
        Diag.confirm("换一批")
        loadLong(focusRefresh = true, focusListAfter = false)
    }

    /**
     * 转圈。
     *
     * 一次刷新要两秒左右，这期间屏幕上唯一在动的就是这颗图标——
     * 没有它，按下去和没按下去长得一模一样，用户会以为遥控器没电了。
     * 用 View 的 rotation 而不是换帧：一张矢量图转起来就够，不必再压一套 GIF。
     */
    private fun spinRefresh(on: Boolean) {
        if (on) {
            val anim = spin ?: ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }.also { spin = it }
            anim.start()
        } else {
            spin?.cancel()
            spin = null
            refresh.rotation = 0f
        }
    }

    /**
     * 拉推荐池。保鲜期内直接给上一轮那份。
     *
     * `force` 是「用户又按了一次这一项」——那是明确要求换一批。
     * 只在真的要去拉的时候才检查收藏索引：它在第一次会连着打几个接口，
     * 放在这里的意义是「等页面要显示心形时，索引已经在内存里了」。
     */
    private fun songPool(force: Boolean): List<Track> {
        val now = System.currentTimeMillis()
        val cached = pool
        if (!force && cached != null && now - poolAt < recTtl) return cached
        val fresh = FeedApi.recommendMusic(log)
        pool = fresh
        poolAt = now
        return fresh
    }

    private fun renderRecommend(d: Dest, list: List<Track>) {
        if (d == Dest.REC_UP) {
            renderUps(list)
            return
        }
        render(list)
        // 收藏索引可能还没建过（第一次开这个应用、或从没进过收藏页）。
        // 建好之后补一次绑定，心形才出得来——列表已经上屏了，不必为它等
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { FavState.ensureIndexed(activity, log) } }
            if (dest == Dest.REC_MUSIC) adapter.notifyDataSetChanged()
        }
    }

    /**
     * 推荐 UP 主那一页。
     *
     * 列表从池子聚合出来是同步的（纯内存分组），所以先上屏；
     * 「已关注」要联网（一次批量查，不逐个查），晚到一步，到了再补标记。
     */
    private fun renderUps(pool: List<Track>) {
        val ups = RecommendApi.ups(pool)
        listView.adapter = upAdapter
        upAdapter.submit(ups)
        upAdapter.setFollowed(emptySet())
        listView.scrollToPosition(0)
        // 曲目表那份列头在这一页对不上，藏掉
        cols.visibility = View.GONE
        count.text = activity.getString(R.string.home_count_up, ups.size)
        // 这一页没有「播放全部」可言：一列人和一列歌不是一回事
        playAll.isEnabled = false
        playAll.alpha = 0.4f

        val head = ups.firstOrNull()?.sample
        paintCover(head)
        paintBackdrop(head)
        note.text = if (ups.isEmpty()) activity.getString(R.string.home_empty) else ""

        if (ups.isEmpty()) return
        val tag = ++upTag
        val mids = ups.map { it.mid }.toSet()
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                runCatching { RecommendApi.followedSet(log) }.getOrDefault(emptySet())
            }
            // 这期间已经切走或又刷了一次，这份结果就过期了
            if (dest != Dest.REC_UP || upTag != tag) return@launch
            upAdapter.setFollowed(all intersect mids)
        }
    }

    /** UP 列表的刷新代次。晚到的关注关系不能盖在新一版列表上 */
    private var upTag = 0

    /**
     * 把「正在播」的标记打到列表上。
     *
     * cid 和 bvid 两个都得给：搜索来源的 cid 恒为 0，只给 cid 的话
     * 「列表里没有这一首」和「整张表都是这一首」会变成同一个判断。
     * 具体判定在 TrackListAdapter.isCurrent 里。
     */
    private fun paintNow() {
        val t = playback.currentTrack()
        adapter.currentCid = t?.cid ?: 0L
        adapter.currentBvid = t?.bvid.orEmpty()
        adapter.currentPlaying = playback.isPlaying()
    }

    private fun render(list: List<Track>) {
        // 从「推荐 UP 主」切回来时，列表上挂的还是人物表，要整张换掉
        if (listView.adapter !== adapter) listView.adapter = adapter
        cols.visibility = View.VISIBLE
        adapter.submit(enrich(list))
        // 先装数据再标「正在播」：标的那一步要按新列表找行号
        paintNow()
        // 换目的地必须回到顶部：RecyclerView 会记住上一份数据滚到哪
        listView.scrollToPosition(0)
        count.text = activity.getString(R.string.home_count, list.size)
        playAll.isEnabled = list.isNotEmpty()
        playAll.alpha = if (list.isEmpty()) 0.4f else 1f

        val head = if (dest == Dest.QUEUE) playback.currentTrack() else list.firstOrNull()
        paintCover(head)
        paintBackdrop(head)

        when {
            list.isNotEmpty() -> note.text = ""
            dest == Dest.FAV -> paintNote(R.string.fav_cloud_empty)
            dest == Dest.HIST -> paintNote(R.string.history_empty)
            dest == Dest.QUEUE -> paintNote(R.string.now_nothing)
            else -> paintNote(R.string.home_empty)
        }
        rescueFocusIfLost()
    }

    /**
     * 换单之后焦点可能挂在一个已经藏起来的控件上。
     *
     * 最典型的是「我的收藏」页那对「新建 / 删除收藏夹」——它们只在这一单里出现，
     * 切走就 GONE。只要焦点此刻停在上面，屏幕上就再没有任何高亮，
     * 之后每个方向键都像按在空处，用户看到的是「遥控器失灵」。
     *
     * 判据只看 `isShown`：控件自己或任一层父容器不可见，都算悬空。
     * 交回侧栏里当前这一项——那是用户刚按下确认的地方。
     *
     * 但「不可见」有两种：一种是藏在 GONE 的容器里（真悬空），另一种是列表
     * 刚换了一批数据、那一行已经被回收（视图已经离树）。后者不用管，
     * 焦点自己会跟着新布局走。用 `isAttachedToWindow` 把两者分开。
     */
    private fun rescueFocusIfLost() {
        val f = activity.currentFocus ?: return
        if (f.isShown) return
        if (!f.isAttachedToWindow) return
        navViews[dest]?.requestFocus()
    }

    private fun paintCover(track: Track?) {
        val url = track?.cover.orEmpty()
        // 封面框一直留着：这是「一张歌单」的封面位，空着也知道这里该有图，
        // 和播放页那个「空了就是没歌」的封面框不是一回事
        coverBox.visibility = View.VISIBLE
        if (url.isEmpty()) {
            cover.setImageDrawable(null)
            return
        }
        // 头图 280×158dp，2 倍密度 560×316px；16:9 取图，保住 B 站原封面的完整画面
        CoverLoader.load(cover, FeedApi.reCrop(url, 560, 316), 560, 316)
    }

    /**
     * 整页背景：当前歌单的封面放大模糊。
     *
     * 和播放页共用 [Blur] 的缓存（按 bvid 存 480×270，只留四张）。首页按「这一单
     * 的第一首」取图，播放页按「正在播的那首」取图——两页同一张歌单时命中的是
     * 同一个 key，来回切不重算。
     *
     * 换目的地要换图，所以必须挡住「回来晚了」的那一张：用户在 A 单停留的
     * 300ms 里已经切到 B 单，A 的图这时候回来不能糊到 B 上。
     */
    private fun paintBackdrop(track: Track?) {
        val url = track?.cover.orEmpty()
        if (url.isEmpty()) {
            backdropKey = ""
            backdrop.setImageDrawable(null)
            backdrop.alpha = 0f
            return
        }
        val want = track!!.bvid
        backdropKey = want
        CoverLoader.fetch(FeedApi.reCrop(url, 480, 270), 480, 270) { bmp ->
            if (bmp == null || backdropKey != want) return@fetch
            backdrop.setImageBitmap(Blur.of(want, bmp))
            // 淡入而不是直接换：同一位置换一张模糊图，硬切会闪一下
            backdrop.animate().alpha(0.8f).setDuration(320).start()
        }
    }

    private fun paintNote(res: Int) {
        note.text = activity.getString(res)
    }

    // ------------------------------------------------------------ 播放

    private fun playAll() {
        // 「推荐 UP 主」没有可播的东西，那个按钮在这一页是置灰的
        if (dest == Dest.REC_UP) return
        val list = adapter.snapshot
        if (list.isEmpty()) {
            hint(activity.getString(R.string.home_empty))
            return
        }
        playback.playQueue(list, 0)
        Diag.confirm("播放全部 ${list.size} 首")
        onOpenPlayer()
    }

    private fun pick(position: Int, track: Track) {
        playback.playQueue(adapter.snapshot, position)
        Diag.confirm("首页起播 第${position + 1}首《${track.title.take(12)}》")
        onOpenPlayer()
    }

    private fun openPlayerOrHint() {
        if (!playback.hasTrack()) {
            hint(activity.getString(R.string.home_bar_empty))
            return
        }
        Diag.confirm("底栏进播放页")
        onOpenPlayer()
    }

    // ------------------------------------------------------------ 底栏

    fun onTrackChanged() {
        paintBar()
        // 不管当前在哪一单，行上的红条都要跟着正在播的那首走
        paintNow()
        adapter.currentPlaying = playback.isPlaying()
        // 正在收听这一单就是队列本身。自动下一首只换了播的那首、没换队列，
        // 这时重绑会把人滚回顶部，所以在「队列真的变了」时才重画。
        // 比内容不比条数：碰巧都是 28 首的两份歌单，条数比不出来
        if (dest == Dest.QUEUE && isShowing && !sameQueue()) {
            render(playback.queueSnapshot())
            return
        }
        // 刚播的这首可能刚被认出来是合集（view 回来后才知道），那一行的
        // 「合集 N 首」角标要立刻补上。不重画整页，只改那一行。
        if (isShowing) markAlbumBadge(playback.currentTrack()?.bvid.orEmpty())
    }

    /**
     * 给「刚确认是合集」的那一行补上角标。
     *
     * 只重画这一行：整表重绑会把焦点洗掉，用户正在看的那一屏会跳。
     */
    private fun markAlbumBadge(bvid: String) {
        if (bvid.isEmpty()) return
        val pos = adapter.positionOf(bvid)
        if (pos < 0) return
        val album = CollectionApi.peekOrDisk(bvid) ?: return
        if (!album.isCollection) return
        adapter.markAlbum(pos, album.parts.size)
    }

    /**
     * 把「已知是合集」的那几条补上 P 数。
     *
     * 只为播过的合集补：接口给的 P 数（最新发布、历史）在映射时就填好了，
     * 音乐区和综合榜不给，播过一次之后缓存里就有了——这样不用为了角标
     * 在首屏打三十次 view。
     *
     * 缓存读内存优先，内存没有再翻磁盘（冷启动后磁盘上还有 24 小时内的合集表）。
     */
    private fun enrich(list: List<Track>): List<Track> = list.map { t ->
        if (t.pages > 1) {
            t
        } else {
            val album = CollectionApi.peekOrDisk(t.bvid) ?: return@map t
            if (!album.isCollection) t else t.copy(pages = album.parts.size, album = album.title)
        }
    }

    private fun sameQueue(): Boolean {
        val now = playback.queueSnapshot()
        val shown = adapter.snapshot
        if (now.size != shown.size) return false
        // 按 bvid 比而不是 cid：搜索来源的 cid 全是 0，那样比「队列有没有变」
        // 会永远得到「没变」，自动下一首时队列不重画
        return now.indices.all { now[it].bvid == shown[it].bvid }
    }

    fun onProgress(positionMs: Long, durationMs: Long, playing: Boolean) {
        val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        barFill.scaleX = f
        barPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // 列表里那一行的小频谱跟着暂停停手。赋值有相等判断，每 500ms 调一次不会重画
        adapter.currentPlaying = playing
        paintBarPane(positionMs)
    }

    /**
     * 底栏切换格：一套状态，三档，整个文字块都由这里画。
     *
     * 三档都让切换格常驻——它既是「歌名 ↓」的落点，也是换档的唯一入口。
     * 沉浸档不把它 GONE 掉，而是把它自己撑到两格高：歌名与歌手收起，
     * 大频谱就长在切换格里，柱子从整块底边往上，两格都占满。
     * 于是「谁能切档」和「焦点在哪」永远一致，不存在进得去出不来的死路。
     *
     * 只在这句变了的时候改 `text`。TextView 的跑马灯在文本被重新赋值时会从头再滚一遍，
     * 每 500ms 赋一次等于永远停在第一个字上。
     */
    private fun paintBarPane(posMs: Long) {
        val pane = effectiveBarPane()
        val hasTrack = playback.hasTrack()
        // 没有在播的歌就别把歌名收起来：空底栏摆一条频谱是在演
        val immersive = pane == BarPane.IMMERSIVE && hasTrack
        applyBarStructure(immersive)

        barBigSpectrum.visibility = if (immersive) View.VISIBLE else View.GONE
        barBigSpectrum.playing = playback.isPlaying()
        barSpectrum.playing = playback.isPlaying()

        when {
            immersive -> {
                barLyric.visibility = View.GONE
                barSpectrum.visibility = View.GONE
            }
            pane == BarPane.SPECTRUM || barLines.isEmpty() -> {
                barLyric.visibility = View.GONE
                barSpectrum.visibility = if (hasTrack) View.VISIBLE else View.GONE
            }
            else -> {
                barSpectrum.visibility = View.GONE
                barLyric.visibility = View.VISIBLE
                var at = 0
                for (i in barLines.indices.reversed()) {
                    if (barLines[i].startMs <= posMs) {
                        at = i
                        break
                    }
                }
                if (at != barLineAt) {
                    barLineAt = at
                    barLyric.text = barLines[at].text
                }
            }
        }
    }

    /** 上一次铺好的结构。进度每 500ms 来一次，只有进出沉浸档才需要动布局参数 */
    private var appliedImmersive: Boolean? = null

    /**
     * 铺结构：切换格永远在，只有沉浸档把它撑到两格高、去掉给歌手名留的缩进。
     * 文字块高度常驻不变（常态 = 歌名行 + 间距 + 歌手行，沉浸档 = 撑高的那一格），
     * 底栏的垂直居中因此不挪，两格也都被柱子占满。
     */
    private fun applyBarStructure(immersive: Boolean) {
        if (appliedImmersive == immersive) return
        appliedImmersive = immersive
        val density = activity.resources.displayMetrics.density

        barTitle.visibility = if (immersive) View.GONE else View.VISIBLE
        barArtist.visibility = if (immersive) View.GONE else View.VISIBLE

        val rowLp = barArtistRow.layoutParams as LinearLayout.LayoutParams
        rowLp.height = if (immersive) {
            barTextH.takeIf { it > 0 } ?: ((BAR_TITLE_DP + BAR_ROW_H_DP + 3) * density).toInt()
        } else {
            (BAR_ROW_H_DP * density).toInt()
        }
        rowLp.topMargin = if (immersive) 0 else (3 * density).toInt()
        barArtistRow.layoutParams = rowLp

        val paneLp = barPaneBox.layoutParams as LinearLayout.LayoutParams
        paneLp.marginStart = if (immersive) 0 else (24 * density).toInt()
        barPaneBox.layoutParams = paneLp

        // 沉浸档歌名没了，横向焦点链要跳过整个文字块，纵向才进切换格：
        //   常态    封面 → 歌名 → 上一首；歌名 ↓ 切换格，切换格 ↑ 回歌名
        //   沉浸    封面 → 上一首；      封面 ↓ 切换格，切换格 ↑ 回封面
        // 少了这两条，沉浸档的切换格就成了「进得去出不来」的死路：
        // 它上面左右键被换档占着，歌名又是 GONE，requestFocus 会失败。
        barCoverBox.nextFocusRightId =
            if (immersive) R.id.home_bar_prev else R.id.home_bar_title
        barPrev.nextFocusLeftId =
            if (immersive) R.id.home_bar_cover_box else R.id.home_bar_title
        // 切档会改宽度，小频谱和大频谱都要按新坐标重算
        bar.post { sizeBarSpectrum() }
    }

    /**
     * 把焦点交给底栏那段「正在收听」。
     *
     * 从播放页退回来时落在这儿：眼睛刚离开整屏播放，落点该是「还在播的那首」的封面，
     * 而不是三十行列表里的某一行。**只落焦点，不改档**——停在上次选的那一档，
     * 不再像 0.29.6 那样一回来就强制展开大频谱。
     */
    fun focusNowPlaying() {
        bar.post {
            when {
                playback.hasTrack() && barCoverBox.isShown -> barCoverBox.requestFocus()
                rowCount() > 0 -> focusList()
                else -> focusNav()
            }
        }
    }

    /**
     * 拉当前这首的歌词给底栏用。
     *
     * 和播放页那一份各管各的：那边整屏歌词要排版、要居中、要滚，这里只要「现在是哪一句」。
     * 共用一份会逼着两个页面互相同步状态，而它们的生命周期本来就不一样。
     */
    private fun loadBarLyrics(track: Track) {
        if (track.bvid.isEmpty()) return
        val key = if (track.cid > 0) track.cid else -track.bvid.hashCode().toLong()
        if (key == barLyricKey) return
        barLyricKey = key
        barLyricJob?.cancel()
        barLines = emptyList()
        barLineAt = -2
        // 换歌不清档：用户显式选的那一档是偏好，不该被下一首歌打回自动档。
        // 自动档（barPane == null）本来就按「这一首有没有词」自己落档，
        // 所以「无词默认频谱」仍然成立，只是不再覆盖用户的选择。
        barLyric.text = ""
        paintPaneHint()
        paintBarPane(playback.snapshot().positionMs)
        // 匿名拿不到字幕。这里不写提示：底栏只有一行 16dp 的位置，
        // 写「登录后有歌词」会把歌手名挤掉，而播放页右栏已经说过一次了
        if (!LoginApi.isLoggedIn()) return

        barLyricJob = scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { LyricsApi.load(track, log) }.getOrNull()
            }
            if (barLyricKey != key) return@launch
            barLines = (got as? LyricsApi.Outcome.Ok)?.lines.orEmpty()
            barLineAt = -2
            paintPaneHint()
            paintBarPane(playback.snapshot().positionMs)
        }
    }

    /**
     * 切换格：歌词 → 频谱 → 沉浸 → 歌词，三档循环，按一下换一档并写盘。
     * 没有歌词时「歌词」这档不在循环里，变成 频谱 ⇄ 沉浸——和播放页
     * 右栏「没词时跳过歌词档」同一个规矩。
     */
    private fun cycleBarPane() {
        if (playback.currentTrack() == null) {
            hint(activity.getString(R.string.home_bar_empty))
            return
        }
        val cur = effectiveBarPane()
        val next = when {
            !Settings.lyricsOn(activity) || barLines.isEmpty() ->
                // 没词（或设置里关掉了歌词）：只能在两档频谱之间来回，别翻进一屏空白
                if (cur == BarPane.SPECTRUM) BarPane.IMMERSIVE else BarPane.SPECTRUM
            cur == BarPane.LYRIC -> BarPane.SPECTRUM
            cur == BarPane.SPECTRUM -> BarPane.IMMERSIVE
            else -> BarPane.LYRIC
        }
        barPane = next
        Settings.setBarPane(activity, next.ordinal)
        paintPaneHint()
        paintBarPane(playback.snapshot().positionMs)
        Diag.confirm("底栏 → ${paneLabel(next)}")
    }

    private fun paneLabel(pane: BarPane): String = activity.getString(
        when (pane) {
            BarPane.LYRIC -> R.string.bar_pane_lyrics
            BarPane.SPECTRUM -> R.string.bar_pane_spectrum
            BarPane.IMMERSIVE -> R.string.bar_pane_immersive
        }
    )

    /** contentDescription 只报当前这一档，读屏用；屏幕上不写字 */
    private fun paintPaneHint() {
        barPaneBox.contentDescription = paneLabel(effectiveBarPane())
    }

    /**
     * 频谱右端一直伸到「上一首」前，两端各留 12dp，整条在这一格里居中。
     *
     * 写死 dp 值不行：歌手名长到 180dp 时这一格会跟着变窄，柱子会压到按钮上。
     * 量真实坐标还能让以后改底栏边距时不必回来改一个数。只在布局完成后跑；
     * 宽度没变就不重新设 layoutParams，免得自己和自己来回触发布局。
     */
    private fun sizeBarSpectrum() {
        val from = IntArray(2)
        val to = IntArray(2)
        barPaneBox.getLocationInWindow(from)
        barPrev.getLocationInWindow(to)
        val gap = (12 * activity.resources.displayMetrics.density + 0.5f).toInt()
        val width = minOf(to[0] - from[0] - gap * 2, barPaneBox.width - gap * 2)
        if (width <= 0) return
        val lp = barSpectrum.layoutParams
        if (lp.width != width) {
            lp.width = width
            barSpectrum.layoutParams = lp
        }
    }

    /** 设置里改过频谱样式或颜色：底栏与列表行都重取一次外观 */
    fun refreshSpectrum() {
        barSpectrum.applySettings()
        barBigSpectrum.applySettings()
        adapter.notifyDataSetChanged()
    }

    /**
     * 设置里改过歌词的颜色 / 字体 / 大小：底栏那一行跟着换。
     *
     * 字体这里只认字重，不换字族：底栏那一行只有一行高、还带着跑马灯，
     * 宋体的字面比黑体高一截，换过去会顶到上面的歌名。播放页那一屏才换字族。
     *
     * 基准 16sp：12sp 在 3 米外读不出来（这一行还带着跑马灯，滚动中的字更糊）。
     * **只放大、不缩小**（下限 1.0）：设置里的「小」是给播放页那种整段歌词用的，
     * 底栏只有一行、本来就该一眼看清，跟着缩到 0.9 等于把字又调回去了。
     * 上限 1.3——行高 24dp 刚好装得下 16×1.3，再大就压到上面的歌名。
     */
    private fun paintLyricStyle() {
        barLyric.setTextColor(Settings.lyricsColor(activity))
        barLyric.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            16f * Settings.lyricsScale(activity).coerceIn(1f, 1.3f),
        )
    }

    /** 歌词设置改完：重取外观，并按新的开关决定这一格该显示歌词还是频谱 */
    fun refreshLyrics() {
        paintLyricStyle()
        paintBarPane(playback.snapshot().positionMs)
    }

    fun paintBar() {
        val track = playback.currentTrack()
        paintMode(playback.mode)

        if (track == null) {
            // 没有在播的歌：文字块照旧摆着，只是三档内容全收起来。
            // 不动档位——空底栏不该把用户选的偏好冲掉
            barTitle.text = activity.getString(R.string.home_bar_empty)
            barArtist.text = ""
            barLyric.text = ""
            paintPaneHint()
            paintBarPane(0)
            barCover.setImageDrawable(null)
            barPlay.setImageResource(R.drawable.ic_play)
            barFav.visibility = View.INVISIBLE
            barFill.scaleX = 0f
            // 没有歌的时候这四颗键按下去什么都不会发生，压暗比留着更像真的
            for (v in listOf(barPlay, barPrev, barNext, barMode)) v.alpha = 0.3f
            return
        }

        barTitle.text = track.title
        barArtist.text = track.owner
        loadBarLyrics(track)
        val playing = playback.isPlaying()
        barPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        barFav.visibility = View.VISIBLE
        for (v in listOf(barPlay, barPrev, barNext, barMode)) v.alpha = 1f
        if (track.cover.isNotEmpty()) {
            CoverLoader.load(barCover, FeedApi.reCrop(track.cover, 150, 84), 150, 84)
        } else {
            barCover.setImageDrawable(null)
        }
        paintBarFavorite(FavState.isFavorited(activity, track.bvid))
        paintBarPane(playback.snapshot().positionMs)
    }

    /**
     * 底栏那颗模式键：三张图，不写字。
     *
     * 播放页在 0.20.0 就把「顺序 / 单曲 / 随机」三个字换成了图标，首页这一处
     * 还是 12sp 的灰字——同一个状态在相邻两个页面上长得不一样，是这一版要消掉的
     * 最后一处。字只留在 contentDescription 里，给读屏用。
     */
    private fun paintMode(mode: QueueMode) {
        val (icon, label) = when (mode) {
            QueueMode.ORDER -> R.drawable.ic_mode_order to R.string.mode_order
            QueueMode.ONE -> R.drawable.ic_mode_one to R.string.mode_one
            QueueMode.SHUFFLE -> R.drawable.ic_mode_shuffle to R.string.mode_shuffle
        }
        barMode.setImageResource(icon)
        barMode.contentDescription = activity.getString(label)
    }

    fun refreshFavorite() = paintBar()

    private fun paintBarFavorite(on: Boolean) {
        barFav.setImageResource(if (on) R.drawable.ic_heart_on else R.drawable.ic_heart)
        barFav.imageTintList = if (on) {
            null
        } else {
            activity.resources.getColorStateList(R.color.text_secondary, activity.theme)
        }
    }

    /**
     * 底栏的三颗动作键。
     *
     * 0.21 之前它们只是画上去的图标：没有 clickable，MainActivity 里也从没引用过。
     * 现在每一颗都真的动手——上一首 / 播放暂停 / 下一首。底栏因此从「展示条」
     * 变成「控制条」，代价是这一排多了五个焦点位，好处是不用为了暂停一下
     * 先跳进播放页。
     */
    private fun stepQueue(step: Int) {
        if (!playback.hasTrack()) {
            hint(activity.getString(R.string.home_bar_empty))
            return
        }
        if (step > 0) playback.next() else playback.prev()
        Diag.confirm(if (step > 0) "底栏 下一首" else "底栏 上一首")
    }

    /** 播放 / 暂停。停在这颗键上就是「我要控制」，不再顺带跳进播放页 */
    private fun toggleFromBar() {
        if (!playback.hasTrack()) {
            hint(activity.getString(R.string.home_bar_empty))
            return
        }
        playback.toggle()
        Diag.confirm(if (playback.isPlaying()) "底栏 播放" else "底栏 暂停")
    }

    /** 切换播放模式。图标换了还得有一句话——三种图标都是 22dp 的小图，
     *  三米外能看出「变了」，看不出「变成什么」 */
    private fun cycleModeFromBar() {
        playback.cycleMode()
        paintMode(playback.mode)
        hint(activity.getString(R.string.home_mode_now, playback.mode.label))
        Diag.confirm("底栏 模式 ${playback.mode.label}")
    }

    // ------------------------------------------------------------ 焦点

    fun focusNav() {
        navViews[dest]?.requestFocus()
    }

    /**
     * 浮层关闭后把焦点交回侧栏。浮层里的控件随浮层一起 GONE，不交还就是焦点悬空。
     *
     * [target] 指定落点。从「搜索」这类**不换页**的浮层退出来，光标该回到那个入口本身；
     * 不指定就交回上一次选过的目的地——而搜索是 action 项，从不成为 dest，
     * 于是退出搜索时光标会落在「上一次看的那一页」上（实测落过「音乐」和「播放历史」），
     * 看起来像是被送错了页。返回键只退一层，退完该还在原地。
     */
    fun returnFocus(target: Dest? = null) {
        navBox.post { (target?.let { navViews[it] } ?: navViews[dest])?.requestFocus() }
    }

    /** 当前列表上有多少行。曲目表和人物表不是同一个适配器，行数也不在一处 */
    private fun rowCount(): Int = if (dest == Dest.REC_UP) upAdapter.itemCount else adapter.itemCount

    private fun focusList() {
        if (dest == Dest.REC_UP) {
            // 人物表没有「正在播的那一首」，永远从第一行开始
            if (upAdapter.itemCount == 0) return
            listView.post {
                (listView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                listView.post {
                    listView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }
            return
        }
        val playing = playback.currentTrack()?.cid ?: 0L
        // 目标按 cid 找，不按下标：换目的地之后「第几首」会变，cid 不会。
        // 也不按 bvid：一张合集里 100 首共用同一个 bvid，找到的永远是第一首
        val target = adapter.positionOf(playing).takeIf { it >= 0 } ?: 0
        if (target < 0 || adapter.trackCount == 0) return
        listView.post {
            (listView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target, 0)
            listView.post {
                val holder = listView.findViewHolderForAdapterPosition(target)
                    ?: listView.getChildAt(0)?.let { listView.findContainingViewHolder(it) }
                holder?.itemView?.requestFocus()
            }
        }
    }

    /** 光标在不在侧栏里。返回键靠它决定「回到侧栏」还是「退出应用」 */
    private fun inNav(): Boolean = inside(navBox)

    private fun inList(): Boolean = inside(listView)

    private fun inBar(): Boolean = inside(bar)

    private fun inside(container: View): Boolean {
        var view: View? = activity.currentFocus
        while (view != null) {
            if (view === container) return true
            view = view.parent as? View
        }
        return false
    }

    /**
     * 把光标放到列表最后一行。
     *
     * 从底栏按上键时，眼睛要看到的是「接着往下还有哪些歌」，所以落在末尾而不是
     * 跳到在播的那一首。回到在播那一首是「返回键」的事。
     */
    private fun focusLastRow(): Boolean {
        for (i in listView.childCount - 1 downTo 0) {
            val child = listView.getChildAt(i)
            if (child != null && child.isFocusable) {
                child.requestFocus()
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------ 显隐 / 按键

    fun show(
        focusPlaying: Boolean = false,
        focusSidebar: Boolean = true,
        focusOnNowPlaying: Boolean = false,
    ) {
        root.visibility = View.VISIBLE
        if (rowCount() == 0) {
            select(dest, focusList = false)
        } else if (dest == Dest.QUEUE) {
            // 从播放页回来时队列可能已经变了（自动下一首）
            render(playback.queueSnapshot())
        }
        paintBar()

        when {
            // 从播放页退回来：只把焦点落在「还在播的那首」的封面上，档位保持不动
            focusOnNowPlaying -> focusNowPlaying()
            focusPlaying && rowCount() > 0 -> focusList()
            focusSidebar -> navBox.post { focusNav() }
        }
    }

    fun hide() {
        root.visibility = View.GONE
    }

    /** 返回 true 表示这个键已经被首页吃掉 */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        return when (code) {
        KeyEvent.KEYCODE_BACK -> {
            // 在列表里按返回先回侧栏——这一页没有任何「上一步」，
            // 直接在第一个返回键上退出应用，会让人再也不敢按
            if (inNav()) {
                false
            } else {
                focusNav()
                true
            }
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
            toggleFavorite()
            true
        }
        // 行内横着走：行根 ⇄ 歌手列。
        //
        // 这两格是「同一行里的两列」，不写在 XML 的 nextFocusRight 里：
        // item 上只要设了 nextFocus*，Android 的 FocusFinder 就不用几何搜索找下一行了，
        // RecyclerView 的纵向滚动整个停摆（实测一屏六行按不动）。
        // 行内的关系由这一行自己管，纵向仍旧留给 RecyclerView。
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            when {
                // 切换格上左右也是「换一档」。只认第一下：按住不放时系统每 50ms
                // 补发一次 DOWN，照单全收会一口气轮好几圈
                activity.currentFocus === barPaneBox -> {
                    if (event.repeatCount == 0) cycleBarPane()
                    true
                }
                inList() -> moveWithinRow(code)
                else -> false
            }
        }
        // 侧栏 ⇄ 列表 ⇄ 底栏这三段的交接都得自己接：
        // 交给 FocusFinder，从底栏按上会绕过整屏歌直接回侧栏；从列表顶端按上
        // 会跳过「播放全部」；从「播放全部」按下又会落到只有收藏页才存在的按钮上。
        // 侧栏内部的上下仍归侧栏自己——把它也接管掉，人就再也换不了目的地。
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            val lastNav = lastNavView()
            when {
                // 品牌位那颗搜索钮 ↓：回到侧栏当前这一项。它上面没有别的东西，
                // 不接这一下 FocusFinder 会按几何位置挑，挑中的是屏幕另一头
                activity.currentFocus === searchBtn -> {
                    focusNav()
                    true
                }
                // 常态：歌名 ↓ 进切换格。沉浸档歌名是 GONE，改由封面 ↓ 进——
                // 两档下都是「↓ 进切换格」，一格都不多走
                activity.currentFocus === barTitle -> barPaneBox.requestFocus()
                activity.currentFocus === barCoverBox &&
                    effectiveBarPane() == BarPane.IMMERSIVE -> barPaneBox.requestFocus()
                // 筛选词 ↓：落到「播放全部」那一排。下面就是那一排按钮，不越过它们进列表
                inChips(activity.currentFocus) -> playAll.requestFocus()
                // 「换一批」往下：直接进列表。它只出现在推荐音乐，那一页没有工具条
                activity.currentFocus === refresh -> focusFirstRow()
                // 「播放全部」往下：收藏页先过一下新建/删除，其它单直接进列表
                activity.currentFocus === playAll ->
                    if (favTools.visibility == View.VISIBLE) {
                        favNew.requestFocus()
                    } else {
                        focusFirstRow()
                    }
                inFavTools(activity.currentFocus) -> focusFirstRow()
                inList() ->
                    // 按住不放 = 我要的是这一串的尽头。列表三十行，一行一行挪到底栏
                    // 要按三十多次——按住不到一秒直接落到底栏。
                    // 单击仍是一行一行走：这条路不能改，改了就再也翻不了列表。
                    if (heldDown(event)) {
                        val lm = listView.layoutManager as? ScrollableLayoutManager
                        focusBar(BarFrom.LIST, lm?.focusedPosition(activity.currentFocus) ?: -1)
                    } else {
                        moveVertical(1)
                    }
                // 底栏里没有「更下面」的控件。吃掉这一下，免得 FocusFinder
                // 把光标从播放键拽到下面那条长条切换格上
                inBar() -> true
                // 侧栏走到最后一项（账户）再按一下：落到底栏。侧栏和底栏本来就
                // 是屏幕最左、最底的两条边，从这儿过去是几何上最近的一步
                lastNav != null && activity.currentFocus === lastNav -> {
                    Diag.confirm("侧栏→底栏")
                    focusBar(BarFrom.SIDEBAR)
                }
                else -> false
            }
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
            when {
                // 切换格往上：常态回歌名，沉浸档回封面（歌名那格已经收起）。
                // 再按一次才离开底栏——一次返回只退一层
                activity.currentFocus === barPaneBox ->
                    if (effectiveBarPane() == BarPane.IMMERSIVE) {
                        barCoverBox.requestFocus()
                    } else {
                        barTitle.requestFocus()
                    }
                // 从底栏往上按原路回去：长按下来的人回原来那一行，
                // 从侧栏下来的人回侧栏——两拨人不该被塞进同一个落点
                inBar() -> leaveBar()
                inFavTools(activity.currentFocus) -> playAll.requestFocus()
                // 筛选词往上：回到侧栏。它们和「播放全部」同属这一页的工具行
                inChips(activity.currentFocus) -> {
                    focusNav()
                    true
                }
                // 侧栏第一项往上：离开侧栏，落到品牌位后面那颗搜索钮。
                // 侧栏上面没有别的东西，不接这一下按键会卡在原地
                activity.currentFocus === firstNavView() && searchBtn.isShown -> {
                    searchBtn.requestFocus()
                    true
                }
                // 「播放全部」再往上才是侧栏。这一格跳过去就直接换目的地了
                activity.currentFocus === playAll || activity.currentFocus === refresh -> {
                    focusNav()
                    true
                }
                inList() -> moveVertical(-1)
                else -> false
            }
        }
        else -> false
        }
    }

    /**
     * 列表里上下翻页。
     *
     * 这件事不能交给系统的焦点搜索，两个原因：
     *
     * 一、RecyclerView 只创建可见范围内的行。下一行还没被创建出来时，
     * 焦点搜索扑空，系统虽然会补出那一行（`onFocusSearchFailed` 里
     * LinearLayoutManager 会 `fill()`），补出来的行却排在列表底边外面，
     * 焦点移过去了用户看不见——光标像是凭空消失了。
     *
     * 二、更要命的是底栏在列表正下方。焦点搜索在整棵树里找「下面」，
     * 底栏比列表里补出来的那一行更近，于是焦点直接跳到底栏，
     * 中间那一屏歌被整个跳过。
     *
     * 所以这里自己来：算出目标行 → 滚过去 → 把焦点钉上去。
     *
     * @param step 往下 1，往上 -1
     * @return true 表示这个键被列表吃掉了
     */
    private fun moveVertical(step: Int): Boolean {
        val lm = listView.layoutManager as? ScrollableLayoutManager ?: return false
        val from = lm.focusedPosition(activity.currentFocus)
        if (from == RecyclerView.NO_POSITION) return false

        val to = from + step
        // 已经到头：往下交棒给底栏；往上先回到按钮那一排，再往上才是侧栏。
        // 不能直接跳侧栏——那一格跳过去就换目的地了，用户只是想回到顶上看标题。
        // 落在哪一颗跟这一页的落点一致：推荐音乐是「换一批」，别处是「播放全部」。
        if (to < 0 || to >= rowCount()) {
            return if (step > 0) {
                focusBar(BarFrom.LIST, from)
            } else {
                (if (dest == Dest.REC_MUSIC) refresh else playAll).requestFocus()
            }
        }
        return lm.moveTo(listView, from, to)
    }

    /** 「播放全部」和收藏夹工具条上的两个按钮不在列表里，得单独接 */
    private fun inFavTools(v: View?): Boolean = v === favNew || v === favDel

    /** 焦点在不在那排筛选词上。它们是这一页的工具行，不归列表也不归侧栏 */
    private fun inChips(v: View?): Boolean = v != null && chipViews.any { it === v }

    /** 回到列表第一行。播放全部、收藏夹工具条往下都落在这一行 */
    private fun focusFirstRow(): Boolean {
        if (rowCount() == 0) return false
        (listView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        listView.post {
            listView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        return true
    }

    /** 行内横向移动：行根 → 歌手列 → 回行根 */
    private fun moveWithinRow(code: Int): Boolean {
        val holder = focusedRowHolder() ?: return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (holder.whichColumn() == 0) holder.moveHorizontal(View.FOCUS_RIGHT) else false
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (holder.whichColumn() == 1) holder.moveHorizontal(View.FOCUS_LEFT) else false
            else -> false
        }
    }

    /** 焦点所在的那一行的 ViewHolder。焦点不在列表里就是 null */
    private fun focusedRowHolder(): TrackListAdapter.SongHolder? {
        var view: View? = activity.currentFocus
        while (view != null) {
            val holder = listView.findContainingViewHolder(view)
            if (holder != null) return holder as? TrackListAdapter.SongHolder
            view = view.parent as? View
        }
        return null
    }

    /**
     * 去底栏，并记住回来时落哪儿。
     *
     * 落点是播放键，不是歌名。这一排五颗键里播放键是视觉重心，长按到底栏
     * 时焦点要是落在歌名上（一个字的底下垫块玻璃），那一排的方盒和亮边
     * 一颗都不亮——用户会以为还停在列表里。落在最亮的那颗上，
     * 「到了」这件事不用解释。
     *
     * @param from 从哪一头来的
     * @param row  从列表来的时候，光标原来停在第几行（-1 表示不知道）
     */
    private fun focusBar(from: BarFrom, row: Int = -1): Boolean {
        barFrom = from
        barReturnRow = row
        return barPlay.requestFocus()
    }

    /** 从底栏按上：按原路回去 */
    private fun leaveBar(): Boolean = when (barFrom) {
        BarFrom.SIDEBAR -> {
            focusNav()
            true
        }
        // 记住的那一行还在（列表没被换掉）就钉回去；记不住就退到最后一行
        BarFrom.LIST -> if (barReturnRow >= 0 && focusRow(barReturnRow)) true else focusLastRow()
    }

    /** 把光标放到指定的一行。那一行已经看得见就直接给焦点，看不见才滚过去 */
    private fun focusRow(pos: Int): Boolean {
        if (pos < 0 || pos >= rowCount()) return false
        val existing = listView.findViewHolderForAdapterPosition(pos)?.itemView
        if (existing != null && existing.requestFocus()) return true
        (listView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        listView.post {
            listView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
        return true
    }

    /** 侧栏最后一项。它下面就是底栏，这一格是「侧栏→底栏」的入口 */
    private fun lastNavView(): View? = navViews.values.lastOrNull()

    /** 侧栏第一项。它上面是品牌位那颗搜索钮 */
    private fun firstNavView(): View? = navViews.values.firstOrNull()

    /**
     * 焦点落到品牌位那颗搜索钮。
     *
     * 搜索页退出时用它。以前搜索是侧栏里的一项，退出来交回 navViews[SEARCH] 就行；
     * 现在它不在侧栏里了，还按老办法找会落空——焦点悬空之后遥控器就「失灵」了。
     */
    fun focusSearch() {
        searchBtn.post { searchBtn.requestFocus() }
    }

    /**
     * 这个下键是不是「按住」。
     *
     * 两个判据取其一：系统在长按判定通过时会给事件打 FLAG_LONG_PRESS，
     * 之后才是连发；有些遥控器/输入法只连发不打标，所以再看重复次数。
     * 只认重复次数的话，模拟器和一部分电视上这条路根本走不通。
     */
    private fun heldDown(event: KeyEvent): Boolean =
        event.repeatCount >= HOLD_TO_BAR ||
            (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0

    /**
     * @param forced 指定收藏哪一首。底栏那颗心传的是「正在播的那首」——光标虽然
     *               停在心上，但这一格指的是那首歌；MENU 键不传，按光标位置判断。
     */
    private fun toggleFavorite(forced: Track? = null) {
        // 光标停在列表某一行时，MENU 收藏的是那一行——用户盯着的就是它。
        // 光标不在列表里（在侧栏或底栏）时，收藏正在播的那首
        val track = forced
            ?: (if (inList()) focusedTrackOfRow ?: playback.currentTrack() else playback.currentTrack())
        if (track == null) {
            hint(activity.getString(R.string.home_bar_empty))
            return
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { FavState.toggle(activity, track, log) }
                    .onFailure { log("收藏失败 ${it.message}") }
            }
            outcome.onSuccess { added ->
                // 收藏的是列表里某一行时，底栏那颗心是另一首歌的状态，不能跟着翻
                if (track.bvid == playback.currentTrack()?.bvid) paintBarFavorite(added)
                hint(activity.getString(if (added) R.string.fav_added else R.string.fav_removed))
                Diag.confirm(if (added) "首页收藏" else "首页取消收藏")
            }.onFailure {
                hint(activity.getString(R.string.fav_failed))
            }
        }
    }

    /** 收藏/历史这类需要登录的来源，登录态可能刚变化，切回来时要重新拉 */
    fun invalidate() {
        cache.remove(Dest.FAV)
        cache.remove(Dest.HIST)
    }

    private fun loggedIn(): Boolean = LoginApi.isLoggedIn()

    private companion object {
        /**
         * 按住下键几次重复之后直接落到底栏。
         *
         * 电视的按键重复是「首次 500ms，之后每 50ms 一次」，8 次约 0.9 秒。
         * 设成 3 的话连按三下都会被当成按住，正常翻页就废了。
         * 有 FLAG_LONG_PRESS 的机器不会走到这里——那一支在 [heldDown] 里先命中。
         */
        const val HOLD_TO_BAR = 8

        /**
         * 底栏「歌手 / 歌词 / 频谱」那一行的高度（dp）。
         *
         * 原来是 16dp，只够 12sp 的字——3 米外看不清歌词。跟着歌词抬到 16sp 之后
         * 这一行要 24dp 才装得下（还要容得下设置里「特大」档的 1.3 倍）。
         * layout 里写一遍、`applyBarStructure` 切档时又要写回来，所以留成常量：
         * 两处各写一个数字的话，切一次档就悄悄打回原值。
         */
        const val BAR_ROW_H_DP = 24

        /** 常态歌名行实测高度（17sp + 上下各 2dp padding），只在 barTextH 还没量到时兜底 */
        const val BAR_TITLE_DP = 27
    }
}
