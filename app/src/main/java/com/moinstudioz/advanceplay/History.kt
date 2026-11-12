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

    /** A video / audio file this app saved on the USB drive (MediaStore mode): its exact link, remembered at download time. */
    class LibRec(val name: String, val uri: String, val size: Long, val at: Long)
    private val lib = LinkedHashMap<String, LibRec>()          // key = file name in lower case

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
        val lb = j.optJSONArray("lib")
        if (lb != null) for (i in 0 until lb.length()) {
            val r = lb.optJSONObject(i) ?: continue
            val n = r.optString("n")
            if (n.isNotEmpty() && !lib.containsKey(n.lowercase())) lib[n.lowercase()] = LibRec(n, r.optString("u"), r.optLong("s"), r.optLong("d"))
        }
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
        val ll = JSONArray()
        for (x in lib.values) ll.put(JSONObject().put("n", x.name).put("u", x.uri).put("s", x.size).put("d", x.at))
        return JSONObject().put("dl", o).put("w", a).put("wl", l).put("lib", ll)
    }

    /** Changes every time the history changes (screens use it to refresh only when needed). */
    @Volatile var version = 0

    // writing the history to the device happens on its own thread, a moment after the last change
    private val writerThread = android.os.HandlerThread("ap-history").apply { start() }
    private val writerH = android.os.Handler(writerThread.looper)
    private var appCtx: Context? = null
    private val writeNow = Runnable {
        synchronized(this@History) {
            val ac = appCtx
            if (ac != null) sp(ac).edit().putString(KEY, json().toString()).apply()
        }
    }

    @Synchronized private fun save(c: Context) {
        appCtx = c.applicationContext
        version++
        while (dl.size > 800) dl.remove(dl.keys.first())
        while (watched.size > 1500) watched.remove(watched.first())
        while (wl.size > 500) wl.remove(wl.keys.first())
        while (lib.size > 400) lib.remove(lib.keys.first())
        writerH.removeCallbacks(writeNow)
        writerH.postDelayed(writeNow, 700)
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

    /** Remember the exact link of a file we saved (so the Library can always find it again). */
    @Synchronized fun addLib(c: Context, name: String, uri: String, size: Long) {
        ensure(c); val k = name.lowercase(); lib.remove(k); lib[k] = LibRec(name, uri, size, System.currentTimeMillis()); save(c)
    }
    /** Remembered files, newest first. */
    @Synchronized fun libList(c: Context): List<LibRec> { ensure(c); return lib.values.toList().asReversed() }
    /** File names of everything that was downloaded, newest first. */
    @Synchronized fun downloadedFiles(c: Context): List<String> {
        ensure(c); return dl.values.map { it.file }.filter { it.isNotEmpty() }.distinct().reversed()
    }

    /** A file was deleted from the Library: it counts as not downloaded and not watched any more. */
    @Synchronized fun forgetFile(c: Context, file: String) {
        ensure(c)
        dl.values.removeAll { it.file == file }
        watched.remove("f:$file")
        lib.remove(file.lowercase())
        save(c)
    }

    @Synchronized fun export(c: Context): JSONObject { ensure(c); return json() }

    /** Used when the backup is restored: adds what we do not have yet. */
    @Synchronized fun import(c: Context, j: JSONObject?) {
        if (j == null) return
        ensure(c); read(j); save(c)
    }
}
