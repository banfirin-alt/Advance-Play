package com.moinstudioz.advanceplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A web page that opens inside the app. [canDownload] = show the "Download" button (Facebook, Instagram). [accent] = its colour. */
class WebSite(val id: String, val label: String, val url: String, val canDownload: Boolean, val accent: Int)

object WebSites {
    val all = listOf(
        WebSite("facebook", "Facebook", "https://m.facebook.com/", true, 0xFF1877F2.toInt()),
        WebSite("instagram", "Instagram", "https://www.instagram.com/", true, 0xFFE1306C.toInt()),
        WebSite("chatgpt", "ChatGPT", "https://chatgpt.com/", false, 0xFF10A37F.toInt()),
        WebSite("claude", "Claude", "https://claude.ai/", false, 0xFFD97757.toInt())
    )

    fun find(id: String?): WebSite? = all.firstOrNull { it.id == id }

    fun open(c: Context, id: String) { c.startActivity(Intent(c, WebAppActivity::class.java).putExtra("site", id)) }

    /** The "Apps" list: Facebook, Instagram, ChatGPT, Claude, the Watched list (and the YouTube web page on the TV rail). */
    fun showMenu(a: Activity, withYouTube: Boolean) {
        val names = ArrayList<String>()
        all.forEach { names.add(it.label) }
        names.add("Watched videos (YouTube)")
        if (withYouTube) names.add("YouTube web page (sign-in)")
        AlertDialog.Builder(a).setTitle("Apps").setItems(names.toTypedArray()) { _, i ->
            when {
                i < all.size -> open(a, all[i].id)
                i == all.size -> a.startActivity(Intent(a, WatchedActivity::class.java))
                else -> a.startActivity(Intent(a, MainActivity::class.java))
            }
        }.show()
    }
}

/**
 * Facebook, Instagram, ChatGPT and Claude inside the app, behaving like a normal phone browser:
 * it looks like Chrome to the sites, supports pop-up login windows and photo upload, keeps you signed in,
 * and can fill in ONE saved sign-in (email + password, stored encrypted on this device) on all four login pages.
 * Facebook and Instagram have a "Download" button (best effort: it finds the video file of the open video).
 * TV remote: MENU moves to the button bar, BACK goes back.
 */
class WebAppActivity : ComponentActivity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var site: WebSite
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errBox: LinearLayout
    private lateinit var errText: TextView
    private lateinit var popupLayer: FrameLayout
    private lateinit var dlPill: TextView
    private val chips = HashMap<String, TextView>()
    private var popup: WebView? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val sniffed = ArrayList<String>()          // video file links seen while the page loaded
    private val h = Handler(Looper.getMainLooper())
    private var seenDone = 0
    private var seenErr = 0
    private var msgUntil = 0L

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    /** A short message at the bottom of the screen (stays about 9 seconds). */
    private fun showMsg(t: String) {
        status.text = t; status.visibility = View.VISIBLE
        msgUntil = System.currentTimeMillis() + 9000
    }

    private fun sh(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(fill); setStroke(dp(w), stroke) }

    private fun pillBg(accent: Int, filled: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), sh(if (filled) accent else 0xFF262626.toInt(), Color.WHITE, 3))
        addState(intArrayOf(android.R.attr.state_pressed), sh(0xFF333333.toInt(), accent, 2))
        addState(intArrayOf(), if (filled) sh(accent, accent, 1) else sh(0xFF161616.toInt(), 0xFF3A3A3A.toInt(), 1))
    }

    private fun pill(label: String, accent: Int = 0xFF555555.toInt(), filled: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(9), dp(14), dp(9)); isFocusable = true; isClickable = true
        background = pillBg(accent, filled)
        setOnClickListener { click() }
    }

    /** Make the page look like Chrome to the sites: the WebView marker in the user-agent is removed. */
    private fun chromeUa(def: String): String {
        var u = def.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        if (!u.contains("Mobile")) u = u.replace("Safari/", "Mobile Safari/")
        return u
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView, main: Boolean) {
        w.setBackgroundColor(Color.BLACK)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true                       // keeps the sign-in
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(main)                // pop-up login windows ("Continue with Google / Facebook")
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = false
        s.userAgentString = chromeUa(s.userAgentString)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        try {
            // do not tell the sites which app is asking (Chrome does not send it either)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        } catch (_: Exception) {}
    }

    private val mainClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val s = r.url.scheme
            return s != "http" && s != "https"              // ignore "open the app" links (fb://, intent://)
        }
        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) {
            errBox.visibility = View.GONE
            if (site.canDownload) v.evaluateJavascript(hookJs, null)
        }
        override fun onPageFinished(v: WebView, url: String?) {
            CookieManager.getInstance().flush()
            if (site.canDownload) v.evaluateJavascript(hookJs, null)      // watches the page's own data requests for video links
            if (Vault.has(this@WebAppActivity)) h.postDelayed({ fillLogin(true) }, 900)   // fills a login page, never presses Log in
        }
        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (r.isForMainFrame) {
                errText.text = "Cannot open this page.\n" + e.description + "\nCheck the internet connection, then press Retry."
                errBox.visibility = View.VISIBLE
            }
        }
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            if (site.canDownload) {
                val u = r.url.toString()
                // a whole video file (not one of the small DASH pieces)
                if (u.contains(".mp4") && (u.contains("fbcdn") || u.contains("cdninstagram")) && !u.contains("bytestart"))
                    synchronized(sniffed) { sniffed.remove(u); sniffed.add(u); while (sniffed.size > 6) sniffed.removeAt(0) }
            }
            return null
        }
        override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean { runOnUiThread { recreate() }; return true }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            progress.progress = p
            progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }
        override fun onShowCustomView(v: View, cb: WebChromeClient.CustomViewCallback) {
            if (customView != null) { cb.onCustomViewHidden(); return }
            customView = v; customCb = cb
            root.addView(v, MATCH, MATCH); hideBars(true)
        }
        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCb?.onCustomViewHidden(); customCb = null
            hideBars(false)
        }
        override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null); fileCb = cb
            return try { pick.launch(p.createIntent()); true } catch (e: Exception) { fileCb = null; cb.onReceiveValue(null); false }
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            closePopup()
            val pop = WebView(this@WebAppActivity)
            configure(pop, false)
            pop.webViewClient = mainClient
            pop.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(window: WebView) { closePopup() } }
            popup = pop
            popupLayer.addView(pop, MATCH, MATCH); popupLayer.visibility = View.VISIBLE; pop.requestFocus()
            val t = resultMsg.obj as WebView.WebViewTransport
            t.webView = pop; resultMsg.sendToTarget()
            return true
        }
    }

    private fun closePopup() {
        popup?.let { p -> popupLayer.removeView(p); p.destroy() }
        popup = null
        popupLayer.visibility = View.GONE
        if (::web.isInitialized) web.requestFocus()
    }

    private fun hideBars(on: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun styleChips() { for (s in WebSites.all) chips[s.id]?.background = pillBg(s.accent, s.id == site.id) }

    /** Switch to another site (the sign-ins of all sites stay, they share the same cookie jar). */
    private fun selectSite(s: WebSite) {
        site = s
        styleChips()
        dlPill.visibility = if (s.canDownload) View.VISIBLE else View.GONE
        progress.progressTintList = ColorStateList.valueOf(s.accent)
        errBox.visibility = View.GONE
        web.loadUrl(s.url)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        site = WebSites.find(intent.getStringExtra("site")) ?: WebSites.all[0]
        seenDone = DL.doneSeq; seenErr = DL.errSeq

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val extra = if (Dev.isTv(this)) dp(20) else 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.setPadding(i.left + extra, i.top + extra / 2, i.right + extra, i.bottom + extra / 2); ins
        }

        // top bar: back, reload, the four sites, download, sign-in, close (scrolls sideways when it does not fit)
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(0xFF0B0B0B.toInt()) }
        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(7), dp(8), dp(7)) }
        scroller.addView(bar, ViewGroup.LayoutParams(WRAP, WRAP))
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), 0) })
        add(pill("\u25C0") { if (popup != null) closePopup() else if (web.canGoBack()) web.goBack() else finish() })
        add(pill("\u21BB") { errBox.visibility = View.GONE; web.reload() })
        for (s in WebSites.all) {
            val chip = pill(s.label, s.accent, s.id == site.id) { if (s.id != site.id) selectSite(s) }
            chips[s.id] = chip
            add(chip)
        }
        dlPill = pill("\u2B07 Download", 0xFF1DB954.toInt(), true) { grabVideo() }
        dlPill.visibility = if (site.canDownload) View.VISIBLE else View.GONE
        add(dlPill)
        add(pill("Sign-in") { signInDialog() })
        add(pill("\u2715") { finish() })
        col.addView(scroller, MATCH, WRAP)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE; progressTintList = ColorStateList.valueOf(site.accent)
        }
        col.addView(progress, MATCH, dp(3))

        web = WebView(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        configure(web, true)
        web.webViewClient = mainClient
        web.webChromeClient = chrome
        col.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(col, MATCH, MATCH)

        // bottom message strip (download progress and results)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; maxLines = 3; gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xEE111111.toInt()); setStroke(dp(1), 0xFF444444.toInt()) }
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(16), 0, dp(16), dp(40)) })

        // "no internet" box with a Retry button
        errText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(14)) }
        errBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xF0151515.toInt()); setStroke(dp(1), 0xFF444444.toInt()) }
            addView(errText, WRAP, WRAP)
            addView(pill("Retry", 0xFF1877F2.toInt(), true) { errBox.visibility = View.GONE; web.reload() }, WRAP, WRAP)
        }
        root.addView(errBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })

        popupLayer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(popupLayer, MATCH, MATCH)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome.onHideCustomView()
                    popup != null -> closePopup()
                    bar.hasFocus() -> web.requestFocus()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
        web.loadUrl(site.url)
        web.requestFocus()
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (DL.doneSeq != seenDone) { seenDone = DL.doneSeq; showMsg("\u2714 Download complete: " + DL.lastDone.take(40)) }
            if (DL.errSeq != seenErr) { seenErr = DL.errSeq; showMsg("Download failed: " + DL.errMsg.take(90)) }
            if (System.currentTimeMillis() > msgUntil) {
                if (DL.active) { status.text = "\u2B07 " + DL.title.take(30) + "  " + DL.pct + "%"; status.visibility = View.VISIBLE }
                else status.visibility = View.GONE
            }
            h.postDelayed(this, 1000)
        }
    }

    // ---------- saved sign-in (one email + password for all four sites) ----------

    private val fillBody = """
(function(u,p){
  function setv(el,v){ try{ var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value'); if(d&&d.set) d.set.call(el,v); else el.value=v; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){ el.value=v; } }
  var n=0, ins=document.querySelectorAll('input');
  for(var i=0;i<ins.length;i++){
    var e=ins[i]; if(!e.offsetWidth||e.disabled||e.readOnly) continue;
    var t=(e.type||'text').toLowerCase();
    if(t==='password'){ if(p && !e.value){ setv(e,p); n++; } }
    else if(t==='text'||t==='email'||t==='tel'){
      var k=((e.name||'')+' '+(e.id||'')+' '+(e.autocomplete||'')+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();
      if(u && !e.value && /email|e-mail|login|username|user_name|identifier|phone|mobile/.test(k)){ setv(e,u); n++; }
    }
  }
  return n;
})
"""

    /** Fills the saved email / password into the login boxes of the open page. It never presses the Log in button. */
    private fun fillLogin(quiet: Boolean) {
        val u = Vault.user(this)
        val p = Vault.pass(this)
        if (u.isEmpty() && p.isEmpty()) { if (!quiet) showMsg("No saved sign-in yet. Tap Sign-in to add one."); return }
        val js = fillBody.trim() + "(" + JSONObject.quote(u) + "," + JSONObject.quote(p) + ")"
        (popup ?: web).evaluateJavascript(js) { r ->
            if (!quiet) showMsg(if ((r ?: "0").trim() == "0") "No login box found on this page." else "Filled in. Now press the site's Log in button.")
        }
    }

    private fun signInDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val u = EditText(this).apply {
            hint = "Email, phone or username"; setText(Vault.user(this@WebAppActivity)); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val p = EditText(this).apply {
            hint = "Password"; setText(Vault.pass(this@WebAppActivity)); setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val note = TextView(this).apply {
            setTextColor(0xFF9A9A9A.toInt()); textSize = 12f; setPadding(0, dp(10), 0, 0)
            text = "Use the same email and password you chose on Facebook, Instagram, ChatGPT and Claude. It is saved only on this " +
                "device (encrypted, not in the USB backup). The app fills it in on the login pages; you press Log in yourself."
        }
        box.addView(u, MATCH, WRAP); box.addView(p, MATCH, WRAP); box.addView(note, MATCH, WRAP)
        AlertDialog.Builder(this).setTitle("Saved sign-in").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ok = Vault.save(this, u.text.toString().trim(), p.text.toString())
                showMsg(if (ok) "Saved on this device. Open a login page and it fills in by itself." else "This device cannot store it safely.")
                if (ok) fillLogin(true)
            }
            .setNeutralButton("Remove") { _, _ -> Vault.clear(this); showMsg("Saved sign-in removed.") }
            .setNegativeButton("Fill now") { _, _ -> fillLogin(false) }
            .show()
    }

    // ---------- Download button (Facebook / Instagram) ----------

    // Watches the page's own data requests (fetch / XHR) and remembers video file links found in the answers.
    private val hookJs = """
(function(){
  if(window.__apHook) return; window.__apHook=1; window.__apv=[];
  var re=/"(?:browser_native_hd_url|playable_url_quality_hd|hd_src|progressive_url|browser_native_sd_url|playable_url|sd_src|video_url)"\s*:\s*"([^"]+)"|"video_versions"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)"/g;
  function scan(t){ try{ var m; re.lastIndex=0; while((m=re.exec(t))){ var u=(m[1]||m[2]||'').replace(/\\\//g,'/').replace(/\\u0025/g,'%').replace(/\\u0026/g,'&'); if(u.indexOf('http')===0 && window.__apv.indexOf(u)<0) window.__apv.push(u); } }catch(e){} }
  var of=window.fetch;
  if(of){ window.fetch=function(){ var p=of.apply(this,arguments); try{ p.then(function(r){ try{ r.clone().text().then(scan); }catch(e){} }); }catch(e){} return p; }; }
  var os=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send=function(){ this.addEventListener('load',function(){ try{ if(typeof this.responseText==='string') scan(this.responseText); }catch(e){} }); return os.apply(this,arguments); };
})();
"""

    // looks for video file links inside the open page and returns them as JSON text
    private val findJs = """
(function(){
  var found=[];
  function clean(u){ try{ return u.replace(/\\\//g,'/').replace(/\\u0025/g,'%').replace(/\\u0026/g,'&').replace(/\\u003d/gi,'=').replace(/&amp;/g,'&'); }catch(e){ return u; } }
  if(window.__apv){ for(var h=window.__apv.length-1;h>=0;h--){ found.push(window.__apv[h]); } }
  var html=document.documentElement.innerHTML;
  var keys=['browser_native_hd_url','playable_url_quality_hd','hd_src','progressive_url','browser_native_sd_url','playable_url','sd_src','video_url'];
  for(var i=0;i<keys.length;i++){
    var re=new RegExp('"'+keys[i]+'"\\s*:\\s*"([^"]+)"','g'); var m;
    while((m=re.exec(html))){ var u=clean(m[1]); if(u.indexOf('http')===0 && found.indexOf(u)<0) found.push(u); }
  }
  var re2=/"video_versions"\s*:\s*\[\s*\{[^\]]*?"url"\s*:\s*"([^"]+)"/g; var m2;
  while((m2=re2.exec(html))){ var u2=clean(m2[1]); if(found.indexOf(u2)<0) found.push(u2); }
  var vs=document.querySelectorAll('video');
  for(var j=0;j<vs.length;j++){ var s=vs[j].currentSrc||vs[j].src; if(s && s.indexOf('http')===0 && found.indexOf(s)<0) found.push(s); }
  var ids=[]; var rx=/"(?:video_id|videoId)"\s*:\s*"?(\d{6,})"?/g; var mm;
  while((mm=rx.exec(html)) && ids.length<3){ if(ids.indexOf(mm[1])<0) ids.push(mm[1]); }
  var og=document.querySelector('meta[property="og:title"]');
  return JSON.stringify({urls:found,ids:ids,title:(og&&og.content)||document.title||'',page:location.href});
})()
"""

    private fun usable(u: String) = u.startsWith("http") && !u.contains("bytestart")

    private val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Turns a link written inside page data (\/ and \u0026 style) into a normal link. */
    private fun unescape(s: String): String {
        var t = s.replace("\\\\", "\\").replace("\\/", "/").replace("&amp;", "&")
        t = Regex("\\\\u([0-9a-fA-F]{4})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return t
    }

    /** All video file links inside a page's text, best quality first. */
    private fun extract(html0: String): List<String> {
        val html = html0.take(6_000_000).replace("\\\"", "\"")        // some pages keep their data inside a text string
        val out = ArrayList<String>()
        val keys = listOf("browser_native_hd_url", "playable_url_quality_hd", "hd_src_no_ratelimit", "hd_src", "progressive_url",
            "browser_native_sd_url", "playable_url", "sd_src_no_ratelimit", "sd_src", "video_url")
        for (k in keys) {
            val re = Regex("\"" + k + "\"\\s*:\\s*\"([^\"]+)\"")
            for (m in re.findAll(html)) {
                val u = unescape(m.groupValues[1])
                if (u.startsWith("http") && !out.contains(u)) out.add(u)
            }
        }
        val iv = Regex("\"video_versions\"\\s*:\\s*\\[\\s*\\{[^\\]]*?\"url\"\\s*:\\s*\"([^\"]+)\"")
        for (m in iv.findAll(html)) {
            val u = unescape(m.groupValues[1])
            if (u.startsWith("http") && !out.contains(u)) out.add(u)
        }
        return out
    }

    /** Downloads a web page as text, signed in with the same cookies as the app's web view. Null when refused. */
    private fun fetchPage(url: String, extra: Map<String, String>): String? {
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", desktopUa)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrEmpty()) b.header("Cookie", cookie)
        for ((k, v) in extra) b.header(k, v)
        return Http.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    /**
     * The address of the open page tells which video it is. Ask the website itself for exactly that video
     * (like other download tools do). [notes] collects what happened, to show if nothing is found.
     */
    private fun findByAddress(page: String, ids: List<String>, notes: StringBuilder): String? {
        val none = emptyMap<String, String>()
        val tries = ArrayList<Pair<String, Map<String, String>>>()
        if (site.id == "facebook") {
            val id = Regex("(?:/videos/|/reel/|[?&]v=|video_id=)(\\d{6,})").find(page)?.groupValues?.get(1) ?: ids.firstOrNull()
            if (id != null) {
                tries.add(Pair("https://www.facebook.com/watch/?v=$id", none))
                tries.add(Pair("https://www.facebook.com/reel/$id", none))
                tries.add(Pair("https://www.facebook.com/video.php?v=$id", none))
            }
        } else if (site.id == "instagram") {
            val code = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})").find(page)?.groupValues?.get(1)
            if (code != null) {
                tries.add(Pair("https://www.instagram.com/p/$code/embed/captioned/", none))
                tries.add(Pair("https://www.instagram.com/reel/$code/", none))
                tries.add(Pair("https://www.instagram.com/p/$code/?__a=1&__d=dis",
                    mapOf("X-IG-App-ID" to "936619743392459", "X-Requested-With" to "XMLHttpRequest")))
            }
        }
        if (tries.isEmpty()) { notes.append("this page is not a single video (open one video first). "); return null }
        for ((u, hdr) in tries) {
            val html = try { fetchPage(u, hdr) } catch (e: Exception) { null }
            if (html == null) { notes.append("refused: ").append(u.substringAfter("//").take(28)).append(". "); continue }
            val found = extract(html).firstOrNull { usable(it) }
            if (found != null) return found
            notes.append("no link in: ").append(u.substringAfter("//").take(28)).append(". ")
        }
        return null
    }

    /** "Download video": finds the video file for the open page and queues it (the same video cannot be queued twice). */
    private fun grabVideo() {
        if (!site.canDownload) return
        showMsg("Looking for the video on this page...")
        web.evaluateJavascript(findJs) { res ->
            val onPage = ArrayList<String>()
            val ids = ArrayList<String>()
            var title = ""
            var page = web.url ?: site.url
            try {
                val v = JSONTokener(res).nextValue()
                if (v is String) {
                    val o = JSONObject(v)
                    val a = o.optJSONArray("urls")
                    if (a != null) for (i in 0 until a.length()) onPage.add(a.optString(i))
                    val d = o.optJSONArray("ids")
                    if (d != null) for (i in 0 until d.length()) ids.add(d.optString(i))
                    title = o.optString("title")
                    page = o.optString("page", page)
                }
            } catch (_: Exception) {}
            val net = synchronized(sniffed) { sniffed.filter { usable(it) }.reversed() }
            Thread { searchAndQueue(page, title, onPage, ids, net) }.start()
        }
    }

    /** Runs in the background: 1) ask the website for this exact video, 2) links inside the open page, 3) files the page loaded. */
    private fun searchAndQueue(page: String, title: String, onPage: List<String>, ids: List<String>, net: List<String>) {
        val notes = StringBuilder()
        var media: String? = null
        try { media = findByAddress(page, ids, notes) } catch (e: Exception) { notes.append("error: ").append(e.javaClass.simpleName).append(". ") }
        if (media == null) media = onPage.firstOrNull { usable(it) }
        if (media == null) media = net.firstOrNull()
        val m = media
        val msg: String = if (m == null) {
            "No video found. " + notes.toString().take(150) + " Open the single video (not the feed), let it play a few seconds, then press Download again."
        } else {
            val stamp = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            val t = title.trim().replace(Regex("\\s+"), " ").take(70).ifBlank { "video" }
            val name = "$t [${site.label} $stamp]"
            Dl.enqueueDirect(this, "web:" + m.substringBefore('?'), m, name) ?: "Added to download queue"
        }
        runOnUiThread { showMsg(msg) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE -> {
                if (e.action == KeyEvent.ACTION_UP) { if (bar.hasFocus()) web.requestFocus() else bar.getChildAt(0).requestFocus() }
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (e.action == KeyEvent.ACTION_UP) grabVideo(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() { CookieManager.getInstance().flush(); web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { closePopup() } catch (_: Exception) {}
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
