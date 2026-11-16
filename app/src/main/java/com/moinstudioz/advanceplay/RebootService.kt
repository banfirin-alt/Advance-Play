package com.moinstudioz.advanceplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The restart helper. It is switched on once in the TV's Accessibility settings.
 * When "Reboot the TV" is pressed it opens the TV's own power menu and presses "Restart" in it (and in the question that follows).
 * It does nothing at any other time.
 */
class RebootService : AccessibilityService() {
    companion object {
        /** The running helper, or null while it is not switched on. */
        @Volatile var instance: RebootService? = null
        /** True once a Restart button was pressed. */
        @Volatile var pressed = false
        @Volatile private var until = 0L
    }

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    override fun onInterrupt() {}

    /** Opens the TV's power menu; for the next 15 seconds a Restart button that shows up is pressed. */
    fun restartTv() {
        pressed = false
        until = System.currentTimeMillis() + 15000
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > until) return
        val root = rootInActiveWindow ?: return
        for (word in listOf("Restart", "Reboot")) {
            val found = root.findAccessibilityNodeInfosByText(word) ?: continue
            for (n in found) {
                val t = n.text?.toString() ?: continue
                if (t.contains("power off", true)) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { pressed = true; return }
            }
        }
    }
}
