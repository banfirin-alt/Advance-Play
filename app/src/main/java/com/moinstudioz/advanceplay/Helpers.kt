package com.moinstudioz.advanceplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Small thumbnail loader with a memory cache (keeps the TV fast). */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)

    fun load(url: String?, iv: ImageView) {
        iv.tag = url
        iv.setImageDrawable(null)
        if (url.isNullOrEmpty()) return
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        pool.execute {
            try {
                val bytes = Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                    .use { r -> r.body?.bytes() } ?: return@execute
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 480) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) { cache.put(url, bmp); iv.post { if (iv.tag == url) iv.setImageBitmap(bmp) } }
            } catch (_: Exception) {}
        }
    }
}

object Dl {
    private val rx = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})")

    fun canonical(u: String): String? =
        if (!u.contains("youtube.com") && !u.contains("youtu.be")) null
        else rx.find(u)?.groupValues?.get(1)?.let { "https://www.youtube.com/watch?v=$it" }

    /** Videos that are waiting or running right now (YouTube links and web-download keys), so the same video is not queued twice. */
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Marks a video as queued. Returns false when it already was. */
    fun markQueued(key: String): Boolean = queued.add(key)
    fun unqueue(key: String) { queued.remove(key) }

    /** Returns an error message, or null when the download was queued. The same video cannot be queued twice. */
    fun enqueue(c: Context, u: String): String? {
        val v = canonical(u) ?: return "That is not a video link"
        UsbState.err?.let { return it }
        if (!markQueued(v)) return "Already in the download queue"
        return try {
            ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java).putExtra("url", v)); null
        } catch (e: Exception) { unqueue(v); "Cannot start download: " + e.message }
    }

    /** Facebook / Instagram: download a video file link found on a web page. [key] identifies the video (to block duplicates). */
    fun enqueueDirect(c: Context, key: String, mediaUrl: String, title: String): String? {
        UsbState.err?.let { return it }
        if (!markQueued(key)) return "Already in the download queue"
        return try {
            ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)
                .putExtra("url", key).putExtra("direct", mediaUrl).putExtra("name", title)); null
        } catch (e: Exception) { unqueue(key); "Cannot start download: " + e.message }
    }
}
