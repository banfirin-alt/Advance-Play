package com.moinstudioz.advanceplay

import android.webkit.CookieManager

/**
 * Finds the video file behind a Facebook or Instagram page link by asking the website itself
 * (signed in with the same cookies as the app's web screen). Used by the "Send to TV" page.
 */
object WebGrab {
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Turns a link written inside page data (\/ and \u0026 style) into a normal link. */
    fun unescape(s: String): String {
        var t = s.replace("\\\\", "\\").replace("\\/", "/").replace("&amp;", "&")
        t = Regex("\\\\u([0-9a-fA-F]{4})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return t
    }

    /** All video file links inside a page's text, best quality first. */
    fun extract(html0: String): List<String> {
        val html = html0.take(6_000_000).replace("\\\"", "\"")
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

    /** Downloads a web page as text; returns (final address after redirects, text) or null when refused. */
    fun fetch(url: String, extra: Map<String, String> = emptyMap()): Pair<String, String>? {
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        if (!cookie.isNullOrEmpty()) b.header("Cookie", cookie)
        for ((k, v) in extra) b.header(k, v)
        return Http.client.newCall(b.build()).execute().use { r ->
            if (r.isSuccessful) Pair(r.request.url.toString(), r.body?.string() ?: "") else null
        }
    }

    /** The video file link for a Facebook / Instagram page link, or null ([notes] says what was tried). */
    fun resolve(page: String, notes: StringBuilder): String? {
        val host = try { java.net.URI(page).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val none = emptyMap<String, String>()
        val tries = ArrayList<Pair<String, Map<String, String>>>()
        if (host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com")) {
            // the link itself first (short fb.watch links are followed to the real page)
            val first = try { fetch(page, none) } catch (_: Exception) { null }
            if (first != null) {
                extract(first.second).firstOrNull { it.startsWith("http") }?.let { return it }
                val id = Regex("(?:/videos/|/reel/|[?&]v=|video_id=)(\\d{6,})").find(first.first)?.groupValues?.get(1)
                    ?: Regex("\"(?:video_id|videoId)\"\\s*:\\s*\"?(\\d{6,})").find(first.second)?.groupValues?.get(1)
                if (id != null) {
                    tries.add(Pair("https://www.facebook.com/watch/?v=$id", none))
                    tries.add(Pair("https://www.facebook.com/reel/$id", none))
                    tries.add(Pair("https://www.facebook.com/video.php?v=$id", none))
                }
            } else notes.append("Facebook refused the page. ")
        } else if (host.contains("instagram.com")) {
            val code = Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})").find(page)?.groupValues?.get(1)
            if (code != null) {
                tries.add(Pair("https://www.instagram.com/p/$code/embed/captioned/", none))
                tries.add(Pair("https://www.instagram.com/reel/$code/", none))
                tries.add(Pair("https://www.instagram.com/p/$code/?__a=1&__d=dis",
                    mapOf("X-IG-App-ID" to "936619743392459", "X-Requested-With" to "XMLHttpRequest")))
            } else notes.append("This Instagram link is not a single post or reel. ")
        } else {
            notes.append("Not a Facebook or Instagram link. ")
            return null
        }
        for ((u, hdr) in tries) {
            val got = try { fetch(u, hdr) } catch (_: Exception) { null }
            if (got == null) { notes.append("refused: ").append(u.substringAfter("//").take(26)).append(". "); continue }
            extract(got.second).firstOrNull { it.startsWith("http") }?.let { return it }
            notes.append("no link in: ").append(u.substringAfter("//").take(26)).append(". ")
        }
        return null
    }
}
