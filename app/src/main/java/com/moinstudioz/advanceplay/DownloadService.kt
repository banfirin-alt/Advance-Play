package com.moinstudioz.advanceplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var lastNotif = 0L
    private val THREADS = 6                      // parallel connections = maximum internet
    private val SEG = 4L * 1024 * 1024

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("done", "Download results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        fg("Preparing...", 0)
        val url = i?.getStringExtra("url")
        if (url == null) { if (pending.get() == 0) stopSelf(); return START_NOT_STICKY }
        Prefs.addPending(this, url)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "advplay:dl").apply { acquire(6 * 60 * 60 * 1000L) }
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(mode, "advplay:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        DL.queue = pending.incrementAndGet()
        exec.execute { work(url) }
        return START_NOT_STICKY
    }

    private fun work(url: String) {
        try {
            process(url)
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            DL.fail(m); notifyDone("Download failed", m)
        } finally {
            Prefs.removePending(this, url)
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

        when {
            q == 2 && aud != null -> saveSimple(aud.content, "$safe.m4a", "audio/mp4")
            q == 0 && hd -> saveHd(vOnly!!.content, aud!!.content, "$safe.mp4")
            prog != null -> saveSimple(prog.content, "$safe.mp4", "video/mp4")
            else -> throw IOException("No downloadable stream found for this video")
        }
        DL.complete(info.name)
        Prefs.setLastDone(this, info.name)
        notifyDone("Download complete", info.name)
    }

    private fun saveSimple(url: String, name: String, mime: String) {
        val out = Usb.open(this, name, mime)
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(out.pfd).channel.use { ch ->
                fetchTo(url, ch, 0, 100)
                runCatching { ch.force(false) }
            }
        } catch (e: Exception) {
            runCatching { out.pfd.close() }; out.delete(); throw e
        }
    }

    private fun saveHd(vUrl: String, aUrl: String, name: String) {
        val vf = File(cacheDir, "v.tmp"); val af = File(cacheDir, "a.tmp")
        try {
            RandomAccessFile(vf, "rw").use { fetchTo(vUrl, it.channel, 0, 45) }
            RandomAccessFile(af, "rw").use { fetchTo(aUrl, it.channel, 45, 55) }
            val out = Usb.open(this, name, "video/mp4")
            try {
                mux(vf, af, out.pfd, 55, 100)
                out.pfd.close()
            } catch (e: Exception) {
                runCatching { out.pfd.close() }; out.delete(); throw e
            }
        } finally { vf.delete(); af.delete() }
    }

    private fun probeSize(url: String): Long {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
        Http.dl.newCall(req).execute().use { r ->
            if (r.code != 206) throw IOException("Server does not allow fast download (${r.code})")
            return r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("Unknown file size")
        }
    }

    /** Parallel segmented download: THREADS connections, each pulls 4 MB segments until the file is complete. */
    private fun fetchTo(url: String, ch: FileChannel, from: Int, to: Int) {
        val total = probeSize(url)
        val next = AtomicLong(0)
        val done = AtomicLong(0)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val tasks = (1..THREADS).map {
                pool.submit {
                    try {
                        val buf = ByteArray(128 * 1024)
                        while (failure.get() == null) {
                            val start = next.getAndAdd(SEG)
                            if (start >= total) break
                            segment(url, start, minOf(start + SEG, total) - 1, ch, buf) { done.addAndGet(it.toLong()) }
                        }
                    } catch (t: Throwable) { failure.compareAndSet(null, t); throw t }
                }
            }
            var lastB = 0L
            var lastT = SystemClock.elapsedRealtime()
            while (tasks.any { !it.isDone }) {
                Thread.sleep(500)
                val now = SystemClock.elapsedRealtime()
                val d = done.get()
                DL.speed = (d - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                lastB = d; lastT = now
                DL.pct = (from + (to - from) * d / total).toInt()
                tickNotif()
            }
            try { tasks.forEach { it.get() } } catch (e: ExecutionException) { throw (e.cause as? Exception) ?: e }
            DL.pct = to
        } finally { pool.shutdownNow() }
    }

    private fun segment(url: String, start: Long, end: Long, ch: FileChannel, buf: ByteArray, onBytes: (Int) -> Unit) {
        var pos = start
        var tries = 0
        while (pos <= end) {
            try {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=$pos-$end").build()
                Http.dl.newCall(req).execute().use { r ->
                    if (r.code != 206) throw IOException("Server error ${r.code}")
                    val ins = (r.body ?: throw IOException("Empty response")).byteStream()
                    while (pos <= end) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                        if (n < 0) break
                        ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                        pos += n; onBytes(n)
                        Throttle.onBytes(this, n)          // 80/20 split while a video is playing
                    }
                }
                if (pos <= end) throw IOException("Connection dropped")
            } catch (e: IOException) {
                if (++tries >= 5) throw e
                Thread.sleep(400L * tries)
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
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
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
