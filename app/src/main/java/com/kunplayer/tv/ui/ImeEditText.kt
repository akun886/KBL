package com.kunplayer.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/**
 * 带「按键先交给我」通道的 EditText。
 *
 * 焦点在输入框上、键盘也挂着的时候，按键会先落到输入法窗口：
 *   · BACK 实测要连按三次才轮到宿主（第一次收键盘、第二次被它自己吃掉、第三次才放行）；
 *   · 方向键被拿去做候选选择，上下左右一个都到不了 View 树。
 * 表现都是「按了没反应」。[onKeyPreIme] 是输入法之前的那道关口，每个键都能拿到。
 *
 * ACTION_DOWN 直接吃掉不下发：漏给输入法的话它会先按自己的逻辑处理一遍，
 * 等到 UP 再来已经晚一步了。
 */
class ImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    /** 返回 true 表示这一次 BACK 已被处理，不再往下传 */
    var onBack: (() -> Boolean)? = null

    /**
     * 方向键的出路。返回 true = 宿主吃掉了这个键（焦点要移出输入框），
     * 返回 false = 不关我的事，交给输入框自己（左右移光标就该这样）。
     */
    var onDpad: ((Int) -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) return true
            if (event.action == KeyEvent.ACTION_UP) return onBack?.invoke() ?: false
        }
        if (event.action == KeyEvent.ACTION_DOWN && keyCode in DPAD) {
            if (onDpad?.invoke(keyCode) == true) return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    private companion object {
        val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
