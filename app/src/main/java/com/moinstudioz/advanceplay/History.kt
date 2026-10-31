package com.moinstudioz.advanceplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Recent search words, newest first (30 are kept, 20 are shown). Saved on the device and in the USB backup. */
object Recents {
    private const val KEY = "recent"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ap", Context.MODE_PRIVATE)

    fun list(c: Context): List<String> = try {
        val a = JSONArray(sp(c).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, l: List<String>) { sp(c).edit().putString(KEY, JSONArray(l).toString()).apply() }

    /** Put a search word on top of the list (an older copy of the same word is removed). */
    fun add(c: Context, q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        val l = ArrayList<String>()
        l.add(t)
        for (x in list(c)) if (!x.equals(t, true)) l.add(x)
        save(c, l.take(30))
    }

    fun remove(c: Context, q: String) { save(c, list(c).filter { !it.equals(q, true) }) }
    fun clear(c: Context) { save(c, emptyList()) }

    fun export(c: Context): JSONArray = JSONArray(list(c))

    /** Used when the backup is restored: adds words we do not have yet. */
    fun import(c: Context, a: JSONArray?) {
        if (a == null) return
        val l = ArrayList<String>(list(c))
        for (i in 0 until a.length()) {
            val t = a.optString(i).trim()
            if (t.isNotEmpty() && l.none { it.equals(t, true) }) l.add(t)
        }
        save(c, l.take(30))
    }
}

/**
 * Remembers which videos were downloaded and which were watched.
 * Downloaded: key = YouTube video id (or "web:<link>") -> title and file name.
 * Watched: YouTube video id for streamed videos, "f:<file name>" for videos played from the Library.
 */
object History {
    private const val KEY = "hist"
    private class Rec(val title: String, val file: String, val at: Long)

    /** One YouTube video that was played in the app: how far we got, newest first in [watchList]. */
    class Watch(val id: String, val title: String, val thumb: String, val posMs: Long, val durMs: Long, val at: Long) {
        /** Share of the video that was watched, 0..100. */
        val percent: Int get() = if (durMs > 0) ((posMs * 100) / durMs).toInt().coerceIn(0, 100) else 0
        val finished: Boolean get() = durMs > 0 && posMs >= durMs - 10_000
    }
    private val wl = LinkedHashMap<String, Watch>()

    private val dl = LinkedHashMap<String, Rec>()
    private val watched = LinkedHashSet<String>()
    private var loaded = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ap", Context.MODE_PRIVATE)

    @Synchronized private fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        try { read(JSONObject(sp(c).getString(KEY, "{}") ?: "{}")) } catch (_: Exception) {}
    }

    private fun read(j: JSONObject) {
        val o = j.optJSONObject("dl")
        if (o != null) {
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val r = o.optJSONObject(k) ?: continue
                if (!dl.containsKey(k)) dl[k] = Rec(r.optString("t"), r.optString("f"), r.optLong("d"))
            }
        }
        val a = j.optJSONArray("w")
        if (a != null) for (i in 0 until a.length()) watched.add(a.optString(i))
        val l = j.optJSONArray("wl")
        if (l != null) for (i in 0 until l.length()) {
            val r = l.optJSONObject(i) ?: continue
            val id = r.optString("i")
            if (id.isNotEmpty() && !wl.containsKey(id))
                wl[id] = Watch(id, r.optString("t"), r.optString("u"), r.optLong("p"), r.optLong("l"), r.optLong("d"))
        }
    }

    private fun json(): JSONObject {
        val o = JSONObject()
        for ((k, r) in dl) o.put(k, JSONObject().put("t", r.title).put("f", r.file).put("d", r.at))
        val a = JSONArray()
        for (w in watched) a.put(w)
        val l = JSONArray()
        for (x in wl.values) l.put(JSONObject().put("i", x.id).put("t", x.title).put("u", x.thumb).put("p", x.posMs).put("l", x.durMs).put("d", x.at))
        return JSONObject().put("dl", o).put("w", a).put("wl", l)
    }

    @Synchronized private fun save(c: Context) {
        while (dl.size > 800) dl.remove(dl.keys.first())
        while (watched.size > 1500) watched.remove(watched.first())
        while (wl.size > 500) wl.remove(wl.keys.first())
        sp(c).edit().putString(KEY, json().toString()).apply()
    }

    /** The YouTube video id inside a link, or the text itself when it is not a YouTube link. */
    fun idOf(url: String): String = Dl.canonical(url)?.substringAfter("v=") ?: url

    @Synchronized fun markDownloaded(c: Context, key: String, title: String, file: String) {
        ensure(c); dl.remove(key); dl[key] = Rec(title, file, System.currentTimeMillis()); save(c)
    }
    @Synchronized fun isDownloaded(c: Context, key: String): Boolean { ensure(c); return dl.containsKey(key) }

    @Synchronized fun markWatched(c: Context, key: String) { ensure(c); if (watched.add(key)) save(c) }
    @Synchronized fun isWatched(c: Context, key: String): Boolean { ensure(c); return watched.contains(key) }

    /** Remember how far a YouTube video was watched (called while playing and when the player closes). */
    @Synchronized fun saveProgress(c: Context, id: String, title: String, thumb: String, posMs: Long, durMs: Long) {
        ensure(c)
        if (posMs < 3000 && !wl.containsKey(id)) return            // ignore videos closed right after they started
        val old = wl.remove(id)
        wl[id] = Watch(id, title.ifBlank { old?.title ?: "" }, thumb.ifBlank { old?.thumb ?: "" }, posMs, if (durMs > 0) durMs else (old?.durMs ?: 0L), System.currentTimeMillis())
        save(c)
    }
    @Synchronized fun progressOf(c: Context, id: String): Watch? { ensure(c); return wl[id] }
    /** All watched YouTube videos, most recent first. */
    @Synchronized fun watchList(c: Context): List<Watch> { ensure(c); return wl.values.toList().asReversed() }
    @Synchronized fun removeWatch(c: Context, id: String) { ensure(c); wl.remove(id); watched.remove(id); save(c) }
    @Synchronized fun clearWatch(c: Context) { ensure(c); wl.clear(); save(c) }

    /** A file was deleted from the Library: it counts as not downloaded and not watched any more. */
    @Synchronized fun forgetFile(c: Context, file: String) {
        ensure(c)
        dl.values.removeAll { it.file == file }
        watched.remove("f:$file")
        save(c)
    }

    @Synchronized fun export(c: Context): JSONObject { ensure(c); return json() }

    /** Used when the backup is restored: adds what we do not have yet. */
    @Synchronized fun import(c: Context, j: JSONObject?) {
        if (j == null) return
        ensure(c); read(j); save(c)
    }
}
