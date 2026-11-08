package com.moinstudioz.advanceplay

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dadb.Dadb
import java.io.File
import java.net.ConnectException

/**
 * Restart buttons.
 * - Restart the app: closes the app completely and opens it again.
 * - Reboot the TV: a normal app is not allowed to reboot a TV. The only way without root is to ask the TV itself through its
 *   own "network debugging" (adb) switch in Developer options. The app then sends the "reboot" command to its own TV.
 */
object Power {
    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density + .5f).toInt()

    /** Closes the app completely (downloads in progress stop) and opens it again after a moment. */
    fun restartApp(a: Activity) {
        val cls = if (Dev.isTv(a)) BrowseActivity::class.java else MainActivity::class.java
        val i = Intent(a, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(a, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        (a.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 700, pi)
        a.finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 300)
    }

    /** Sends "reboot" to the TV through its own network-debugging port. [done] gets null when sent, or a message to show. */
    private fun rebootTv(c: Context, port: Int, done: (String?) -> Unit) {
        Thread {
            var msg: String? = null
            try {
                // the key that the TV will ask you to allow is kept inside the app's own folder
                val home = File(c.filesDir, "adbhome")
                File(home, ".android").mkdirs()
                System.setProperty("user.home", home.absolutePath)
                Dadb.create("127.0.0.1", port).use { d -> d.shell("reboot") }
            } catch (e: ConnectException) {
                msg = "The TV did not answer on port $port. Open Developer options on the TV and switch on ADB debugging / Network debugging, then try again."
            } catch (e: Exception) {
                // when the TV really reboots it cuts the connection, which also ends here
                msg = "The reboot command was sent. If the TV does not restart within 15 seconds, it was not allowed: switch on " +
                    "ADB / Network debugging in Developer options, press Reboot again and tap Allow (tick Always) on the TV."
            }
            Handler(Looper.getMainLooper()).post { done(msg) }
        }.start()
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
                } else askReboot(a)
            }.show()
    }

    private fun askReboot(a: Activity) {
        val sp = a.getSharedPreferences("ap", Context.MODE_PRIVATE)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0) }
        box.addView(TextView(a).apply {
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            text = "This restarts the whole TV (not switch off).\n\nBefore the first time: Settings > Device > About: press OK on Build 7 times, " +
                "then in Developer options switch on ADB debugging and Network debugging. When the TV asks \"Allow debugging?\", " +
                "tick Always and press Allow."
        })
        val port = EditText(a).apply {
            hint = "Debug port (usually 5555)"; setText(sp.getInt("adbport", 5555).toString()); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(port, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(a).setTitle("Reboot the TV").setView(box)
            .setPositiveButton("Reboot now") { _, _ ->
                val p = port.text.toString().toIntOrNull() ?: 5555
                sp.edit().putInt("adbport", p).apply()
                Toast.makeText(a, "Sending reboot... if the TV asks, press Allow", Toast.LENGTH_LONG).show()
                rebootTv(a, p) { m -> if (m != null) AlertDialog.Builder(a).setTitle("Reboot").setMessage(m).setPositiveButton("OK", null).show() }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
