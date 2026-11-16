package com.moinstudioz.advanceplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * "Fast searches": the user's own ready-made searches (at most 50). They can be added, edited and deleted,
 * and they are the topics of the home feed. The first time the list starts with the family's favourites.
 */
object FastSearch {
    const val MAX = 50
    private const val KEY = "fast"
    private val defaults = listOf("Zem TV", "Reality TV", "Reality Stories", "Birbal", "Shemaroo Kids", "ARY QTV", "Doraemon", "We Bare Bears")
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ap", Context.MODE_PRIVATE)

    fun list(c: Context): List<String> {
        val raw = sp(c).getString(KEY, null) ?: return defaults
        return try { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { defaults }
    }

    private fun save(c: Context, l: List<String>) { sp(c).edit().putString(KEY, JSONArray(l).toString()).apply() }

    /** False when the text is empty or the list is full (50). */
    fun add(c: Context, q: String): Boolean {
        val t = q.trim()
        if (t.isEmpty()) return false
        val l = ArrayList<String>(list(c))
        if (l.any { it.equals(t, true) }) return true
        if (l.size >= MAX) return false
        l.add(t); save(c, l)
        return true
    }

    fun replace(c: Context, old: String, new: String) {
        val t = new.trim()
        if (t.isEmpty()) return
        save(c, list(c).map { if (it.equals(old, true)) t else it })
    }

    fun remove(c: Context, q: String) { save(c, list(c).filter { !it.equals(q, true) }) }
}

/** Channels the user subscribed to (kept on the device, no account needed). */
object Subs {
    class Sub(val name: String, val url: String)
    private const val KEY = "subs"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ap", Context.MODE_PRIVATE)

    fun list(c: Context): List<Sub> = try {
        val a = JSONArray(sp(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Sub(it.optString("n"), it.optString("u")) } }
    } catch (_: Exception) { emptyList() }

    fun has(c: Context, url: String): Boolean = list(c).any { it.url == url }

    /** Subscribes or unsubscribes. Returns true when the channel is subscribed now. */
    fun toggle(c: Context, name: String, url: String): Boolean {
        val l = ArrayList<Sub>(list(c))
        val now = l.none { it.url == url }
        if (now) l.add(Sub(name, url)) else l.removeAll { it.url == url }
        val a = JSONArray()
        for (s in l.take(100)) a.put(JSONObject().put("n", s.name).put("u", s.url))
        sp(c).edit().putString(KEY, a.toString()).apply()
        return now
    }
}

/** YouTube-style predictions for what is being typed (needs the internet; call from a background thread). */
object Suggest {
    fun fetch(q: String): List<String> {
        if (q.isBlank()) return emptyList()
        return try {
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt&hl=en&q=" + URLEncoder.encode(q, "UTF-8")
            val rq = okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()
            Http.client.newCall(rq).execute().use { r ->
                if (!r.isSuccessful) emptyList()
                else {
                    val s = JSONArray(r.body?.string() ?: "[]").optJSONArray(1) ?: JSONArray()
                    (0 until s.length()).map { s.optString(it) }.filter { it.isNotBlank() }.take(8)
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
