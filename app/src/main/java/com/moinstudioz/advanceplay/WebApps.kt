package com.moinstudioz.advanceplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A web page that opens inside the app. [canDownload] = show the "Download video" button (Facebook, Instagram). */
class WebSite(val id: String, val label: String, val url: String, val canDownload: Boolean)

object WebSites {
    val all = listOf(
        WebSite("facebook", "Facebook", "https://m.facebook.com/", true),
        WebSite("instagram", "Instagram", "https://www.instagram.com/", true),
        WebSite("chatgpt", "ChatGPT", "https://chatgpt.com/", false),
        WebSite("claude", "Claude", "https://claude.ai/", false)
    )

    fun find(id: String?): WebSite? = all.firstOrNull { it.id == id }

    fun open(c: Context, id: String) { c.startActivity(Intent(c, WebAppActivity::class.java).putExtra("site", id)) }

    /** The "Apps" list: Facebook, Instagram, ChatGPT, Claude, the Watched list (and YouTube Web on the TV). */
    fun showMenu(a: Activity, withYouTube: Boolean) {
        val names = ArrayList<String>()
        all.forEach { names.add(it.label) }
        names.add("Watched videos (YouTube)")
        if (withYouTube) names.add("YouTube Web")
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
 * Facebook, Instagram, ChatGPT and Claude inside the app. The WebView keeps its cookies, so you sign in once and stay signed in.
 * Facebook and Instagram have a "Download video" button (best effort: it looks for the video file link on the open page).
 * TV remote: MENU (or long-press OK on a link-free spot) moves to the button bar; BACK goes back.
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
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private val sniffed = ArrayList<String>()          // video file links seen while the page loaded
    private val h = Handler(Looper.getMainLooper())
    private var seenDone = 0
    private var seenErr = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun pillBg(): StateListDrawable {
        fun sh(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(fill); setStroke(dp(w), stroke) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), sh(0xFF262626.toInt(), Color.WHITE, 3))
            addState(intArrayOf(android.R.attr.state_pressed), sh(0xFF262626.toInt(), 0xFF777777.toInt(), 1))
            addState(intArrayOf(), sh(0xFF161616.toInt(), 0xFF444444.toInt(), 1))
        }
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8)); isFocusable = true; isClickable = true; background = pillBg()
        setOnClickListener { click() }
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
    }

    private fun hideBars(on: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        site = WebSites.find(intent.getStringExtra("site")) ?: WebSites.all[0]
        seenDone = DL.doneSeq; seenErr = DL.errSeq

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.setPadding(i.left, i.top, i.right, i.bottom); ins
        }

        // button bar (top)
        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(0xFF0B0B0B.toInt()) }
        bar.addView(TextView(this).apply { text = site.label; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), 0, dp(12), 0) })
        bar.addView(pill("\u25C0 Back") { if (web.canGoBack()) web.goBack() else finish() })
        bar.addView(pill("\u21BB Reload") { web.reload() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
        if (site.canDownload) bar.addView(pill("\u2B07 Download video") { grabVideo() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
        bar.addView(pill("\u2715 Close") { finish() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), 0, 0, 0) })
        status = TextView(this).apply { setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; maxLines = 1; gravity = Gravity.END; setPadding(dp(10), 0, dp(6), 0) }
        bar.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(bar, MATCH, WRAP)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        col.addView(progress, MATCH, dp(3))

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true                  // keeps the sign-in
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            // a normal phone-browser identity (the default WebView one is refused by some login pages)
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = chrome
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme
                    return s != "http" && s != "https"          // ignore "open the app" links (fb://, intent://)
                }
                override fun onPageFinished(v: WebView, url: String?) { CookieManager.getInstance().flush() }
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
        }
        col.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(col, MATCH, MATCH)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome.onHideCustomView()
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
            status.text = if (DL.active) "\u2B07 " + DL.title.take(28) + "  " + DL.pct + "%"
                          else if (DL.lastDone.isNotEmpty()) "\u2714 " + DL.lastDone.take(28) else ""
            if (DL.doneSeq != seenDone) { seenDone = DL.doneSeq; toast("Download complete") }
            if (DL.errSeq != seenErr) { seenErr = DL.errSeq; toast("Download failed: " + DL.errMsg.take(90)) }
            h.postDelayed(this, 1000)
        }
    }

    // looks for the video file link inside the open page and returns it as JSON text
    private val findJs = """
(function(){
  var found=[];
  function clean(u){ try{ return u.replace(/\\\//g,'/').replace(/\\u0025/g,'%').replace(/\\u0026/g,'&').replace(/\\u003d/gi,'=').replace(/&amp;/g,'&'); }catch(e){ return u; } }
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
  var og=document.querySelector('meta[property="og:title"]');
  return JSON.stringify({urls:found,title:(og&&og.content)||document.title||'',page:location.href});
})()
"""

    private fun usable(u: String) = u.startsWith("http") && !u.contains("bytestart")

    /** "Download video": find the video file on the open page and queue it (the same video cannot be queued twice). */
    private fun grabVideo() {
        if (!site.canDownload) return
        toast("Looking for the video on this page...")
        web.evaluateJavascript(findJs) { res ->
            var media: String? = null
            var title = ""
            try {
                val v = JSONTokener(res).nextValue()
                if (v is String) {
                    val o = JSONObject(v)
                    val a = o.optJSONArray("urls")
                    if (a != null) for (i in 0 until a.length()) { val u = a.optString(i); if (usable(u)) { media = u; break } }
                    title = o.optString("title")
                }
            } catch (_: Exception) {}
            if (media == null) synchronized(sniffed) { media = sniffed.lastOrNull { usable(it) } }
            val m = media
            if (m == null) {
                toast("No video found. Open the video, let it start playing, then press Download again.")
            } else {
                val stamp = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
                val t = title.trim().replace(Regex("\\s+"), " ").take(70).ifBlank { "video" }
                val name = "$t [${site.label} $stamp]"
                val err = Dl.enqueueDirect(this, "web:" + m.substringBefore('?'), m, name)
                toast(err ?: "Added to download queue")
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE -> {
                if (e.action == KeyEvent.ACTION_UP) { if (bar.hasFocus()) web.requestFocus() else bar.getChildAt(1).requestFocus() }
                return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (e.action == KeyEvent.ACTION_UP) grabVideo(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() { CookieManager.getInstance().flush(); web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
