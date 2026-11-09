package com.moinstudioz.advanceplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONTokener

/** The video files found for a web link: picture file, sound file (or null when the picture file already has sound), and a title. */
class SniffResult(val video: String, val audio: String?, val title: String)

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

    fun onRequest(u: String) {
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

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            val waited = System.currentTimeMillis() - started
            val p = catcher.pick()
            if (p != null && (p.complete || waited > 30000)) { finish(p); return }
            if (waited > 30000) { finish(null); return }
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

    private fun finish(p: StreamCatcher.Pick?) {
        if (finished) return
        finished = true
        val w = web
        if (p == null || w == null) { deliver(null); return }
        // title of the video, if the page gives one
        h.postDelayed({ deliver(SniffResult(p.video, p.audio, "")) }, 2500)
        w.evaluateJavascript("(function(){var og=document.querySelector('meta[property=\"og:title\"]');return (og&&og.content)||document.title||'';})()") { r ->
            var t = ""
            try { val v = JSONTokener(r).nextValue(); if (v is String) t = v } catch (_: Exception) {}
            deliver(SniffResult(p.video, p.audio, t))
        }
    }

    private fun cleanup() {
        h.removeCallbacksAndMessages(null)
        val w = web
        web = null
        if (w != null) { try { host.removeView(w); w.destroy() } catch (_: Exception) {} }
    }
}
