package com.moinstudioz.advanceplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The video files found for a web link: picture file, sound file (or null when the picture file already has sound), and a title. */
class SniffResult(
    val video: String, val audio: String?, val title: String,
    val files: List<String> = emptyList(),     // other sites: video file links found on the page
    val hls: List<String> = emptyList(),       // other sites: stream playlists (.m3u8) found on the page
    val page: String = ""                      // the page they came from (sent as Referer)
)

/** The browser identity used for Facebook / Instagram pages (a TV is shown as an ordinary Android phone). */
object BrowserUa {
    fun make(c: Context, def: String): String {
        if (Dev.isTv(c)) {
            val ver = Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(def)?.groupValues?.get(1) ?: "124.0.0.0"
            return "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + ver + " Mobile Safari/537.36"
        }
        var u = def.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        if (!u.contains("Mobile")) u = u.replace("Safari/", "Mobile Safari/")
        return u
    }
}

/**
 * Remembers the video files a page plays (Facebook / Instagram keep picture and sound in separate files,
 * each requested in small pieces). Feed it every web request; ask for the best files with [pick].
 */
class StreamCatcher {
    class Pick(val video: String, val audio: String?, val complete: Boolean)
    private class Cap(val url: String, val kind: Int, val q: Int, val at: Long)   // kind: 0 whole file, 1 picture only, 2 sound only
    private val caps = LinkedHashMap<String, Cap>()
    private val gen = LinkedHashMap<String, Int>()      // other sites: link -> 0 video file, 1 stream playlist

    private fun efgText(u: String): String {
        val e = Uri.parse(u).getQueryParameter("efg") ?: return ""
        return try { String(Base64.decode(e, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8) }
        catch (_: Exception) { try { String(Base64.decode(e, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" } }
    }

    /** The same link without the "give me only this piece" parameters, so it returns the whole picture or sound file. */
    private fun whole(u: String): String {
        val uri = Uri.parse(u)
        val b = uri.buildUpon().clearQuery()
        for (n in uri.queryParameterNames) if (n != "bytestart" && n != "byteend") for (v in uri.getQueryParameters(n)) b.appendQueryParameter(n, v)
        return b.build().toString()
    }

    /** Every web request of the page goes through here. */
    fun onRequest(u: String) {
        if (u.contains("fbcdn") || u.contains("cdninstagram")) fbRequest(u) else genericRequest(u)
    }

    private fun addGeneric(u: String, kind: Int) {
        synchronized(gen) { gen.remove(u); gen[u] = kind; while (gen.size > 30) gen.remove(gen.keys.first()) }
    }

    /** Any other website: video files (.mp4 .webm ...) and stream playlists (.m3u8). */
    private fun genericRequest(u: String) {
        val p = u.substringBefore('?').substringBefore('#').lowercase()
        val kind = when {
            p.endsWith(".m3u8") -> 1
            p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".mkv") || p.endsWith(".mov") || p.endsWith(".m4v") -> 0
            else -> return
        }
        if (u.contains("bytestart") || u.contains("doubleclick") || u.contains("googlesyndication") ||
            p.contains("/ads/") || p.contains("preview") || p.contains("thumb") || p.contains("sprite")) return
        addGeneric(u, kind)
    }

    /** A video link read from the page's video element. */
    fun addDirect(u: String) {
        if (!u.startsWith("http")) return
        addGeneric(u, if (u.substringBefore('?').lowercase().endsWith(".m3u8")) 1 else 0)
    }

    fun hasGeneric(): Boolean = synchronized(gen) { gen.isNotEmpty() }

    /** (video files, playlists) found on other sites, oldest first. */
    fun generic(): Pair<List<String>, List<String>> {
        val l = synchronized(gen) { gen.entries.toList() }
        return Pair(l.filter { it.value == 0 }.map { it.key }, l.filter { it.value == 1 }.map { it.key })
    }

    private fun fbRequest(u: String) {
        if (!u.contains(".mp4") || !(u.contains("fbcdn") || u.contains("cdninstagram"))) return
        val tag = efgText(u).lowercase()
        val kind = when {
            tag.contains("audio") -> 2
            tag.contains("progressive") -> 0
            tag.contains("dash") -> 1
            u.contains("bytestart") -> return          // a piece of a stream and we cannot tell what it is
            else -> 0
        }
        val q = Regex("(\\d{3,4})p").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val clean = whole(u)
        synchronized(caps) {
            caps.remove(clean)
            caps[clean] = Cap(clean, kind, q, System.currentTimeMillis())
            while (caps.size > 40) caps.remove(caps.keys.first())
        }
    }

    /** Best files so far. complete = picture and sound are both there (or one file has both). */
    fun pick(): Pick? {
        val list = synchronized(caps) { caps.values.toList() }
        val v = list.filter { it.kind == 1 }.maxWithOrNull(compareBy<Cap> { it.q }.thenBy { it.at })
        val a = list.lastOrNull { it.kind == 2 }
        val w = list.lastOrNull { it.kind == 0 }
        return when {
            v != null && a != null -> Pick(v.url, a.url, true)
            w != null -> Pick(w.url, null, true)
            v != null -> Pick(v.url, null, false)
            else -> null
        }
    }
}

/**
 * Opens a Facebook / Instagram video link in a hidden browser inside the app (signed in like the app's own web screen),
 * lets it play for a moment and catches the video files it loads. Must be started on the main thread.
 * [done] is called on the main thread with the files, or null when nothing was found within 30 seconds.
 */
class LinkSniffer(private val act: Activity, private val host: FrameLayout, private val done: (SniffResult?) -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private val catcher = StreamCatcher()
    private var web: WebView? = null
    private var finished = false
    private var delivered = false
    private var started = 0L

    // keeps trying to start the video (muted) so that the page loads its video files
    private val playJs = "(function(){var n=0;var t=setInterval(function(){try{var v=document.querySelector('video');" +
        "if(v){v.muted=true;if(v.paused){v.play();}}}catch(e){} if(++n>50)clearInterval(t);},500);})();"

    // reads the video links of the page's video elements (some sites put the file link there)
    private val srcJs = "(function(){var o=[];var vs=document.querySelectorAll('video,source');for(var i=0;i<vs.length;i++){" +
        "var s=vs[i].currentSrc||vs[i].src;if(s&&s.indexOf('http')===0&&o.indexOf(s)<0)o.push(s);}return JSON.stringify(o);})()"

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            val waited = System.currentTimeMillis() - started
            web?.evaluateJavascript(srcJs) { r ->
                try {
                    val v = JSONTokener(r).nextValue()
                    if (v is String) { val a = JSONArray(v); for (i in 0 until a.length()) catcher.addDirect(a.optString(i)) }
                } catch (_: Exception) {}
            }
            val p = catcher.pick()
            if (p != null && (p.complete || waited > 30000)) { finish(p, null); return }
            if (p == null && waited > 8000 && catcher.hasGeneric()) { finish(null, catcher.generic()); return }
            if (waited > 30000) { finish(null, if (catcher.hasGeneric()) catcher.generic() else null); return }
            h.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(link: String) {
        started = System.currentTimeMillis()
        val w = WebView(act)
        web = w
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = BrowserUa.make(act, s.userAgentString)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        } catch (_: Exception) {}
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        w.alpha = 0.02f                                   // not visible, but the page behaves as if it was on screen
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val sc = r.url.scheme
                return sc != "http" && sc != "https"
            }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                try { catcher.onRequest(r.url.toString()) } catch (_: Exception) {}
                return null
            }
            override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(playJs, null) }
        }
        host.addView(w, 0, FrameLayout.LayoutParams(960, 540))
        w.loadUrl(link)
        h.postDelayed(poll, 2000)
    }

    private fun deliver(r: SniffResult?) {
        if (delivered) return
        delivered = true
        cleanup()
        done(r)
    }

    private fun finish(p: StreamCatcher.Pick?, g: Pair<List<String>, List<String>>?) {
        if (finished) return
        finished = true
        val w = web
        val page = w?.url ?: ""
        val base: SniffResult? = when {
            p != null -> SniffResult(p.video, p.audio, "", emptyList(), emptyList(), page)
            g != null -> SniffResult("", null, "", g.first, g.second, page)
            else -> null
        }
        if (base == null || w == null) { deliver(null); return }
        // title of the video, if the page gives one
        h.postDelayed({ deliver(base) }, 2500)
        w.evaluateJavascript("(function(){var og=document.querySelector('meta[property=\"og:title\"]');return (og&&og.content)||document.title||'';})()") { r ->
            var t = ""
            try { val v = JSONTokener(r).nextValue(); if (v is String) t = v } catch (_: Exception) {}
            deliver(SniffResult(base.video, base.audio, t, base.files, base.hls, base.page))
        }
    }

    private fun cleanup() {
        h.removeCallbacksAndMessages(null)
        val w = web
        web = null
        if (w != null) { try { host.removeView(w); w.destroy() } catch (_: Exception) {} }
    }
}

/** Picks the best of the video links found on any website and queues the download. */
object Universal {
    class Choice(val url: String, val hls: Boolean)

    /** The size of a file link (0 when it is not a video file). Uses the network. */
    private fun sizeOf(u: String, referer: String): Long = try {
        val rb = okhttp3.Request.Builder().url(u).header("User-Agent", UA).header("Range", "bytes=0-0")
        if (referer.isNotEmpty()) rb.header("Referer", referer)
        Http.client.newCall(rb.build()).execute().use { r ->
            val ct = r.header("Content-Type") ?: ""
            if (!r.isSuccessful || ct.startsWith("text") || ct.contains("html") || ct.contains("json")) 0L
            else (r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.header("Content-Length")?.toLongOrNull() ?: 0L)
        }
    } catch (_: Exception) { 0L }

    /** Blocking (uses the network): the biggest video file, otherwise the best stream playlist. */
    fun choose(files: List<String>, hls: List<String>, referer: String): Choice? {
        var best: String? = null
        var bestSize = 0L
        for (u in files.distinct().takeLast(8)) { val sz = sizeOf(u, referer); if (sz > bestSize) { bestSize = sz; best = u } }
        if (best != null && bestSize >= 300_000) return Choice(best, false)
        val m = hls.lastOrNull { it.contains("master") } ?: hls.firstOrNull()
        if (m != null) return Choice(m, true)
        return best?.let { Choice(it, false) }
    }

    /** Blocking: queues the download for what the sniffer found. Returns a message for the screen. */
    fun enqueue(c: Context, link: String, r: SniffResult): String {
        val stamp = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
        val host = try { java.net.URI(link).host?.removePrefix("www.")?.substringBefore('.') ?: "" } catch (_: Exception) { "" }
        val label = host.replaceFirstChar { it.uppercase() }.ifBlank { "Web" }
        val title = r.title.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { label + " video" } + " [" + label + " " + stamp + "]"
        if (r.video.isNotEmpty())
            return Dl.enqueueDirect(c, "web:" + r.video.substringBefore('?'), r.video, title, r.audio, r.page) ?: "Added to the download queue"
        val ch = choose(r.files, r.hls, r.page) ?: return "No video found on this page. Open the video link itself, not a list of videos."
        return if (ch.hls) Dl.enqueueHls(c, "hls:" + ch.url.substringBefore('?'), ch.url, title, r.page) ?: "Added to the download queue (stream)"
        else Dl.enqueueDirect(c, "web:" + ch.url.substringBefore('?'), ch.url, title, null, r.page) ?: "Added to the download queue"
    }
}

/** "Download from link": paste a link of any video website; the app finds the video and downloads it. */
object LinkDownloader {
    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()

    fun ask(act: Activity) {
        val d = act.resources.displayMetrics.density
        val et = EditText(act).apply {
            hint = "Paste a video link"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFF888888.toInt())
        }
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
            if (t.startsWith("http")) et.setText(t)
        } catch (_: Exception) {}
        val box = LinearLayout(act).apply { setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(act).setTitle("Download from link")
            .setMessage("YouTube, Facebook, Instagram or any website with a video.")
            .setView(box)
            .setPositiveButton("Download") { _, _ -> run(act, et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    fun run(act: Activity, link0: String) {
        val link = link0.trim()
        if (!link.startsWith("http")) { toast(act, "That is not a web link"); return }
        if (Dl.canonical(link) != null) { toast(act, Dl.enqueue(act, link) ?: "Added to the download queue"); return }
        val path = link.substringBefore('?').substringBefore('#')
        if (listOf(".mp4", ".mkv", ".webm", ".m4v", ".mov").any { path.lowercase().endsWith(it) }) {
            val name = java.net.URLDecoder.decode(path.substringAfterLast('/').substringBeforeLast('.'), "UTF-8").take(70).ifBlank { "video" }
            toast(act, Dl.enqueueDirect(act, "web:" + path, link, name) ?: "Added to the download queue")
            return
        }
        val host = act.findViewById<FrameLayout>(android.R.id.content)
        toast(act, "Looking for the video... (up to 30 seconds)")
        LinkSniffer(act, host) { r ->
            Thread {
                val msg = if (r == null) "No video found on this page." else try { Universal.enqueue(act.applicationContext, link, r) }
                          catch (e: Exception) { "Error: " + (e.message ?: e.javaClass.simpleName) }
                act.runOnUiThread { toast(act, msg) }
            }.start()
        }.start(link)
    }
}
