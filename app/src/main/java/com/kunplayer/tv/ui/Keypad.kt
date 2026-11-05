package com.kunplayer.tv.ui

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.kunplayer.tv.R
import com.kunplayer.tv.diag.Diag

/**
 * 电视上的自绘键盘：九宫格（T9 那种排版）。
 *
 * 为什么从 QWERTY 改成九宫格：
 *   · QWERTY 是给两只手在桌面上敲的人设计的。遥控器只有方向键和确认，
 *     一行十个键意味着按一次右移一格，「zhoujielun」要按四十来次，还得一路盯着光标。
 *   · 九宫格一行三个键，横向最多移两次，键也大到两米外看得清。同样一个词按键数
 *     只多两成，找键的时间少一大截——电视上的成本主要在「找」不在「按」。
 *
 * 为什么键上是字母而不是「2/3/4」：B 站搜索吃拼音原文，不认 T9 数字串。
 * 数字键得再配一张映射表，而遥控器上那套映射从来没人背得下来。键上写字母，按下去出字母。
 *
 * **一格三个字母怎么选**（0.27.0）：按下去弹出这一格的全部字母，光标落在第一个上，
 * 左右挑、确认落字。早先做的是「同键连按循环」——按 `abc` 出 a、再按出 b——
 * 那套只适合手机上的拇指，遥控器上没人能记住「刚才这格按到哪儿了」：
 * 换个字母要从头数，数错了就得删掉重来。弹出来摆在眼前，选哪个是看得见的。
 *
 * 布局参数全部用**权重**：同一个 [Keypad] 既铺在 400dp 的搜索左栏里，也铺在
 * 收藏夹浮层的框里。写死 dp 的话窄的会溢出、宽的会缩在中间一小块。
 */
class Keypad(
    private val container: LinearLayout,
    private val onText: (String) -> Unit,
    private val onSubmit: (String) -> Unit,
    /** 提交键上的字。搜索页是「搜索」，新建收藏夹是「确定」 */
    private val submitLabel: String = "搜索",
    /** 「搜索」右边那一颗的字与动作。搜索页给「清空」，收藏夹不给 */
    private val extraLabel: String? = null,
    private val onExtra: (() -> Unit)? = null,
) {

    private enum class Kind { CHAR, SPACE, BACK, GO, EXTRA }

    /** 一个键。[col] 是它在行内的格号（0 起），上下移动按格号就近对齐 */
    private class Key(
        val label: String,
        val kind: Kind,
        val col: Int,
    )

    private val rows = ArrayList<MutableList<TextView>>()
    private val buffer = StringBuilder()
    private val keyOf = HashMap<TextView, Key>()

    /** 弹出的字母卡（懒建，第一次按字母格才有）。铺在 host 里，盖住整块键盘 */
    private var overlay: FrameLayout? = null

    /** 卡片本体。存引用而不是从 overlay 里按下标取——下标这种东西改一次层级就错位 */
    private var pickCard: LinearLayout? = null

    /** 卡片上的字母键，左右移动就在这个表里挪 */
    private val picks = ArrayList<TextView>()

    /** 弹出卡片的是哪一格。选完字 / 取消都回它，光标不迷路 */
    private var pickAnchor: TextView? = null

    private var built = false

    val text: String get() = buffer.toString()

    /**
     * 九宫格。每行三格，按 a b c / d e f / … 的顺序每三个字母一格。
     * 字母分布跟手机拨号盘一样（abc=2 / def=3 / …），按老了不用重新学。
     */
    private val grid = listOf(
        listOf("abc", "def", "ghi"),
        listOf("jkl", "mno", "pqr"),
        listOf("stu", "vwx", "yz"),
    )

    /**
     * 键是一次 XML inflate，整块键盘铺下来要吃掉两百多毫秒。
     * 冷启动根本看不到键盘，所以推迟到第一次进搜索页再铺——这是把启动时间拉回来的一步。
     */
    fun ensureBuilt() {
        if (built) return
        built = true
        build()
    }

    private fun build() {
        val density = container.resources.displayMetrics.density
        // 52dp 是「两米外读得清 17sp 字母」和「四行塞进 540dp 屏」之间的那个数：
        // 键盘总高 = 4 × 52 + 间距 23 ≈ 231dp，加上输入回显与历史还留有富余
        val keyH = 52
        val gap = 5

        for (row in grid) {
            val line = line(gap)
            container.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val holders = ArrayList<TextView>()
            for ((i, letters) in row.withIndex()) {
                val view = View.inflate(container.context, R.layout.item_key, null) as TextView
                view.text = letters
                view.textSize = 17f
                view.letterSpacing = 0.12f
                view.layoutParams = cell(1f, keyH, gap, density)
                val key = Key(letters, Kind.CHAR, col = i)
                bindKey(view, key)
                keyOf[view] = key
                line.addView(view)
                holders += view
            }
            rows += holders
        }

        // 最后一排：空格 / 删除 / 搜索（+ 可选的第四颗）。
        // 不塞进字母行：塞进去就会有跨两格的宽键，按格号对齐时光标会拐弯
        val actionsLine = line(gap, topExtraDp = 3)
        container.addView(
            actionsLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val actions = listOf(
            Key("空格", Kind.SPACE, 0),
            Key("删除", Kind.BACK, 1),
            Key(submitLabel, Kind.GO, 2),
        ).toMutableList()
        if (extraLabel != null) actions += Key(extraLabel, Kind.EXTRA, 3)

        val holders = ArrayList<TextView>()
        for (key in actions) {
            val view = View.inflate(container.context, R.layout.item_key, null) as TextView
            view.text = key.label
            view.textSize = 15f
            view.layoutParams = cell(1f, keyH, gap, density)
            bindKey(view, key)
            keyOf[view] = key
            actionsLine.addView(view)
            holders += view
        }
        rows += holders
    }

    private fun line(gap: Int, topExtraDp: Int = 0): LinearLayout =
        LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ((gap + topExtraDp) * container.resources.displayMetrics.density).toInt() }
        }

    private fun cell(weight: Float, heightDp: Int, gap: Int, density: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, (heightDp * density).toInt(), weight).apply {
            marginStart = (gap * density / 2).toInt()
            marginEnd = (gap * density / 2).toInt()
        }

    private fun bindKey(view: TextView, key: Key) {
        view.setOnClickListener { press(view, key) }
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) Diag.enter("键盘")
            v.animate().cancel()
            v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(120)
                .start()
            (v as TextView).setTextColor(
                if (hasFocus) 0xFF000000.toInt()
                else container.resources.getColor(R.color.text_primary, null)
            )
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_key_focus else R.drawable.bg_key)
        }
    }

    private fun press(view: TextView, key: Key) {
        when (key.kind) {
            // 一格几个字母：弹出来让人挑，不猜。
            // 只有一个字母的格（yz 那行的末格）没得挑，直接落字，省一次确认
            Kind.CHAR -> {
                if (key.label.length <= 1) insertLetter(key.label[0])
                else openPicker(view, key)
            }
            Kind.SPACE -> {
                // 首尾空格和连打空格都没有意义，搜索词里只留单个分隔
                if (buffer.isEmpty() || buffer.last() == ' ') return
                buffer.append(' ')
                onText(text)
            }
            Kind.BACK -> {
                if (buffer.isEmpty()) return
                buffer.setLength(buffer.length - 1)
                onText(text)
            }
            // 搜一次要按十几次键，是这一页最贵的一次操作，值得单独记一条
            Kind.GO -> {
                Diag.confirm("搜索 ${if (text.isBlank()) "空" else text}")
                onSubmit(text)
            }
            Kind.EXTRA -> onExtra?.invoke()
        }
    }

    // ------------------------------------------------------------ 字母选择卡

    /**
     * 把这一格的字母摆出来给用户挑。
     *
     * 卡片沿用键自己的 `item_key` 与聚焦底板（白底黑字），只是放大一号：
     * 键盘上按下某格、再在同一套视觉里挑字，中间不换语言。
     */
    private fun openPicker(anchor: TextView, key: Key) {
        val host = container.parent as? FrameLayout ?: run {
            // 布局没包叠层（外面临着改坏了）：退化成直接落第一个字母，
            // 难用，但至少不会按了没反应
            insertLetter(key.label[0])
            return
        }
        val density = container.resources.displayMetrics.density
        val box = overlay ?: buildOverlay(host).also { overlay = it }
        val card = pickCard ?: return

        card.removeAllViews()
        picks.clear()
        pickAnchor = anchor

        for (ch in key.label) {
            val v = View.inflate(container.context, R.layout.item_key, null) as TextView
            v.text = ch.toString()
            v.textSize = 27f
            v.letterSpacing = 0f
            // 68×50dp 是键盘格；这里 76×58dp，比底下的键大一圈，一眼看出是「挑字那一层」
            v.layoutParams = LinearLayout.LayoutParams((76 * density).toInt(), (58 * density).toInt()).apply {
                marginStart = (5 * density).toInt()
                marginEnd = (5 * density).toInt()
            }
            paintPick(v, false)
            v.setOnClickListener { takeLetter(ch) }
            v.setOnFocusChangeListener { view, hasFocus -> paintPick(view as TextView, hasFocus) }
            picks += v
            card.addView(v)
        }

        box.visibility = View.VISIBLE
        box.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        box.animate().alpha(1f).setDuration(110).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        box.post {
            picks.firstOrNull()?.requestFocus()
        }
    }

    /** 叠在键盘上的那一层：遮底 + 居中的卡片。只建一次，之后反复用 */
    private fun buildOverlay(host: FrameLayout): FrameLayout {
        val density = container.resources.displayMetrics.density
        val box = FrameLayout(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
            isClickable = true
            // 压暗键盘但不全黑：还要看得见自己刚才按的是哪一格。
            // 0xC4（77%）那版在真机上把键都压没了，看着像键盘坏了；0xB0 刚好
            setBackgroundColor(0xB0121316.toInt())
        }
        val card = LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_picker)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            elevation = 14 * density
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        box.addView(card)
        pickCard = card
        host.addView(box)
        return box
    }

    private fun paintPick(v: TextView, hasFocus: Boolean) {
        v.setBackgroundResource(if (hasFocus) R.drawable.bg_key_focus else R.drawable.bg_key)
        v.setTextColor(
            if (hasFocus) 0xFF000000.toInt()
            else container.resources.getColor(R.color.text_primary, null)
        )
    }

    /** 挑定一个字母：落字、收起卡片、光标回原来那一格 */
    private fun takeLetter(ch: Char) {
        val anchor = pickAnchor
        anchor?.requestFocus()
        hidePicker()
        insertLetter(ch)
    }

    private fun insertLetter(ch: Char) {
        if (buffer.length >= 40) return
        buffer.append(ch)
        onText(text)
    }

    /**
     * 收起卡片，什么都不落。
     * 返回 true 表示「这次返回键被卡片吃了」——上层不该因此关掉整个搜索页。
     */
    fun dismissPicker(): Boolean {
        if (overlay?.visibility != View.VISIBLE) return false
        pickAnchor?.requestFocus()
        hidePicker()
        return true
    }

    private fun hidePicker() {
        overlay?.visibility = View.GONE
        picks.clear()
        pickAnchor = null
    }

    /** 卡片里的左右移动；上下等于「算了」，跟返回同一个意思 */
    private fun pickerMove(code: Int): Boolean {
        val i = picks.indexOfFirst { it.hasFocus() }
        if (i < 0) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (i > 0) picks[i - 1].requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (i < picks.lastIndex) picks[i + 1].requestFocus()
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> dismissPicker()
            else -> return false
        }
        return true
    }

    fun clear() {
        // 清空时把字母卡一并收起：面板要关了、或换了一次新的输入，
        // 卡片还挂着就会在下次打开时莫名其妙地又冒出来（焦点还悬在它下面）
        hidePicker()
        buffer.setLength(0)
        onText("")
    }

    /**
     * 直接换掉整串输入，不触发 onText。
     *
     * 点一条历史时用：搜索词由宿主接管（它还要读历史、发请求、重画左栏），
     * 再走一遍 onText 会让防抖又排一次搜索。这里只同步键盘内部那份 buffer，
     * 否则下一次按字母会接着旧串往上加。
     */
    fun setInput(text: String) {
        buffer.setLength(0)
        buffer.append(text)
    }

    /**
     * 外接键盘 / 手机遥控 App 直接打的字母。
     * 电视遥控器上没有字母键，但插个 USB 键盘、或用手机端的遥控 App 时，
     * 让输入直接落在搜索词上比逼用户用方向键一个个挑要合理得多。
     */
    fun type(ch: Char) {
        if (buffer.length >= 40) return
        if (ch == ' ' && (buffer.isEmpty() || buffer.last() == ' ')) return
        buffer.append(ch)
        onText(text)
    }

    fun backspace() {
        if (buffer.isEmpty()) return
        buffer.setLength(buffer.length - 1)
        onText(text)
    }

    /**
     * 落点：字母区第二排第一格（`jkl`）。
     *
     * 不是第一格 `abc` 也不是最后一排「搜索」：
     *   · 落 `abc` 的话，想按 `jkl` 那一排要先往下一次，每次进搜索页都白按一下；
     *   · 落「搜索」最糟——用户第一下想打第一个字母，结果把空词提交了。
     * 第二排是九宫格的几何中心，往上下左右都只有一格远，是个不偏心的起点。
     */
    fun focusFirst() {
        ensureBuilt()
        rows.getOrNull(1)?.firstOrNull()?.requestFocus()
            ?: rows.firstOrNull()?.firstOrNull()?.requestFocus()
    }

    /** 焦点落到最后一排开头（「空格」）。键盘下方的按钮按上要回到这里，不是「搜索」 */
    fun focusLastRowStart() {
        ensureBuilt()
        rows.lastOrNull()?.firstOrNull()?.requestFocus()
    }

    /**
     * 焦点在键盘里吗。
     *
     * 字母卡弹出来时，卡上的字母键在 container 的**兄弟**节点里（叠层），
     * 所以不能只沿 container 往上找——不认它们，方向键会漏给 FocusFinder，
     * 卡片上按左就直接跳出浮层了。
     */
    fun contains(view: View?): Boolean {
        if (!built) return false
        if (inside(view, container)) return true
        val box = overlay
        return box != null && box.visibility == View.VISIBLE && inside(view, box)
    }

    private fun inside(view: View?, root: View?): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (cur === root) return true
            cur = cur.parent as? View
        }
        return false
    }

    /**
     * 返回 true 表示这个方向键已被键盘吃掉。
     * 上下越界时返回 false，让上层决定是回页签还是进货架。
     */
    fun move(keyCode: Int): Boolean {
        if (!built) return false
        // 字母卡开着的时候，方向键只归卡片管：左右挑字母，上下关掉卡片
        if (overlay?.visibility == View.VISIBLE) return pickerMove(keyCode)
        val pos = locate() ?: return false
        val (r, c) = pos
        val line = rows[r]
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (c > 0) line[c - 1].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 最右一列再往右：返回 false，把「这一步该出门了」交给调用方。
                // 搜索页靠它走进右栏结果区；收藏夹浮层那边没有可去的地方，
                // 自己把这个键吃掉（不再往外传，传出去焦点会跑到浮层背后）
                if (c < line.lastIndex) {
                    line[c + 1].requestFocus()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (r == 0) return false
                rows[r - 1][mapColumn(line[c], rows[r - 1])].requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (r == rows.lastIndex) return false
                rows[r + 1][mapColumn(line[c], rows[r + 1])].requestFocus()
                return true
            }
        }
        return false
    }

    /**
     * 上下换行落到目标行的哪一格：按**格号**就近对。
     *
     * 三行字母每行都是三格，格号直接对应，一条直上直下。到底排（功能键）时
     * 0/1/2 各自对上空格 / 删除 / 搜索。不按序号比例：比例会把光标在换行时
     * 推向最右那颗，而这一排最右是「搜索」——误按一次就把词提交了。
     */
    private fun mapColumn(source: TextView, target: MutableList<TextView>): Int {
        val srcCol = keyOf[source]?.col ?: 0
        var best = 0
        var bestDist = Int.MAX_VALUE
        target.forEachIndexed { i, view ->
            val col = keyOf[view]?.col ?: return@forEachIndexed
            val dist = Math.abs(col - srcCol)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    private fun locate(): Pair<Int, Int>? {
        rows.forEachIndexed { r, line ->
            val c = line.indexOfFirst { it.hasFocus() }
            if (c >= 0) return r to c
        }
        return null
    }
}
