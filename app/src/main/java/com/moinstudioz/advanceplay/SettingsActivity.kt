package com.moinstudioz.advanceplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile

/** All settings in red. Works with a TV remote: no file picker needed. */
class SettingsActivity : ComponentActivity() {
    private val RED = 0xFFFF3B30.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val qNames = arrayOf("HD up to 1080p (merged)", "Compatible (single file, 360p-720p)", "Audio only (M4A)")
    private val lNames = arrayOf("Auto", "YouTube TV layout", "Normal YouTube")
    private lateinit var tTarget: TextView
    private lateinit var tInfo: TextView
    private lateinit var bUsb: TextView
    private lateinit var bPick: TextView
    private lateinit var bQ: TextView
    private lateinit var bSpeed: TextView
    private lateinit var bHome: TextView
    private lateinit var bLayout: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (Dev.isTv(this) && Usb.treeUuid(uri).equals("primary", true)) {
                toast("That is the TV memory. Choose a folder on the USB drive."); return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            Prefs.setTree(this, uri); Prefs.setDirect(this, null)
            afterTargetChosen("Folder saved")
        }
    }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { useUsb() }

    private fun afterTargetChosen(msg: String) {
        Thread {
            val restored = Backup.restore(this)
            Backup.save(this)
            runOnUiThread { toast(if (restored) "$msg. Your data was restored from the backup." else msg); refresh() }
        }.start()
    }

    private fun useUsb() {
        val s = Usb.setupDirect(this)
        when {
            s.ok -> afterTargetChosen("Saving to ${s.msg}")
            s.needPerm -> requestStoragePermission(s.msg)
            else -> toast(s.msg)
        }
        refresh()
    }

    private fun requestStoragePermission(msg: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                toast("Switch ON 'Allow access to all files' for Advance Play, go back, then press Use USB drive again.")
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (e2: Exception) { toast(msg + " This TV has no permission screen. Try the file-picker button, or another USB drive.") }
            }
        } else askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun pill(txt: String, fg: Int = RED, accent: Int = RED, click: () -> Unit) = TextView(this).apply {
        text = txt; setTextColor(fg); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(18), dp(14), dp(18), dp(14)); isFocusable = true; isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF262626.toInt(), accent, 3))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF262626.toInt(), accent, 2))
            addState(intArrayOf(), shape(0xFF161616.toInt(), 0xFF2E2E2E.toInt(), 1))
        }
        setOnClickListener { click() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tv = Dev.isTv(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val e = if (tv) dp(48) else dp(16)
            scroll.setPadding(i.left + e, i.top + e, i.right + e, i.bottom + e); ins
        }
        fun add(v: View) = col.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(7), 0, dp(7)) })
        add(TextView(this).apply { text = "Settings"; setTextColor(RED); textSize = 28f; typeface = Typeface.DEFAULT_BOLD })
        tTarget = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        add(tTarget)
        bUsb = pill(if (tv) "Use USB drive (recommended)" else "Use SD card / USB drive") { useUsb() }
        bPick = pill("Choose folder with file picker") {
            try { pickTree.launch(null) } catch (e: ActivityNotFoundException) { toast("This device has no file picker. Use the USB drive button.") }
        }
        bQ = pill("") { Prefs.setQuality(this, (Prefs.quality(this) + 1) % 3); refresh() }
        bSpeed = pill("") { Throttle.maxSpeed = !Throttle.maxSpeed; Prefs.setMaxSpeed(this, Throttle.maxSpeed); refresh() }
        bHome = pill("") { Prefs.setTvHome(this, 1 - Prefs.tvHome(this)); refresh() }
        bLayout = pill("") { Prefs.setLayout(this, (Prefs.layout(this) + 1) % 3); refresh() }
        add(bUsb); add(bPick); add(bQ); add(bSpeed)
        if (tv) add(bHome)
        add(bLayout)
        tInfo = TextView(this).apply { setTextColor(RED); textSize = 14f }
        add(tInfo)
        add(pill("Backup now") {
            Thread { val ok = Backup.save(this); runOnUiThread { toast(if (ok) "Backup saved next to your videos" else "Backup failed. Choose where to save first.") } }.start()
        })
        add(pill("Reset network counters") { Net.session = 0; Prefs.resetTotal(this); refresh() })
        add(pill("Close", Color.WHITE, Color.WHITE) { finish() })
        add(TextView(this).apply { text = "Advance Play\nCreated By Moin Studioz"; setTextColor(Color.WHITE); alpha = .7f; textSize = 12f })
        scroll.addView(col); root.addView(scroll, MATCH, MATCH)
        setContentView(root)
        bUsb.requestFocus()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val tree = Prefs.tree(this)
        val where = when {
            tree != null -> "Folder: " + (DocumentFile.fromTreeUri(this, tree)?.name ?: tree.toString())
            Prefs.direct(this) != null -> "USB: " + Prefs.direct(this)
            else -> "Not set yet"
        }
        val err = Usb.check(this)
        tTarget.text = "Saving to: $where" + (if (err != null) "\n$err" else "\nReady to download")
        bQ.text = "Download quality: " + qNames[Prefs.quality(this)]
        bSpeed.text = "Download speed: " + (if (Throttle.maxSpeed) "MAXIMUM always" else "Maximum, but 80/20 while a video plays")
        bHome.text = "TV home screen: " + (if (Prefs.tvHome(this) == 0) "Fast native (recommended)" else "YouTube web page")
        bLayout.text = "YouTube web layout: " + lNames[Prefs.layout(this)]
        tInfo.text = "Network this session: " + Formatter.formatFileSize(this, Net.session) + "\n" +
            "Network all-time: " + Formatter.formatFileSize(this, Prefs.total(this)) + "\n" +
            "Link speed estimate: " + Formatter.formatFileSize(this, Throttle.capacity(this)) + "/s"
    }
}
