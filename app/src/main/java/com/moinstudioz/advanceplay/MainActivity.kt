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
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : ComponentActivity() {
    companion object {
        // Looks like a Samsung smart TV, so YouTube serves its real "YouTube TV" interface.
        const val TV_UA = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36"
    }

    private val RED = 0xFFFF3B30.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val qNames = arrayOf("HD up to 1080p (merged)", "Compatible (single file, 360p-720p)", "Audio only (M4A)")
    private val lNames = arrayOf("Auto (TV layout on TV)", "YouTube TV layout", "Normal YouTube")
    private val tv by lazy { Dev.isTv(this) }

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
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
    private lateinit var sSpeed: TextView
    private lateinit var sLayout: TextView
    private lateinit var sInfo: TextView
    private var rail: LinearLayout? = null
    private var panelView: View? = null
    private var menuUntil = 0L
    private var custom: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private lateinit var mobUa: String
    private var seenDone = 0
    private var seenErr = 0
    private var errRetry = 0
    private var backHeld = false
    private var ticks = 0
    private val h = Handler(Looper.getMainLooper())

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (tv && Usb.treeUuid(uri).equals("primary", true)) {
                toast("That is the TV memory. Choose a folder on the USB drive.")
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            Prefs.setTree(this, uri)
            Thread {
                val restored = Backup.restore(this)      // reinstall: bring everything back from the backup file
                Backup.save(this)
                runOnUiThread {
                    Throttle.maxSpeed = Prefs.maxSpeed(this)
                    toast(if (restored) "Folder saved. Your data was restored from the backup." else "Download folder saved")
                    refreshSettings()
                }
            }.start()
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
        Throttle.maxSpeed = Prefs.maxSpeed(this)
        seenDone = DL.doneSeq; seenErr = DL.errSeq
        buildUi(); setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) runCatching { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    black.visibility == View.VISIBLE -> exitBlack()
                    settings.visibility == View.VISIBLE -> { settings.visibility = View.GONE; web.requestFocus() }
                    rail?.hasFocus() == true -> web.requestFocus()
                    custom != null -> chrome.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        handleIntent(intent)
        loadStart()
        resumeUnfinished()
        if (tv) toast("Hold BACK or press MENU for the Advance Play menu")
        h.post(tick); h.post(probe)
        Thread { Usb.autoSetup(this); UsbState.refresh(this, true); Backup.save(this) }.start()   // find USB again + refresh backup on every start
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onPause() {
        super.onPause()
        if (!::web.isInitialized) return
        CookieManager.getInstance().flush()
        val u = web.url
        if (u != null && u.contains("youtube.com") && !u.contains("watch") && !u.contains("shorts")) Prefs.setLastUrl(this, u)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); if (::web.isInitialized) web.destroy(); super.onDestroy() }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val m = Regex("https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
            if (m != null) enqueue(m)
        }
    }

    /** After a reboot or crash, unfinished downloads start again automatically. */
    private fun resumeUnfinished() {
        if (DL.resumed) return
        DL.resumed = true
        if (DL.queue > 0 || DL.active || UsbState.err != null) return
        val p = Prefs.pending(this)
        if (p.isEmpty()) return
        toast("Resuming ${p.size} unfinished download(s)")
        p.forEach { u ->
            runCatching { ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", u)) }
        }
    }

    // ---------- YouTube layout ----------
    private fun useTvLayout() = when (Prefs.layout(this)) { 1 -> true; 2 -> false; else -> tv }
    private fun home() = if (useTvLayout()) "https://www.youtube.com/tv" else "https://m.youtube.com"
    private fun applyUa() { web.settings.userAgentString = if (useTvLayout()) TV_UA else mobUa }

    private fun loadStart() {
        val last = Prefs.lastUrl(this)
        web.loadUrl(if (last != null && last.contains("youtube.com") && last.contains("/tv") == useTvLayout()) last else home())
    }

    // ---------- UI helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun txt(sp: Float, c: Int) = TextView(this).apply { textSize = sp; setTextColor(c) }

    private fun shape(fill: Int, stroke: Int, w: Int, r: Int = 28) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(w), stroke)
    }

    private fun focusBg(accent: Int, r: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(0xFF262626.toInt(), accent, 3, r))
        addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF262626.toInt(), 0xFF262626.toInt(), 1, r))
        addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 0, r))
    }

    private fun pill(txt: String, fg: Int = Color.WHITE, accent: Int = Color.WHITE, click: () -> Unit) =
        TextView(this).apply {
            text = txt; setTextColor(fg); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isFocusable = true; isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(0xFF262626.toInt(), accent, 3))
                addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF262626.toInt(), accent, 2))
                addState(intArrayOf(), shape(0xFF161616.toInt(), 0xFF2E2E2E.toInt(), 1))
            }
            setOnClickListener { click() }
        }

    private fun navItem(icon: Int, label: String, c: Int, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(6)); isFocusable = true; isClickable = true
        background = focusBg(c, 22)
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(c) }, dp(if (tv) 30 else 24), dp(if (tv) 30 else 24))
        addView(TextView(context).apply { text = label; textSize = if (tv) 12f else 10.5f; setTextColor(c); gravity = Gravity.CENTER }, WRAP, WRAP)
        setOnClickListener { click() }
    }

    private fun navBar(horizontal: Boolean): LinearLayout {
        val n = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6)); background = shape(0xFF141414.toInt(), 0xFF2A2A2A.toInt(), 1, 32)
        }
        fun item(icon: Int, label: String, c: Int, click: () -> Unit) {
            val lp = if (horizontal) LinearLayout.LayoutParams(0, WRAP, 1f)
            else LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(0, dp(3), 0, dp(3)) }
            n.addView(navItem(icon, label, c, click), lp)
        }
        item(R.drawable.ic_home, "Home", Color.WHITE) { web.loadUrl(home()) }
        item(R.drawable.ic_library, "Library", Color.WHITE) { startActivity(Intent(this, LibraryActivity::class.java)) }
        item(R.drawable.ic_download, "Download", Color.WHITE) { downloadCurrent() }
        item(R.drawable.ic_moon, "Background", Color.WHITE) { enterBlack() }
        item(R.drawable.ic_settings, "Settings", RED) { openSettings() }
        return n
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply { orientation = if (tv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val e = if (tv) dp(28) else 0
            content.setPadding(i.left + e, i.top + e, i.right + e, i.bottom + e); ins
        }

        // ----- YouTube -----
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            if (Build.VERSION.SDK_INT >= 33 && !tv) settings.isAlgorithmicDarkeningAllowed = true
            mobUa = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme; return s != "http" && s != "https"
                }
                override fun onPageFinished(v: WebView, url: String?) {
                    errRetry = 0; swipe.isRefreshing = false
                    CookieManager.getInstance().flush()
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (!r.isForMainFrame) return
                    swipe.isRefreshing = false
                    if (errRetry++ < 2) v.postDelayed({ v.reload() }, 1500)
                    else if (useTvLayout() && Prefs.layout(this@MainActivity) != 2) {
                        Prefs.setLayout(this@MainActivity, 2); applyUa(); errRetry = 0
                        toast("TV layout is not available here. Using normal YouTube."); v.loadUrl(home())
                    } else toast("Could not load the page. Pull down or press Home to retry.")
                }
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    runOnUiThread { recreate() }; return true
                }
            }
            webChromeClient = chrome
            setOnLongClickListener { longPress() }
        }
        applyUa()
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.WHITE); setProgressBackgroundColorSchemeColor(0xFF2C2C2E.toInt())
            addView(web, MATCH, MATCH)
            setOnRefreshListener { web.reload() }
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
            isEnabled = !tv
        }

        // ----- status card -----
        tStatus = txt(14f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2C2C2E.toInt())
        }
        tUsb = txt(12f, Color.WHITE)
        tNet = txt(12f, RED)
        val wm = txt(11f, Color.WHITE).apply { text = "Created By Moin Studioz"; alpha = .65f; gravity = Gravity.END }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(10))
            background = shape(0xFF111111.toInt(), 0xFF2A2A2A.toInt(), 1, 24)
        }
        panelView = panel
        panel.addView(tStatus); panel.addView(bar, MATCH, dp(5))
        if (tv) panel.addView(tUsb)
        panel.addView(tNet); panel.addView(wm, MATCH, WRAP)
        val panelLp = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) }

        if (tv) {
            // ----- TV: menu rail on the left, YouTube TV on the right -----
            val r = navBar(false); rail = r
            content.addView(r, LinearLayout.LayoutParams(dp(110), MATCH).apply { setMargins(0, 0, dp(12), 0) })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(web.also { (it.parent as? ViewGroup)?.removeView(it) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            right.addView(panel, panelLp)
            content.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            // ----- phone: app bar, YouTube, status card, bottom navigation -----
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), dp(6)) }
            top.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, dp(30), dp(30))
            top.addView(TextView(this).apply {
                text = "Advance Play"; setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            tUsb.setPadding(dp(12), dp(6), dp(12), dp(6))
            tUsb.background = shape(0xFF161616.toInt(), 0xFF2E2E2E.toInt(), 1, 16)
            top.addView(tUsb)
            content.addView(top, MATCH, WRAP)
            content.addView(swipe, LinearLayout.LayoutParams(MATCH, 0, 1f))
            content.addView(panel, panelLp)
            content.addView(navBar(true), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
        }
        root.addView(content, MATCH, MATCH)

        // ----- settings (red) -----
        settings = ScrollView(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        val sl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (tv) 60 else 20), dp(if (tv) 40 else 28), dp(if (tv) 60 else 20), dp(20))
        }
        fun full(v: View) = sl.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(7), 0, dp(7)) })
        full(txt(26f, RED).apply { text = "Settings"; typeface = Typeface.DEFAULT_BOLD })
        sUsb = pill("", RED, RED) {
            try { pickTree.launch(null) } catch (e: ActivityNotFoundException) {
                toast("No folder picker on this device. The USB/SD app folder will be used instead.")
            }
        }
        sQ = pill("", RED, RED) { Prefs.setQuality(this, (Prefs.quality(this) + 1) % 3); refreshSettings() }
        sSpeed = pill("", RED, RED) {
            Throttle.maxSpeed = !Throttle.maxSpeed; Prefs.setMaxSpeed(this, Throttle.maxSpeed); refreshSettings()
        }
        sLayout = pill("", RED, RED) {
            Prefs.setLayout(this, (Prefs.layout(this) + 1) % 3); applyUa(); web.loadUrl(home()); refreshSettings()
        }
        sInfo = txt(14f, RED)
        full(sUsb); full(sQ); full(sSpeed); full(sLayout); full(sInfo)
        full(pill("Backup now", RED, RED) {
            Thread {
                val ok = Backup.save(this)
                runOnUiThread { toast(if (ok) "Backup saved next to your videos" else "Backup failed. Choose a download folder first.") }
            }.start()
        })
        full(pill("Reset network counters", RED, RED) { Net.session = 0; Prefs.resetTotal(this); refreshInfo() })
        full(pill("Close") { settings.visibility = View.GONE; web.requestFocus() })
        full(txt(12f, Color.WHITE).apply { text = "Advance Play\nCreated By Moin Studioz"; alpha = .7f })
        settings.addView(sl)
        root.addView(settings, MATCH, MATCH)

        // ----- download complete card -----
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22)); visibility = View.GONE; elevation = dp(8).toFloat()
        }
        cardT = txt(24f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        cardS = txt(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        card.addView(cardT); card.addView(cardS)
        root.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // ----- jet-black Background Mode (always on top) -----
        black = View(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; keepScreenOn = true
            isClickable = true; isLongClickable = true; isFocusable = true
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
        black.visibility = View.VISIBLE; black.requestFocus(); hideBars(true)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun exitBlack() {
        black.visibility = View.GONE; if (custom == null) hideBars(false)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        web.requestFocus()
    }

    // ---------- settings ----------
    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    private fun refreshSettings() {
        val name = Prefs.tree(this)?.let { DocumentFile.fromTreeUri(this, it)?.name }
        sUsb.text = (if (tv) "USB folder: " else "Download folder (SD card): ") + (name ?: "tap to choose")
        sQ.text = "Download quality: " + qNames[Prefs.quality(this)]
        sSpeed.text = "Download speed: " + (if (Throttle.maxSpeed) "MAXIMUM always" else "Maximum, but 80/20 while a video plays")
        sLayout.text = "YouTube layout: " + lNames[Prefs.layout(this)]
        refreshInfo()
    }

    private fun refreshInfo() {
        sInfo.text = "Playing now: " + (if (Throttle.streaming) "YES" else "no") + "\n" +
            "Link speed estimate: " + spd(Throttle.capacity(this)) + "\n" +
            "Network this session: " + fmt(Net.session) + "\n" +
            "Network all-time: " + fmt(Prefs.total(this))
    }

    // ---------- downloads ----------
    private val idRx = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")
    private fun videoUrl(u: String): String? =
        if (!u.contains("youtube.com") && !u.contains("youtu.be")) null
        else idRx.find(u)?.groupValues?.get(1)?.let { "https://www.youtube.com/watch?v=$it" }

    private fun enqueue(u: String) {
        val v = videoUrl(u) ?: run { toast("That is not a video link"); return }
        UsbState.err?.let { toast(it); openSettings(); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java).putExtra("url", v))
            toast("Added to download queue")
        } catch (e: Exception) { toast("Cannot start download: " + e.message) }
    }

    private fun downloadCurrent() {
        val u = web.url ?: ""
        if (videoUrl(u) != null) enqueue(u) else toast("Open a video first, then press Download")
    }

    /** Touch long-press on a video thumbnail (normal YouTube layout). */
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

    // ---------- TV remote ----------
    private fun openMenu() {
        if (!tv) { openSettings(); return }
        menuUntil = SystemClock.elapsedRealtime() + 10000
        rail?.visibility = View.VISIBLE; panelView?.visibility = View.VISIBLE
        rail?.getChildAt(0)?.requestFocus()
    }
    private fun toggleMenu() { if (rail?.hasFocus() == true) web.requestFocus() else openMenu() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (black.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (!down) exitBlack(); return true }
            if (k in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) return true
            return super.dispatchKeyEvent(e)
        }
        when (k) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE -> { if (!down) toggleMenu(); return true }
            KeyEvent.KEYCODE_PROG_RED -> { if (!down) openSettings(); return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { if (!down) startActivity(Intent(this, LibraryActivity::class.java)); return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { if (!down) enterBlack(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (!down) downloadCurrent(); return true }
        }
        // TV: hold BACK = open menu, short BACK = normal back
        if (tv && k == KeyEvent.KEYCODE_BACK && settings.visibility != View.VISIBLE && rail?.hasFocus() != true) {
            if (down) { if (e.repeatCount == 1 && !backHeld) { backHeld = true; openMenu() }; return true }
            if (backHeld) { backHeld = false; return true }
            onBackPressedDispatcher.onBackPressed(); return true
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
        card.background = shape(0xFF161616.toInt(), if (err) RED else Color.WHITE, 2, 28)
        card.visibility = View.VISIBLE
        h.removeCallbacks(hideCard); h.postDelayed(hideCard, 5000)
    }

    private val tick = object : Runnable {
        override fun run() {
            Net.tick(this@MainActivity)
            val limited = Throttle.streaming && !Throttle.maxSpeed
            val st = StringBuilder()
            if (DL.active) {
                st.append("\u2B07 ").append(DL.title.take(50)).append(" \u00B7 ").append(DL.pct).append("% \u00B7 ").append(spd(DL.speed))
                if (DL.queue > 1) st.append(" \u00B7 +").append(DL.queue - 1).append(" queued")
                if (limited) st.append(" \u00B7 limited to 20%")
            } else if (DL.lastDone.isNotEmpty()) st.append("\u2714 Saved: ").append(DL.lastDone.take(50))
            else st.append(if (tv) "Hold BACK or press MENU for the menu" else "Idle \u00B7 long-press a video to download")
            tStatus.text = st
            bar.progress = if (DL.active) DL.pct else 0
            UsbState.refresh(this@MainActivity)
            val u = UsbState.space
            val tag = if (tv) "USB" else "SD / Storage"
            tUsb.text = if (u == null) "$tag \u2022 not connected" else "$tag \u2022 ${fmt(u.first)} free"
            tNet.text = if (!Net.supported) "Network usage unavailable" else
                "Network \u2193 ${spd(Net.speed)} \u00B7 Session ${fmt(Net.session)} \u00B7 Total ${fmt(Prefs.total(this@MainActivity))}" +
                    (if (limited) "\nStreaming video 80% \u00B7 Downloads 20%" else "")
            if (DL.doneSeq != seenDone) { seenDone = DL.doneSeq; showCard("\u2714 Download Complete", DL.lastDone + "\nSaved to " + (if (tv) "USB" else "your SD card / folder"), false) }
            if (DL.errSeq != seenErr) { seenErr = DL.errSeq; showCard("Download failed", DL.errMsg, true) }
            if (tv) {   // while a video is playing, hide the menu so the video uses the whole screen
                val watching = web.url?.contains("watch") == true
                val vis = if (!watching || SystemClock.elapsedRealtime() < menuUntil || rail?.hasFocus() == true) View.VISIBLE else View.GONE
                if (rail?.visibility != vis) { rail?.visibility = vis; panelView?.visibility = vis }
            }
            if (++ticks % 600 == 0) Thread { Backup.save(this@MainActivity) }.start()
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
