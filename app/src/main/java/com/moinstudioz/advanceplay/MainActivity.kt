package com.moinstudioz.advanceplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONTokener

class MainActivity : ComponentActivity() {
    private val RED = 0xFFFF3B30.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val qNames = arrayOf("HD up to 1080p (merged)", "Compatible (single file, 360p-720p)", "Audio only (M4A)")

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var web: WebView
    private lateinit var tStatus: TextView
    private lateinit var tUsb: TextView
    private lateinit var tNet: TextView
    private lateinit var bar: ProgressBar
    private lateinit var card: LinearLayout
    private lateinit var cardT: TextView
    private lateinit var cardS: TextView
    private lateinit var black: View
    private lateinit var settings: ScrollView
    private lateinit var sUsb: TextView
    private lateinit var sQ: TextView
    private lateinit var sInfo: TextView
    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var seenDone = 0
    private var seenErr = 0
    private var longHandled = false
    private val h = Handler(Looper.getMainLooper())

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            Prefs.setTree(this, uri); toast("USB folder saved"); refreshSettings()
        }
    }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(v: View, cb: WebChromeClient.CustomViewCallback) {
            custom = v; customCb = cb
            root.addView(v, 1, FrameLayout.LayoutParams(MATCH, MATCH)); hideBars(true)
        }
        override fun onHideCustomView() {
            custom?.let { root.removeView(it) }
            custom = null; customCb?.onCustomViewHidden(); customCb = null; hideBars(false)
        }
    }

    // ---------- life cycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (DL.lastDone.isEmpty()) DL.lastDone = Prefs.lastDone(this)
        seenDone = DL.doneSeq; seenErr = DL.errSeq
        buildUi(); setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) runCatching { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    black.visibility == View.VISIBLE -> exitBlack()
                    settings.visibility == View.VISIBLE -> settings.visibility = View.GONE
                    custom != null -> chrome.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        handleIntent(intent)
        if (b == null) web.loadUrl(Prefs.lastUrl(this) ?: "https://m.youtube.com")
        resumeUnfinished()
        h.post(tick); h.post(probe)
    }

    /** After a reboot or crash, unfinished downloads start again automatically. */
    private fun resumeUnfinished() {
        if (DL.resumed) return
        DL.resumed = true
        if (DL.queue > 0 || DL.active || !Usb.hasTarget(this)) return
        val p = Prefs.pending(this)
        if (p.isEmpty()) return
        toast("Resuming ${p.size} unfinished download(s)")
        p.forEach { u ->
            runCatching { ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", u)) }
        }
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val m = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
            if (m != null && isVideo(m)) enqueue(m)
        }
    }

    // ---------- UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun tv(sp: Float, c: Int) = TextView(this).apply { textSize = sp; setTextColor(c) }

    private fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }

    private fun pill(txt: String, fg: Int = Color.WHITE, accent: Int = Color.WHITE, click: () -> Unit) =
        TextView(this).apply {
            text = txt; setTextColor(fg); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(0xFF2C2C2E.toInt(), accent, 3))
                addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF2C2C2E.toInt(), accent, 2))
                addState(intArrayOf(), shape(0xFF1C1C1E.toInt(), 0xFF3A3A3C.toInt(), 1))
            }
            setOnClickListener { click() }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            content.setPadding(i.left, i.top, i.right, i.bottom); ins
        }

        // toolbar
        val tb = LinearLayout(this).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        fun add(v: View) = tb.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
        add(pill("Home") { web.loadUrl("https://m.youtube.com") })
        add(pill("Back") { if (web.canGoBack()) web.goBack() })
        add(pill("Download") { downloadCurrent() })
        add(pill("Background") { enterBlack() })
        add(pill("Settings", RED, RED) { openSettings() })
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tb) }, MATCH, WRAP)

        // YouTube
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            // look like normal Chrome so Google sign-in is accepted
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme; return s != "http" && s != "https"
                }
                override fun onPageFinished(v: WebView, url: String?) {
                    CookieManager.getInstance().flush()          // keep login after reboot / power-off
                    if (url != null && url.contains("youtube.com") && !url.contains("/watch") && !url.contains("/shorts"))
                        Prefs.setLastUrl(this@MainActivity, url)
                }
            }
            webChromeClient = chrome
            setOnLongClickListener { longPress() }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(web, true)
            if (getCookie("https://m.youtube.com")?.contains("PREF=") != true)
                setCookie("https://m.youtube.com", "PREF=f6=400; Domain=.youtube.com; Path=/")   // YouTube dark theme
        }
        content.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // status panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)); setBackgroundColor(Color.BLACK)
        }
        tStatus = tv(14f, Color.WHITE)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2C2C2E.toInt())
        }
        tUsb = tv(13f, Color.WHITE)
        tNet = tv(13f, RED)
        val wm = tv(11f, Color.WHITE).apply { text = "Created By Moin Studioz"; alpha = .65f; gravity = Gravity.END }
        panel.addView(tStatus); panel.addView(bar, MATCH, dp(6)); panel.addView(tUsb); panel.addView(tNet); panel.addView(wm, MATCH, WRAP)
        content.addView(panel, MATCH, WRAP)
        root.addView(content, MATCH, MATCH)

        // settings overlay (red)
        settings = ScrollView(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        val sl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20)) }
        fun full(v: View) = sl.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(8), 0, dp(8)) })
        full(tv(26f, RED).apply { text = "Settings"; typeface = Typeface.DEFAULT_BOLD })
        sUsb = pill("", RED, RED) {
            try { pickTree.launch(null) } catch (e: ActivityNotFoundException) {
                toast("No folder picker on this device. The USB drive's app folder will be used instead.")
            }
        }
        sQ = pill("", RED, RED) { Prefs.setQuality(this, (Prefs.quality(this) + 1) % 3); refreshSettings() }
        sInfo = tv(14f, RED)
        full(sUsb); full(sQ); full(sInfo)
        full(pill("Reset network counters", RED, RED) { Net.session = 0; Prefs.resetTotal(this); refreshInfo() })
        full(pill("Close") { settings.visibility = View.GONE })
        full(tv(12f, Color.WHITE).apply { text = "Advance Play By Moin\nCreated By Moin Studioz"; alpha = .7f })
        settings.addView(sl)
        root.addView(settings, MATCH, MATCH)

        // download complete card
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22)); visibility = View.GONE; elevation = dp(8).toFloat()
        }
        cardT = tv(24f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        cardS = tv(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        card.addView(cardT); card.addView(cardS)
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // jet-black Background Mode overlay (always on top)
        black = View(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; keepScreenOn = true
            isClickable = true; isLongClickable = true
            setOnLongClickListener { exitBlack(); true }
        }
        root.addView(black, MATCH, MATCH)
    }

    // ---------- background mode ----------
    private fun hideBars(hide: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun enterBlack() {
        black.visibility = View.VISIBLE; hideBars(true)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun exitBlack() {
        black.visibility = View.GONE; if (custom == null) hideBars(false)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    // ---------- settings ----------
    private fun openSettings() { refreshSettings(); settings.visibility = View.VISIBLE; sUsb.requestFocus() }

    private fun refreshSettings() {
        val name = Prefs.tree(this)?.let { DocumentFile.fromTreeUri(this, it)?.name }
        sUsb.text = "USB folder: " + (name ?: "tap to choose")
        sQ.text = "Download quality: " + qNames[Prefs.quality(this)]
        refreshInfo()
    }

    private fun refreshInfo() {
        sInfo.text = "Bandwidth split: video 80% / downloads 20% while a video is playing\n" +
            "Playing now: " + (if (Throttle.streaming) "YES (downloads limited)" else "no (downloads full speed)") + "\n" +
            "Link speed estimate: " + spd(Throttle.capacity(this)) + "\n" +
            "Network this session: " + fmt(Net.session) + "\n" +
            "Network all-time: " + fmt(Prefs.total(this))
    }

    // ---------- downloads ----------
    private fun isVideo(u: String) = u.contains("youtube.com/watch") || u.contains("youtu.be/") ||
        u.contains("youtube.com/shorts/") || u.contains("youtube.com/live/")

    private fun enqueue(u: String) {
        if (!isVideo(u)) { toast("That is not a video link"); return }
        if (!Usb.hasTarget(this)) { toast("Select your USB folder first"); openSettings(); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", u))
            toast("Added to download queue")
        } catch (e: Exception) { toast("Cannot start download: " + e.message) }
    }

    private fun downloadCurrent() {
        val u = web.url ?: ""
        if (isVideo(u)) enqueue(u) else toast("Open a video first, or long-press a video")
    }

    /** Touch long-press: find the link under the finger. */
    private fun longPress(): Boolean {
        val t = web.hitTestResult.type
        if (t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val msg = Handler(Looper.getMainLooper()) { m ->
                val u = m.data.getString("url")
                if (u != null) enqueue(u) else toast("No link found"); true
            }.obtainMessage()
            web.requestFocusNodeHref(msg)
            return true
        }
        return false
    }

    /** TV remote: hold OK on the highlighted link. */
    private fun focusedLink() {
        web.evaluateJavascript(
            "(function(){var e=document.activeElement;var a=e&&e.closest?e.closest('a'):null;return a?a.href:''})()"
        ) { v ->
            val s = (JSONTokener(v ?: "\"\"").nextValue() as? String).orEmpty()
            if (s.isNotEmpty()) enqueue(s) else toast("Highlight a video, then hold OK")
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val dpad = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT ||
            k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (black.visibility == View.VISIBLE && dpad) return true
        if (k == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP && settings.visibility != View.VISIBLE) { openSettings(); return true }
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
        if (ok && settings.visibility != View.VISIBLE && web.hasFocus()) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { longHandled = true; focusedLink(); return true }
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 1 && longHandled) return true
            if (e.action == KeyEvent.ACTION_UP && longHandled) { longHandled = false; return true }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- polling ----------
    private fun fmt(b: Long) = Formatter.formatFileSize(this, b)
    private fun spd(b: Long) = Formatter.formatFileSize(this, b) + "/s"

    private val hideCard = Runnable { card.visibility = View.GONE }

    private fun showCard(t: String, s: String, err: Boolean) {
        cardT.text = t; cardS.text = s
        cardT.setTextColor(if (err) RED else Color.WHITE)
        card.background = shape(0xFF1C1C1E.toInt(), if (err) RED else Color.WHITE, 2)
        card.visibility = View.VISIBLE
        h.removeCallbacks(hideCard); h.postDelayed(hideCard, 5000)
    }

    private val tick = object : Runnable {
        override fun run() {
            Net.tick(this@MainActivity)
            val st = StringBuilder()
            if (DL.active) {
                st.append("\u2B07 ").append(DL.title.take(50)).append(" \u00B7 ").append(DL.pct).append("% \u00B7 ").append(spd(DL.speed))
                if (DL.queue > 1) st.append(" \u00B7 +").append(DL.queue - 1).append(" queued")
                if (Throttle.streaming) st.append(" \u00B7 limited to 20%")
            } else if (DL.lastDone.isNotEmpty()) st.append("\u2714 Saved: ").append(DL.lastDone.take(50))
            else st.append("Idle \u00B7 long-press a video to download")
            tStatus.text = st
            bar.progress = if (DL.active) DL.pct else 0
            val u = Usb.space(this@MainActivity)
            tUsb.text = if (u == null) "USB: not connected / not selected" else "USB free: ${fmt(u.first)} of ${fmt(u.second)}"
            tNet.text = if (!Net.supported) "Network usage unavailable" else
                "Network \u2193 ${spd(Net.speed)} \u00B7 Session ${fmt(Net.session)} \u00B7 Total ${fmt(Prefs.total(this@MainActivity))}" +
                    (if (Throttle.streaming) "\nStreaming video 80% \u00B7 Downloads 20%" else "")
            if (DL.doneSeq != seenDone) { seenDone = DL.doneSeq; showCard("\u2714 Download Complete", DL.lastDone + "\nSaved to USB", false) }
            if (DL.errSeq != seenErr) { seenErr = DL.errSeq; showCard("Download failed", DL.errMsg, true) }
            if (settings.visibility == View.VISIBLE) refreshInfo()
            h.postDelayed(this, 1000)
        }
    }

    /** Detects an unmuted playing video (live or normal) so downloads can drop to 20%. */
    private val probe = object : Runnable {
        override fun run() {
            web.evaluateJavascript(
                "(function(){var v=document.querySelector('video');return !!(v&&!v.paused&&!v.ended&&!v.muted&&v.volume>0&&v.readyState>2)})()"
            ) { Throttle.streaming = it == "true" }
            h.postDelayed(this, 2000)
        }
    }
}
