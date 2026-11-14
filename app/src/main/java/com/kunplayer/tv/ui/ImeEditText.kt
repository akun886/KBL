package com.kunplayer.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/**
 * 带「返回键先交给我」通道的 EditText。
 *
 * 焦点在输入框上时，BACK 会先落到输入法窗口：搜狗 TV 版实测要连按三次才轮到宿主
 * （第一次收键盘、第二次被它自己吃掉、第三次才放行）——表现就是「按返回没反应」。
 * 这一层绕不过去，[onKeyPreIme] 却是输入法之前的那道关口，每一次 BACK 都能拿到。
 *
 * ACTION_DOWN 直接吃掉不下发：漏给输入法的话它会先按自己的逻辑收键盘，
 * 等到 UP 再来处理就已经晚一步了。
 */
class ImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    /** 返回 true 表示这一次 BACK 已被处理，不再往下传 */
    var onBack: (() -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) return true
            if (event.action == KeyEvent.ACTION_UP) return onBack?.invoke() ?: false
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
