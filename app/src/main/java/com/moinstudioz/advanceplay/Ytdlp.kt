package com.moinstudioz.advanceplay

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

/**
 * The general video extractor: yt-dlp, bundled inside the app. It understands hundreds of websites.
 * We only ask it WHICH files make up the video; the downloading itself is done by the app's own fast engine, straight onto the USB drive.
 */
object Ytdlp {
    /** What to download: [video] file (or stream playlist when [hls]), an optional separate [audio] file, a title, and the page it came from. */
    class Pick(val video: String, val audio: String?, val hls: Boolean, val title: String, val referer: String)

    @Volatile private var ready = false
    @Volatile private var failed: String? = null
    private val lock = Any()

    /** Starts yt-dlp (the first time it unpacks itself, a few seconds) and refreshes it once a day, because websites change. */
    private fun ensure(c: Context): Boolean {
        synchronized(lock) {
            if (ready) return true
            if (failed != null) return false
            try {
                YoutubeDL.getInstance().init(c.applicationContext)
                ready = true
                val sp = c.getSharedPreferences("ap", Context.MODE_PRIVATE)
                if (System.currentTimeMillis() - sp.getLong("ytdlp_upd", 0L) > 24 * 3600_000L) {
                    val t = Thread {
                        try {
                            val obj = YoutubeDL.getInstance()
                            val m = obj.javaClass.methods.filter { it.name == "updateYoutubeDL" }.minByOrNull { it.parameterCount }
                            if (m != null) {
                                val args = arrayOfNulls<Any>(m.parameterCount)
                                args[0] = c.applicationContext
                                if (m.parameterCount > 1) {
                                    val t1 = m.parameterTypes[1]
                                    args[1] = try { t1.getField("STABLE").get(null) } catch (_: Throwable) { t1.enumConstants?.firstOrNull() }
                                }
                                m.invoke(obj, *args)
                                sp.edit().putLong("ytdlp_upd", System.currentTimeMillis()).apply()
                            }
                        } catch (_: Throwable) {}
                    }
                    t.start()
                    t.join(25_000)
                }
                return true
            } catch (t: Throwable) {
                failed = (t.message ?: t.javaClass.simpleName).take(120)
                return false
            }
        }
    }

    private fun has(f: JSONObject, k: String): Boolean {
        val v = f.optString(k, "none")
        return v.isNotEmpty() && v != "none" && v != "null"
    }

    /** Chooses the best way to download from yt-dlp's list of formats. */
    private fun pick(j: JSONObject): Pick? {
        val title = j.optString("title")
        val referer = (j.optJSONObject("http_headers")?.optString("Referer") ?: "").ifEmpty { j.optString("webpage_url") }
        val arr = j.optJSONArray("formats")
        val fs = ArrayList<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { fs.add(it) }
        if (fs.isEmpty()) {
            val u = j.optString("url")
            return if (u.startsWith("http")) Pick(u, null, u.substringBefore('?').endsWith(".m3u8"), title, referer) else null
        }
        fun proto(f: JSONObject) = f.optString("protocol")
        fun direct(f: JSONObject) = proto(f) == "https" || proto(f) == "http"
        fun hh(f: JSONObject) = f.optInt("height", 0)
        fun score(f: JSONObject) = hh(f) * 100000.0 + f.optDouble("tbr", 0.0)
        val both = fs.filter { has(it, "vcodec") && has(it, "acodec") && direct(it) }.maxByOrNull { score(it) }
        val vOnly = fs.filter {
            has(it, "vcodec") && !has(it, "acodec") && direct(it) && (it.optString("vcodec").startsWith("avc") || it.optString("vcodec").startsWith("h264"))
        }.maxByOrNull { score(it) }
        val aOnly = fs.filter {
            !has(it, "vcodec") && has(it, "acodec") && direct(it) && (it.optString("acodec").startsWith("mp4a") || it.optString("ext") == "m4a")
        }.maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)) }
        val hlsF = fs.filter { proto(it).startsWith("m3u8") && has(it, "vcodec") }.maxByOrNull { score(it) }
        // (height, preference, choice): the tallest picture wins; on a tie a single file beats two files beats a stream
        val cands = ArrayList<Triple<Int, Int, Pick>>()
        if (both != null) cands.add(Triple(hh(both), 3, Pick(both.optString("url"), null, false, title, referer)))
        if (vOnly != null && aOnly != null) cands.add(Triple(hh(vOnly), 2, Pick(vOnly.optString("url"), aOnly.optString("url"), false, title, referer)))
        if (hlsF != null) {
            val u = hlsF.optString("manifest_url").ifEmpty { hlsF.optString("url") }
            cands.add(Triple(hh(hlsF), 1, Pick(u, null, true, title, referer)))
        }
        return cands.maxWithOrNull(compareBy<Triple<Int, Int, Pick>>({ it.first }, { it.second }))?.third
    }

    /** Blocking (takes a few seconds, uses the internet): asks yt-dlp about [link]. Null when it cannot find a video ([why] says why). */
    fun resolve(c: Context, link: String, why: StringBuilder): Pick? {
        if (!ensure(c)) { why.append("The video finder could not start: ").append(failed); return null }
        return try {
            val req = YoutubeDLRequest(link)
            req.addOption("--dump-single-json")
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            req.addOption("--socket-timeout", "20")
            val host = try { java.net.URI(link).host ?: "" } catch (_: Exception) { "" }
            val ck = try { CookieManager.getInstance().getCookie(link) } catch (_: Exception) { null }
            // Facebook / Instagram: use the sign-in of the app's own web screen
            if (!ck.isNullOrEmpty() && (host.contains("facebook") || host.contains("instagram"))) req.addOption("--add-header", "Cookie:" + ck)
            val resp = YoutubeDL.getInstance().execute(req)
            pick(JSONObject(resp.out))
        } catch (t: Throwable) {
            why.append((t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(200))
            null
        }
    }
}
