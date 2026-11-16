package com.moinstudioz.advanceplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadService : Service() {
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastNotif = 0L
    private val THREADS = 32                     // parallel connections = maximum internet
    private val bufPool = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()      // reused 1 MB buffers
    private val SEG = 1L * 1024 * 1024          // 1 MB pieces: with 32 connections the memory use stays about the same as before

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Throttle.maxSpeed = Prefs.maxSpeed(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("done", "Download results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        fg("Preparing...", 0)
        val url = i?.getStringExtra("url")
        if (url == null) { if (pending.get() == 0) stopSelf(); return START_NOT_STICKY }
        val direct = i.getStringExtra("direct")           // set for Facebook / Instagram downloads (a plain video file link)
        Dl.markQueued(url)                                 // also covers downloads resumed after a restart
        if (direct == null && i.getStringExtra("hls") == null) Prefs.addPending(this, url)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "advplay:dl").apply { acquire(6 * 60 * 60 * 1000L) }
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(mode, "advplay:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        DL.queue = pending.incrementAndGet()
        val title = i.getStringExtra("name")
        val audio = i.getStringExtra("audio")
        val hls = i.getStringExtra("hls")
        val ref = i.getStringExtra("referer") ?: ""
        exec.execute { work(url, direct, title, audio, hls, ref) }
        return START_NOT_STICKY
    }

    /** The web page a video came from (some sites only serve their video files when this is sent). */
    @Volatile private var referer = ""

    private fun work(url: String, direct: String? = null, title: String? = null, audio: String? = null, hls: String? = null, ref: String = "") {
        referer = ref
        try {
            if (hls != null) processHls(hls, title ?: "video")
            else if (direct != null) processDirect(direct, title ?: "video", audio) else process(url)
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            DL.fail(m); notifyDone("Download failed", m)
        } finally {
            Prefs.removePending(this, url)
            Dl.unqueue(url)
            val left = pending.decrementAndGet()
            DL.queue = left
            if (left == 0) {
                DL.idle()
                wake?.let { if (it.isHeld) it.release() }; wake = null
                wifi?.let { if (it.isHeld) it.release() }; wifi = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun process(url: String) {
        DL.begin(); DL.title = "Fetching info..."
        Np.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM)
            throw IOException("Live streams can be watched, but not downloaded")
        DL.title = info.name
        val safe = info.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(90).ifBlank { "video" }

        fun ok(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        val prog = info.videoStreams.filter { ok(it) }.maxByOrNull { it.height }
        val vOnly = info.videoOnlyStreams
            .filter { ok(it) && it.format == NpFormat.MPEG_4 && it.codec?.startsWith("avc1") == true && it.height <= 1080 }
            .maxByOrNull { it.height }
        val aud = info.audioStreams.filter { ok(it) && it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate }
        val q = Prefs.quality(this)
        val hd = vOnly != null && aud != null && (prog == null || vOnly.height > prog.height)

        val outName: String = when {
            q == 2 && aud != null -> { saveSimple(aud.content, "$safe.m4a", "audio/mp4"); "$safe.m4a" }
            q == 0 && hd -> { saveHd(vOnly!!.content, aud!!.content, "$safe.mp4"); "$safe.mp4" }
            prog != null -> { saveSimple(prog.content, "$safe.mp4", "video/mp4"); "$safe.mp4" }
            else -> throw IOException("No downloadable stream found for this video")
        }
        DL.complete(info.name)
        Prefs.setLastDone(this, info.name)
        History.markDownloaded(this, History.idOf(url), info.name, outName)      // remember it was downloaded
        runCatching { Backup.save(this) }
        notifyDone("Download complete", info.name)
    }

    // ---------------------------------------------------------------------------------------------
    // Stream playlists (.m3u8): most video sites outside YouTube. The parts are downloaded in parallel and joined.
    // ---------------------------------------------------------------------------------------------

    private class HlsInfo(val segs: List<String>, val init: String?, val keyUri: String?, val iv: ByteArray?, val seq0: Long)

    private fun hget(url: String, parallel: Boolean = false): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                val rb = okhttp3.Request.Builder().url(url).header("User-Agent", UA).apply { if (referer.isNotEmpty()) header("Referer", referer) }
                (if (parallel) Http.dl else Http.client).newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Server error ${r.code}")
                    return (r.body ?: throw IOException("Empty response")).bytes()
                }
            } catch (e: Exception) { last = e; Thread.sleep(400) }
        }
        throw IOException(last?.message ?: "Download failed")
    }

    private fun htext(url: String) = String(hget(url), Charsets.UTF_8)

    private fun hlsAbs(base: String, ref: String): String =
        try { java.net.URI(base).resolve(ref.replace(" ", "%20")).toString() } catch (_: Exception) { ref }

    private fun attr(line: String, name: String): String? =
        Regex("[:,]" + name + "=(\"([^\"]*)\"|[^,]*)").find(line)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }

    /** Picks the best picture playlist of a master playlist (and the separate sound playlist when the site keeps sound apart). */
    private fun pickVariant(base: String, text: String): Pair<String, String?> {
        val lines = text.lines()
        var bestBw = -1L
        var best = base
        var audioGroup: String? = null
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
            if (j < lines.size && bw > bestBw) { bestBw = bw; best = hlsAbs(base, lines[j].trim()); audioGroup = attr(l, "AUDIO") }
        }
        var audio: String? = null
        if (audioGroup != null) for (l0 in lines) {
            val l = l0.trim()
            if (l.startsWith("#EXT-X-MEDIA") && attr(l, "TYPE") == "AUDIO" && attr(l, "GROUP-ID") == audioGroup) {
                val u = attr(l, "URI")
                if (u != null) { audio = hlsAbs(base, u); if (l.contains("DEFAULT=YES")) break }
            }
        }
        return Pair(best, audio)
    }

    private fun parseMedia(base: String, text: String): HlsInfo {
        var init: String? = null
        var key: String? = null
        var iv: ByteArray? = null
        var seq = 0L
        val segs = ArrayList<String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-MAP:") -> init = attr(l, "URI")?.let { hlsAbs(base, it) }
                l.startsWith("#EXT-X-KEY:") -> {
                    val m = attr(l, "METHOD")
                    if (m == "AES-128") {
                        key = attr(l, "URI")?.let { hlsAbs(base, it) }
                        iv = attr(l, "IV")?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                    } else if (m != null && m != "NONE") throw IOException("This video is protected and cannot be downloaded")
                    else { key = null; iv = null }
                }
                l.isNotEmpty() && !l.startsWith("#") -> segs.add(hlsAbs(base, l))
            }
        }
        return HlsInfo(segs, init, key, iv, seq)
    }

    private fun seqIv(n: Long): ByteArray { val b = ByteArray(16); for (k in 0 until 8) b[15 - k] = (n shr (8 * k)).toByte(); return b }

    private fun writeAll(ch: FileChannel, d: ByteArray) { val bb = ByteBuffer.wrap(d); while (bb.hasRemaining()) ch.write(bb) }

    /** Downloads all parts of a playlist in parallel and writes them, in order, into [ch]. */
    private fun hlsFetch(info: HlsInfo, ch: FileChannel, from: Int, to: Int) {
        val keyBytes: ByteArray? = info.keyUri?.let { hget(it) }
        if (keyBytes != null && keyBytes.size != 16) throw IOException("Unsupported encryption")
        info.init?.let { writeAll(ch, hget(it)) }
        val n = info.segs.size
        val next = AtomicInteger(0)
        val written = AtomicInteger(0)
        val ready = ConcurrentHashMap<Int, ByteArray>()
        val failure = AtomicReference<Throwable?>(null)
        val done = AtomicLong(0)
        val workers = 12
        val pool = Executors.newFixedThreadPool(workers + 1) { r -> Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
        try {
            val tasks = ArrayList<java.util.concurrent.Future<*>>()
            repeat(workers) {
                tasks.add(pool.submit(Runnable {
                    try {
                        while (failure.get() == null) {
                            val i = next.getAndIncrement()
                            if (i >= n) break
                            while (i - written.get() >= 24 && failure.get() == null) Thread.sleep(15)
                            var d = hget(info.segs[i], true)
                            done.addAndGet(d.size.toLong())
                            if (keyBytes != null) {
                                val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(info.iv ?: seqIv(info.seq0 + i)))
                                d = c.doFinal(d)
                            }
                            ready[i] = d
                        }
                    } catch (t: Throwable) { failure.compareAndSet(null, t) }
                }))
            }
            tasks.add(pool.submit(Runnable {
                try {
                    var w = 0
                    while (w < n && failure.get() == null) {
                        val d = ready.remove(w)
                        if (d == null) { Thread.sleep(8); continue }
                        writeAll(ch, d)
                        w++; written.set(w)
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t) }
            }))
            var lastB = 0L
            var lastT = SystemClock.elapsedRealtime()
            while (tasks.any { !it.isDone }) {
                Thread.sleep(500)
                val now = SystemClock.elapsedRealtime()
                val dn = done.get()
                DL.speed = (dn - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                lastB = dn; lastT = now
                DL.pct = (from + (to - from) * written.get() / n.coerceAtLeast(1)).coerceAtMost(to)
                tickNotif()
            }
            val bad = failure.get()
            if (bad != null) throw (bad as? Exception) ?: IOException(bad.message)
            DL.pct = to
        } finally { pool.shutdownNow() }
    }

    private fun hlsToFile(f: File, info: HlsInfo, from: Int, to: Int) {
        java.io.FileOutputStream(f).channel.use { hlsFetch(info, it, from, to) }
    }

    /** Joins everything in one transport-stream file into an mp4 (all picture and sound tracks). */
    private fun remuxAll(src: File, pfd: ParcelFileDescriptor, from: Int, to: Int) {
        val ex = MediaExtractor()
        val mm = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ex.setDataSource(src.path)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) { ex.selectTrack(i); map[i] = mm.addTrack(f) }
            }
            if (map.isEmpty()) throw IOException("No picture or sound found in this stream")
            mm.start()
            val buf = ByteBuffer.allocate(4 shl 20)
            val bi = MediaCodec.BufferInfo()
            val all = src.length().coerceAtLeast(1)
            var done = 0L
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = map[ex.sampleTrackIndex]
                if (t != null) { bi.set(0, n, ex.sampleTime, ex.sampleFlags); mm.writeSampleData(t, buf, bi) }
                ex.advance()
                done += n
                DL.pct = (from + (to - from) * done / all).toInt().coerceAtMost(to)
            }
            mm.stop()
        } finally { runCatching { mm.release() }; ex.release() }
    }

    private fun commitOut(out: Usb.Out, fileName: String) {
        val sz = try { out.pfd.statSize } catch (_: Exception) { 0L }
        runCatching { out.pfd.close() }
        out.finish()
        out.uri?.let { History.addLib(this, fileName, it.toString(), sz) }
    }

    /** Any site: [m3u8] is a stream playlist. Picks the best quality, downloads the parts in parallel and saves one video file. */
    private fun processHls(m3u8: String, title: String) {
        DL.begin(); DL.title = title
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(90).ifBlank { "video" }
        val master = htext(m3u8)
        var videoPl = m3u8
        var audioPl: String? = null
        if (master.contains("#EXT-X-STREAM-INF")) { val v = pickVariant(m3u8, master); videoPl = v.first; audioPl = v.second }
        val vi = parseMedia(videoPl, if (videoPl == m3u8) master else htext(videoPl))
        if (vi.segs.isEmpty()) throw IOException("No video parts found in this stream")
        val ai = audioPl?.let { parseMedia(it, htext(it)) }
        val td = Usb.tempDir(this)
        val vf = File(td, "ap_hv.tmp")
        val af = File(td, "ap_ha.tmp")
        var saved = "$safe.mp4"
        try {
            if (ai != null && ai.segs.isNotEmpty()) {
                // picture and sound are separate playlists: download both, then join them
                hlsToFile(vf, vi, 0, 45); hlsToFile(af, ai, 45, 75)
                val out = Usb.open(this, saved, "video/mp4")
                try { mux(vf, af, out.pfd, 75, 98); commitOut(out, saved) } catch (e: Exception) { runCatching { out.pfd.close() }; out.delete(); throw e }
            } else if (vi.init != null) {
                // fragmented mp4: the parts are written straight into the video file
                val out = Usb.open(this, saved, "video/mp4")
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(out.pfd).channel.use { hlsFetch(vi, it, 0, 98) }
                    commitOut(out, saved)
                } catch (e: Exception) { runCatching { out.pfd.close() }; out.delete(); throw e }
            } else {
                // transport stream: download, then convert into mp4 (if that fails the plain .ts file is kept)
                hlsToFile(vf, vi, 0, 75)
                try {
                    val out = Usb.open(this, saved, "video/mp4")
                    try { remuxAll(vf, out.pfd, 75, 98); commitOut(out, saved) } catch (e: Exception) { runCatching { out.pfd.close() }; out.delete(); throw e }
                } catch (e: Exception) {
                    saved = "$safe.ts"
                    val out = Usb.open(this, saved, "video/mp2t")
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(out.pfd).channel.use { ch ->
                            java.io.FileInputStream(vf).channel.use { src -> var p = 0L; val sz = src.size(); while (p < sz) p += src.transferTo(p, sz - p, ch) }
                        }
                        commitOut(out, saved)
                    } catch (e2: Exception) { runCatching { out.pfd.close() }; out.delete(); throw e2 }
                }
            }
        } finally { vf.delete(); af.delete() }
        DL.complete(title)
        Prefs.setLastDone(this, title)
        History.markDownloaded(this, "web:$safe", title, saved)
        runCatching { Backup.save(this) }
        notifyDone("Download complete", title)
    }

    /** Facebook / Instagram: [media] is a direct link to the video file found on the web page. */
    private fun processDirect(media: String, title: String, audio: String? = null) {
        DL.begin(); DL.title = title
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(90).ifBlank { "video" }
        val file = "$safe.mp4"
        try {
            if (audio != null) saveHd(media, audio, file)        // picture and sound are separate files: download both and join them
            else saveSimple(media, file, "video/mp4")
        } catch (e: IOException) {
            // some servers refuse fast (parallel) download: use one plain connection instead
            if (audio == null && e.message?.contains("fast download") == true) saveSimple(media, file, "video/mp4", true) else throw e
        }
        DL.complete(title)
        Prefs.setLastDone(this, title)
        History.markDownloaded(this, "web:$safe", title, file)
        runCatching { Backup.save(this) }
        notifyDone("Download complete", title)
    }

    private fun saveSimple(url: String, name: String, mime: String, single: Boolean = false) {
        val out = Usb.open(this, name, mime)
        var size = 0L
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(out.pfd).channel.use { ch ->
                if (single) fetchSingle(url, ch) else fetchTo(url, ch, 0, 100)
                runCatching { ch.force(false) }
                size = runCatching { ch.size() }.getOrDefault(0L)
            }
            out.finish()    // success: in USB (MediaStore) mode this makes the video visible; other modes do nothing
            out.uri?.let { History.addLib(this, name, it.toString(), size) }       // remember its exact link for the Library
        } catch (e: Exception) {
            runCatching { out.pfd.close() }; out.delete(); throw e
        }
    }

    private fun saveHd(vUrl: String, aUrl: String, name: String) {
        val td = Usb.tempDir(this); val vf = File(td, "ap_v.tmp"); val af = File(td, "ap_a.tmp")
        try {
            RandomAccessFile(vf, "rw").use { fetchTo(vUrl, it.channel, 0, 45) }
            RandomAccessFile(af, "rw").use { fetchTo(aUrl, it.channel, 45, 55) }
            val out = Usb.open(this, name, "video/mp4")
            try {
                mux(vf, af, out.pfd, 55, 100)
                val sz = try { out.pfd.statSize } catch (_: Exception) { 0L }
                out.pfd.close()
                out.finish()    // success: in USB (MediaStore) mode this makes the video visible; other modes do nothing
                out.uri?.let { History.addLib(this, name, it.toString(), sz) }       // remember its exact link for the Library
            } catch (e: Exception) {
                runCatching { out.pfd.close() }; out.delete(); throw e
            }
        } finally { vf.delete(); af.delete() }
    }

    /** Plain one-connection download (used only when the server does not allow the fast parallel mode). */
    private fun fetchSingle(url: String, ch: FileChannel) {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).apply { if (referer.isNotEmpty()) header("Referer", referer) }.build()
        Http.dl.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server error ${r.code}")
            val body = r.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            val ins = body.byteStream()
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastB = 0L
            var lastT = SystemClock.elapsedRealtime()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                val bb = ByteBuffer.wrap(buf, 0, n)
                while (bb.hasRemaining()) ch.write(bb)
                done += n
                Throttle.onBytes(this, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastT >= 500) {
                    DL.speed = (done - lastB) * 1000 / (now - lastT)
                    lastB = done; lastT = now
                    if (total > 0) DL.pct = (done * 100 / total).toInt().coerceAtMost(99)
                    tickNotif()
                }
            }
            DL.pct = 100
        }
    }

    private fun probeSize(url: String): Long {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0")
            .apply { if (referer.isNotEmpty()) header("Referer", referer) }.build()
        Http.dl.newCall(req).execute().use { r ->
            if (r.code != 206) throw IOException("Server does not allow fast download (${r.code})")
            return r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("Unknown file size")
        }
    }

    /**
     * Maximum-speed download: THREADS parallel connections fetch 2 MB segments into memory,
     * and one writer thread stores them to the USB strictly in order (fast, flash-friendly writes).
     */
    private fun fetchTo(url: String, ch: FileChannel, from: Int, to: Int) {
        val total = probeSize(url)
        val nSeg = ((total + SEG - 1) / SEG).toInt()
        val next = AtomicInteger(0)
        val written = AtomicInteger(0)
        val ready = ConcurrentHashMap<Int, ByteArray>()
        val done = AtomicLong(0)
        val failure = AtomicReference<Throwable?>(null)
        val window = THREADS + 4
        // background priority: the picture and the remote stay smooth even while all connections are busy
        val pool = Executors.newFixedThreadPool(THREADS + 1) { r -> Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
        try {
            val tasks = ArrayList<java.util.concurrent.Future<*>>()
            repeat(THREADS) {
                tasks += pool.submit(Runnable {
                    try {
                        while (failure.get() == null) {
                            val i = next.getAndIncrement()
                            if (i >= nSeg) break
                            while (i - written.get() >= window && failure.get() == null) Thread.sleep(15)
                            val start = i * SEG
                            val end = minOf(start + SEG, total) - 1
                            val len = (end - start + 1).toInt()
                            val data = (if (len == SEG.toInt()) bufPool.poll() else null) ?: ByteArray(len)
                            segment(url, start, end, data) { n -> done.addAndGet(n.toLong()) }
                            ready[i] = data
                        }
                    } catch (t: Throwable) { failure.compareAndSet(null, t) }
                })
            }
            tasks += pool.submit(Runnable {
                try {
                    var w = 0
                    while (w < nSeg && failure.get() == null) {
                        val d = ready.remove(w)
                        if (d == null) { Thread.sleep(8); continue }
                        val bb = ByteBuffer.wrap(d)
                        while (bb.hasRemaining()) ch.write(bb)
                        if (d.size == SEG.toInt() && bufPool.size < THREADS * 2) bufPool.offer(d)
                        w++; written.set(w)
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t) }
            })
            var lastB = 0L
            var lastT = SystemClock.elapsedRealtime()
            while (tasks.any { !it.isDone }) {
                Thread.sleep(500)
                val now = SystemClock.elapsedRealtime()
                val d = done.get()
                DL.speed = (d - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                lastB = d; lastT = now
                DL.pct = (from + (to - from) * d / total).toInt().coerceAtMost(to)
                tickNotif()
            }
            failure.get()?.let { throw (it as? Exception) ?: IOException(it.message) }
            DL.pct = to
        } finally { pool.shutdownNow(); bufPool.clear() }
    }

    private fun segment(url: String, start: Long, end: Long, data: ByteArray, onBytes: (Int) -> Unit) {
        var got = 0
        var tries = 0
        while (got < data.size) {
            try {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).apply { if (referer.isNotEmpty()) header("Referer", referer) }
                    .header("Range", "bytes=${start + got}-$end").build()
                Http.dl.newCall(req).execute().use { r ->
                    if (r.code != 206) throw IOException("Server error ${r.code}")
                    val ins = (r.body ?: throw IOException("Empty response")).byteStream()
                    while (got < data.size) {
                        val n = ins.read(data, got, data.size - got)
                        if (n < 0) break
                        got += n
                        onBytes(n)
                        Throttle.onBytes(this, n)          // 80/20 split only while a video plays (unless Max speed)
                    }
                }
                if (got < data.size) throw IOException("Connection dropped")
            } catch (e: IOException) {
                if (++tries >= 6) throw e
                Thread.sleep(300L * tries)
            }
        }
    }

    private fun mux(v: File, a: File, pfd: ParcelFileDescriptor, from: Int, to: Int) {
        val ev = MediaExtractor(); val ea = MediaExtractor()
        val mm = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            ev.setDataSource(v.path); ea.setDataSource(a.path)
            ev.selectTrack(0); ea.selectTrack(0)
            val tv = mm.addTrack(ev.getTrackFormat(0))
            val ta = mm.addTrack(ea.getTrackFormat(0))
            mm.start()
            val buf = ByteBuffer.allocate(4 shl 20)
            val bi = MediaCodec.BufferInfo()
            val all = (v.length() + a.length()).coerceAtLeast(1)
            var done = 0L
            var vOk = true; var aOk = true
            while (vOk || aOk) {
                val useV = vOk && (!aOk || ev.sampleTime <= ea.sampleTime)
                val e = if (useV) ev else ea
                val n = e.readSampleData(buf, 0)
                if (n < 0) { if (useV) vOk = false else aOk = false; continue }
                bi.set(0, n, e.sampleTime, e.sampleFlags)
                mm.writeSampleData(if (useV) tv else ta, buf, bi)
                e.advance()
                done += n
                DL.pct = (from + (to - from) * done / all).toInt().coerceAtMost(to)
            }
            mm.stop()
        } finally {
            runCatching { mm.release() }; ev.release(); ea.release()
        }
    }

    private fun pi() = PendingIntent.getActivity(
        this, 0, Intent(this, if (Dev.isTv(this)) BrowseActivity::class.java else MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun fg(text: String, pct: Int) {
        val n: Notification = NotificationCompat.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Advance Play By Moin").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi())
            .setProgress(100, pct, pct == 0).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun tickNotif() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif > 1500) { lastNotif = now; fg("${DL.title.take(40)}  ${DL.pct}%", DL.pct) }
    }

    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(this, "done")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(pi()).build()
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
    }
}
