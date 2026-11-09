package com.moinstudioz.advanceplay

import android.app.Activity
import android.webkit.WebView
import android.widget.EditText

/** Small polish for every screen: the item that has the remote's focus grows a little ("pop"), then settles back. */
object Fx {
    fun install(a: Activity) {
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, nw ->
            if (old != null && old.scaleX != 1f) old.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (nw != null && nw.isClickable && nw !is EditText && nw !is WebView)
                nw.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140).start()
        }
    }
}
