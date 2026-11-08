package com.moinstudioz.advanceplay

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * Restart buttons.
 * - Restart the app: closes the app completely and opens it again.
 * - Reboot the TV: Android does not let a normal app restart a TV by itself. The app uses its "restart helper"
 *   (an accessibility service, switched on once) that opens the TV's own power menu and presses Restart for you.
 */
object Power {
    /** Closes the app completely (downloads in progress stop) and opens it again after a moment. */
    fun restartApp(a: Activity) {
        val cls = if (Dev.isTv(a)) BrowseActivity::class.java else MainActivity::class.java
        val i = Intent(a, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(a, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        (a.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 700, pi)
        a.finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 300)
    }

    /** The Restart / Reboot dialog. */
    fun showDialog(a: Activity) {
        AlertDialog.Builder(a).setTitle("Restart")
            .setItems(arrayOf("Restart the app", "Reboot the whole TV")) { _, i ->
                if (i == 0) {
                    AlertDialog.Builder(a).setTitle("Restart the app?")
                        .setMessage("Downloads that are running will stop.")
                        .setPositiveButton("Restart") { _, _ -> restartApp(a) }
                        .setNegativeButton("Cancel", null).show()
                } else rebootTv(a)
            }.show()
    }

    private fun rebootTv(a: Activity) {
        val svc = RebootService.instance
        if (svc == null) { setupDialog(a); return }
        AlertDialog.Builder(a).setTitle("Reboot the TV?")
            .setMessage("The TV's power menu opens by itself and Restart is pressed. Downloads that are running will stop.")
            .setPositiveButton("Reboot") { _, _ ->
                svc.restartTv()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!RebootService.pressed) Toast.makeText(a.applicationContext,
                        "This TV's power menu has no Restart button. Hold the Power button on the remote and choose Restart.", Toast.LENGTH_LONG).show()
                }, 9000)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** One-time step: switch the restart helper on in the TV's Accessibility settings. */
    private fun setupDialog(a: Activity) {
        AlertDialog.Builder(a).setTitle("One-time step")
            .setMessage("Switch on \"Advance Play - Restart helper\" in Accessibility. After that, Reboot the TV works with one click.\n\n" +
                "If the switch is greyed out (\"Restricted setting\"): Settings > Apps > Advance Play > menu (three dots) > Allow restricted settings.")
            .setPositiveButton("Open Accessibility") { _, _ ->
                try { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (e: ActivityNotFoundException) {
                    Toast.makeText(a, "Open it by hand: Settings > Device Preferences > Accessibility", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
