package com.kunplayer.tv.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.kunplayer.tv.R
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.diag.Diag

/**
 * 频谱色板：十色一格一格铺开，按哪格就是哪格。
 *
 * 为什么是一张网格而不是一行一行轮：颜色是「看」出来的，不是「数」出来的。
 * 原来左右键一个个过，选最后一档要按八下，按过头还得绕回来，中途根本记不住
 * 「刚才那个蓝在第几个」——而十种颜色摆在眼前，扫一眼就定了。
 *
 * 选中即生效，没有「确定」：按下这一格的时候颜色已经存了、两处频谱已经重画了，
 * 再要一次确认只是多点一下。选错也只是再点一次，代价完全一样。
 */
class ColorPalette(
    private val activity: Activity,
    /** 点了某个颜色：[target] 是哪一处（频谱 / 歌词），[index] 是 [Settings.COLORS] 的下标 */
    private val onPick: (target: Int, index: Int) -> Unit,
    /** 面板关掉后把焦点交回进来的那一行，否则焦点悬空、遥控器当场失灵 */
    private val onClosed: () -> Unit,
) {

    companion object {
        /** 改的是哪一处颜色。两块地方共用同一套十色，格子只画一份 */
        const val TARGET_SPECTRUM = 0
        const val TARGET_LYRIC = 1

        /** 一行五格。十色正好两行，屏幕上是一个规整的方块，不是一条长龙 */
        private const val COLS = 5
    }

    private val scrim: View = activity.findViewById(R.id.palette_scrim)
    private val grid: LinearLayout = activity.findViewById(R.id.palette_grid)

    private val slots = ArrayList<View>()
    private val rings = ArrayList<View>()

    /** 当前改的是哪一处，以及那一处现在用的是哪一格（只用来画外圈） */
    private var target = TARGET_SPECTRUM
    private var current = 0

    val isShowing: Boolean get() = scrim.visibility == View.VISIBLE

    init {
        build()
        scrim.setOnClickListener { close() }
    }

    /**
     * 按 [Settings.COLORS] 现铺，不写死在 XML 里。
     *
     * 颜色只有一处真源：加一色、换一色都只改 Settings，忘了改布局也不会出现
     * 「界面上十格、实际只认六格」这种对不上的情况。
     */
    private fun build() {
        val gap = dp(10)
        var row: LinearLayout? = null
        Settings.COLORS.forEachIndexed { i, color ->
            if (i % COLS == 0) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = if (i == 0) 0 else gap }
                }
                grid.addView(row)
            }
            val item = LayoutInflater.from(activity)
                .inflate(R.layout.item_swatch, row, false)
            item.findViewById<View>(R.id.swatch_dot).backgroundTintList =
                ColorStateList.valueOf(color)
            // 格与格之间留一手距离：两颗挨着的同色系圆点之间没有缝，看起来是一颗花生
            (item.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (i % COLS != 0) lp.marginStart = gap
            }
            item.setOnClickListener { pick(i) }
            row?.addView(item)
            slots += item
            rings += item.findViewById<View>(R.id.swatch_ring)
        }
    }

    /**
     * 打开色板。
     *
     * @param forTarget [TARGET_SPECTRUM] 或 [TARGET_LYRIC]。两块地方各有各的当前色，
     *                  打开时先落在自己那一颗上，改动也只写回自己那一份
     */
    fun show(forTarget: Int = TARGET_SPECTRUM) {
        target = forTarget
        current = if (forTarget == TARGET_LYRIC) {
            Settings.lyricsColorIndex(activity)
        } else {
            Settings.colorIndex(activity)
        }
        paintCurrent()
        scrim.visibility = View.VISIBLE
        // 焦点先落在「现在用的那一颗」：先让人看见当前是哪个，再决定换不换
        slots.getOrNull(current)?.requestFocus()
        Diag.enter(if (forTarget == TARGET_LYRIC) "歌词色板" else "频谱色板")
    }

    /** 只收面板，不动焦点——关设置页时用，紧接着整页就没了 */
    fun dismiss() {
        scrim.visibility = View.GONE
    }

    /**
     * 返回 = 「这次不换了」。
     *
     * 遥控器没有「点空白处关闭」这个动作，不接返回键的话，进了色板就只能选一个颜色
     * 才出得来——那等于把「退出」变成了一次改色。
     */
    private fun close() {
        if (!isShowing) return
        scrim.visibility = View.GONE
        onClosed()
    }

    private fun pick(i: Int) {
        if (i !in Settings.COLORS.indices) return
        current = i
        paintCurrent()
        // 先落盘刷预览、再收面板：收完面板焦点已经回到设置行上，
        // 屏幕上同一瞬间能看到「这一行的色点和预览一起变了」
        onPick(target, i)
        close()
    }

    private fun paintCurrent() {
        rings.forEachIndexed { i, ring ->
            ring.visibility = if (i == current) View.VISIBLE else View.GONE
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isShowing) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            Diag.confirm("色板 收起")
            close()
            return true
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT &&
            code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            return false
        }
        // 按住不放会补发 DOWN。十格连补几下就绕完一整圈，人会以为色板在自己乱跳
        if (event.repeatCount > 0) return true
        val at = slots.indexOf(activity.currentFocus)
        if (at < 0) {
            // 焦点意外丢了：按一下方向键就把它领回来，不要让人在色板上按了半天没反应
            slots.getOrNull(current)?.requestFocus()
            return true
        }
        slots[move(at, code)].requestFocus()
        Diag.move()
        return true
    }

    /**
     * 网格内的移动，四个方向都绕。
     *
     * 交给 FocusFinder 也能走，但走到边上就停住——电视上没有鼠标可以「扶一下」，
     * 停在角上的人只会以为键坏了。左右在同一行里循环，上下在两行之间换。
     */
    private fun move(from: Int, code: Int): Int {
        val head = from / COLS * COLS
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> head + (from - head + COLS - 1) % COLS
            KeyEvent.KEYCODE_DPAD_RIGHT -> head + (from - head + 1) % COLS
            else -> (from + COLS) % slots.size
        }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()
}
