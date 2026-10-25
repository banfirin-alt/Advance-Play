package com.moinstudioz.advanceplay

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Downloads use HTTP/1.1 so every segment gets its own TCP connection (real parallel speed). */
    val dl: OkHttpClient = client.newBuilder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectionPool(okhttp3.ConnectionPool(32, 2, TimeUnit.MINUTES))
        .readTimeout(40, TimeUnit.SECONDS).build()
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("ap", Context.MODE_PRIVATE)
    fun tree(c: Context): Uri? = p(c).getString("tree", null)?.let { Uri.parse(it) }
    fun setTree(c: Context, u: Uri?) = p(c).edit().putString("tree", u?.toString()).apply()
    fun quality(c: Context) = p(c).getInt("q", 0)
    fun setQuality(c: Context, q: Int) = p(c).edit().putInt("q", q).apply()
    fun total(c: Context) = p(c).getLong("total", 0L)
    fun addTotal(c: Context, d: Long) = p(c).edit().putLong("total", total(c) + d).apply()
    fun resetTotal(c: Context) = p(c).edit().putLong("total", 0L).apply()
    fun cap(c: Context) = p(c).getLong("cap", 0L)
    fun setCap(c: Context, v: Long) = p(c).edit().putLong("cap", v).apply()
    // survives app kill / reboot
    fun pending(c: Context): Set<String> = p(c).getStringSet("pending", emptySet())!!.toSet()
    fun addPending(c: Context, u: String) = p(c).edit().putStringSet("pending", pending(c) + u).apply()
    fun removePending(c: Context, u: String) = p(c).edit().putStringSet("pending", pending(c) - u).apply()
    fun direct(c: Context): String? = p(c).getString("direct", null)
    fun setDirect(c: Context, v: String?) = p(c).edit().putString("direct", v).apply()
    fun tvHome(c: Context) = p(c).getInt("tvhome", 0)             // 0 = fast native home, 1 = YouTube web
    fun setTvHome(c: Context, v: Int) = p(c).edit().putInt("tvhome", v).apply()
    fun diag(c: Context): String = p(c).getString("diag", "") ?: ""
    fun setDiag(c: Context, v: String) = p(c).edit().putString("diag", v).apply()
    fun playHd(c: Context) = p(c).getBoolean("playhd", false)       // false = smooth streaming
    fun setPlayHd(c: Context, v: Boolean) = p(c).edit().putBoolean("playhd", v).apply()
    fun maxSpeed(c: Context) = p(c).getBoolean("max", false)
    fun setMaxSpeed(c: Context, v: Boolean) = p(c).edit().putBoolean("max", v).apply()
    fun layout(c: Context) = p(c).getInt("layout", 0)          // 0 auto, 1 TV layout, 2 normal
    fun setLayout(c: Context, v: Int) = p(c).edit().putInt("layout", v).apply()
    fun setTotal(c: Context, v: Long) = p(c).edit().putLong("total", v).apply()
    fun bkTs(c: Context) = p(c).getLong("bkts", 0L)
    fun setBkTs(c: Context, v: Long) = p(c).edit().putLong("bkts", v).apply()
    fun pos(c: Context, k: String) = p(c).getLong("pos_" + k.hashCode(), 0L)
    fun setPos(c: Context, k: String, v: Long) = p(c).edit().putLong("pos_" + k.hashCode(), v).apply()
    fun lastUrl(c: Context): String? = p(c).getString("lastUrl", null)
    fun setLastUrl(c: Context, u: String?) = p(c).edit().putString("lastUrl", u).apply()
    fun lastDone(c: Context) = p(c).getString("lastDone", "") ?: ""
    fun setLastDone(c: Context, t: String) = p(c).edit().putString("lastDone", t).apply()
}

/** Shared download state, read by the UI every second. */
object DL {
    @Volatile var active = false
    @Volatile var title = ""
    @Volatile var pct = 0
    @Volatile var speed = 0L
    @Volatile var queue = 0
    @Volatile var doneSeq = 0
    @Volatile var errSeq = 0
    @Volatile var lastDone = ""
    @Volatile var errMsg = ""
    @Volatile var resumed = false
    fun begin() { active = true; pct = 0; speed = 0 }
    fun complete(t: String) { lastDone = t; doneSeq++; pct = 100 }
    fun fail(m: String) { errMsg = m; errSeq++ }
    fun idle() { active = false; speed = 0 }
}

/** Network usage of this app (browser + downloads). */
object Net {
    private var last = -1L
    private var lastT = 0L
    @Volatile var speed = 0L
    @Volatile var session = 0L
    @Volatile var supported = true
    fun tick(c: Context) {
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        if (rx < 0) { supported = false; return }
        val now = SystemClock.elapsedRealtime()
        if (last >= 0) {
            val d = (rx - last).coerceAtLeast(0)
            val dt = (now - lastT).coerceAtLeast(1)
            speed = d * 1000 / dt
            session += d
            if (d > 0) Prefs.addTotal(c, d)
        }
        last = rx; lastT = now
    }
}

/**
 * 80 / 20 split: while a video is playing in the browser, downloads are paced to 20% of the
 * estimated link capacity, leaving the rest (80%) for the stream. Without a playing video,
 * downloads run at full speed (and the link capacity estimate is learned from them).
 */
object Throttle {
    @Volatile var streaming = false
    @Volatile var maxSpeed = false      // true = never limit downloads
    const val DL_SHARE = 0.20
    private var winStart = 0L
    private var winBytes = 0L
    private var was = false

    fun capacity(c: Context): Long {
        val learned = Prefs.cap(c)
        if (learned > 0) return learned
        return try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val kbps = cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps ?: 0
            if (kbps > 0) kbps * 125L else 2_000_000L
        } catch (e: Exception) { 2_000_000L }
    }

    fun onBytes(c: Context, n: Int) {
        var sleepMs = 0L
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            val limited = streaming && !maxSpeed
            if (winStart == 0L || was != limited) { winStart = now; winBytes = 0; was = limited }
            winBytes += n
            val dt = now - winStart
            if (!limited) {
                if (dt >= 1500) {
                    val sp = winBytes * 1000 / dt
                    val cap = Prefs.cap(c)
                    Prefs.setCap(c, if (sp > cap) sp else cap - (cap - sp) / 20)
                    winStart = now; winBytes = 0
                }
            } else {
                val limit = (capacity(c) * DL_SHARE).toLong().coerceAtLeast(96_000L)
                val needMs = winBytes * 1000 / limit
                if (needMs > dt) sleepMs = minOf(needMs - dt, 1000L)
                if (dt >= 2000) { winStart = now; winBytes = 0 }
            }
        }
        if (sleepMs > 0) Thread.sleep(sleepMs)
    }
}

object Usb {
    class Out(val pfd: ParcelFileDescriptor, val delete: () -> Unit)
    class Setup(val ok: Boolean, val msg: String, val needPerm: Boolean = false)

    fun appDir(c: Context): File? =
        c.getExternalFilesDirs(null).filterNotNull().firstOrNull { Environment.isExternalStorageRemovable(it) }

    /** Where we save when no picker folder is set: the direct USB folder first, then the app's own USB/SD folder. */
    fun dir(c: Context): File? = Prefs.direct(c)?.let { File(it) } ?: appDir(c)

    /** null = we can write here, otherwise the reason it failed. */
    fun tryWrite(d: File?): String? {
        if (d == null) return "no folder"
        return try {
            d.mkdirs()
            if (!d.isDirectory) return "cannot create folder"
            val f = File(d, ".ap_test"); f.writeText("x"); f.delete(); null
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    fun writable(d: File?) = tryWrite(d) == null

    private fun volDir(v: StorageVolume): File? = try {
        if (Build.VERSION.SDK_INT >= 30) v.directory
        else (StorageVolume::class.java.getMethod("getPath").invoke(v) as? String)?.let { File(it) }
    } catch (e: Exception) { null }

    /** USB drives / SD cards currently plugged in. */
    fun volumeRoots(c: Context): List<File> {
        val out = LinkedHashSet<File>()
        try {
            c.getSystemService(StorageManager::class.java).storageVolumes.forEach { v ->
                if (v.isRemovable && v.state == Environment.MEDIA_MOUNTED) volDir(v)?.let { out.add(it) }
            }
        } catch (_: Exception) {}
        try {
            c.getExternalFilesDirs(null).filterNotNull().forEach {
                if (Environment.isExternalStorageRemovable(it)) out.add(File(it.path.substringBefore("/Android/data")))
            }
        } catch (_: Exception) {}
        try {
            File("/storage").listFiles()?.forEach {
                if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) out.add(it)
            }
        } catch (_: Exception) {}
        return out.filter { it.exists() }
    }

    /** Every place on the USB drive we can try to save into (most visible first). */
    fun candidates(c: Context): List<File> {
        val roots = volumeRoots(c)
        val out = ArrayList<File>()
        roots.forEach { out.add(File(it, "AdvancePlay")) }
        try {
            @Suppress("DEPRECATION")
            c.externalMediaDirs.filterNotNull().forEach {
                val rem = try { Environment.isExternalStorageRemovable(it) } catch (e: Exception) { false }
                if (rem) out.add(File(it, "AdvancePlay"))
            }
        } catch (_: Exception) {}
        roots.forEach { out.add(File(it, "Android/media/${c.packageName}/AdvancePlay")) }
        roots.forEach { out.add(File(it, "Android/data/${c.packageName}/files/AdvancePlay")) }
        roots.forEach { out.add(File(it, "Download/AdvancePlay")); out.add(File(it, "Movies/AdvancePlay")) }
        return out.distinct()
    }

    /** "Use USB drive": finds the plugged-in drive and the first folder on it we are allowed to write to. */
    fun setupDirect(c: Context): Setup {
        val roots = volumeRoots(c)
        if (roots.isEmpty()) {
            Prefs.setDiag(c, "No USB drive found by the app.")
            return Setup(false, "No USB drive found. Plug it in and try again.")
        }
        val log = StringBuilder("Tried these places:\n")
        for (d in candidates(c)) {
            val e = tryWrite(d)
            if (e == null) { Prefs.setDirect(c, d.path); Prefs.setTree(c, null); Prefs.setDiag(c, ""); return Setup(true, d.path) }
            log.append(d.path.removePrefix("/storage/")).append("  ->  ").append(e.take(70)).append('\n')
        }
        Prefs.setDiag(c, log.toString())
        val needPerm = if (Build.VERSION.SDK_INT >= 30) !Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        return Setup(false, "The TV would not let the app save on this USB drive. Details are shown on the screen.", needPerm)
    }

    /** Silent: on every start (and after a reinstall) find the USB folder again by itself and restore the backup. */
    fun autoSetup(c: Context) {
        if (Prefs.tree(c) != null) return
        val cur = Prefs.direct(c)
        if (cur != null && File(cur).isDirectory) return
        if (setupDirect(c).ok) { Backup.restore(c); Backup.save(c) }
    }

    fun treeUuid(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')

    /** null = OK, otherwise a message telling the user what to fix. TV must use USB, never internal memory. */
    fun check(c: Context): String? {
        val tree = Prefs.tree(c)
        if (tree != null) {
            if (Dev.isTv(c) && treeUuid(tree).equals("primary", true))
                return "On TV choose a folder on the USB drive, not the TV memory"
            return null
        }
        val d = dir(c) ?: return if (Dev.isTv(c)) "No USB drive found. Plug it in, then open Settings and press Use USB drive"
        else "Insert an SD card or choose a download folder in Settings"
        return if (writable(d)) null else "Cannot save to the USB yet. Open Settings and press Use USB drive"
    }

    fun hasTarget(c: Context) = check(c) == null

    fun tempDir(c: Context): File = dir(c)?.takeIf { writable(it) } ?: c.cacheDir

    /** Pair(free, total) of the USB volume, or null if not connected. */
    fun space(c: Context): Pair<Long, Long>? = try {
        val tree = Prefs.tree(c)
        val d: File? = if (tree != null) {
            val uuid = treeUuid(tree)
            c.getExternalFilesDirs(null).filterNotNull().firstOrNull { it.path.contains("/$uuid/", true) }
                ?: if (Build.VERSION.SDK_INT >= 30)
                    c.getSystemService(StorageManager::class.java).storageVolumes
                        .firstOrNull { it.uuid.equals(uuid, true) }?.directory
                else null
        } else dir(c)
        d?.let { val s = StatFs(it.path); s.availableBytes to s.totalBytes }
    } catch (e: Exception) { null }

    fun open(c: Context, name: String, mime: String): Out {
        check(c)?.let { throw IOException(it) }
        val tree = Prefs.tree(c)
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(c, tree)
            if (dir == null || !dir.exists() || !dir.canWrite())
                throw IOException("USB folder not available. Plug in the USB or re-select the folder in Settings")
            dir.findFile(name)?.delete()
            val f = dir.createFile(mime, name) ?: throw IOException("Cannot create file on USB")
            val pfd = c.contentResolver.openFileDescriptor(f.uri, "rw") ?: throw IOException("Cannot open file on USB")
            return Out(pfd) { f.delete() }
        }
        val d = dir(c) ?: throw IOException("No USB drive found. Open Settings and press Use USB drive")
        val file = File(d, name)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return Out(pfd) { file.delete() }
    }
}

class NpDownloader : Downloader() {
    override fun execute(request: NpRequest): NpResponse {
        val b = okhttp3.Request.Builder().url(request.url())
        b.header("User-Agent", UA)
        for ((k, vs) in request.headers()) { b.removeHeader(k); for (v in vs) b.addHeader(k, v) }
        val data = request.dataToSend()
        val m = request.httpMethod()
        val body = when {
            data != null -> data.toRequestBody()
            m == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        b.method(m, body)
        Http.client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object Np {
    private var ok = false
    @Synchronized fun init() {
        if (!ok) { NewPipe.init(NpDownloader(), Localization("en", "US")); ok = true }
    }
}

object Dev {
    fun isTv(c: Context): Boolean {
        val m = c.getSystemService(UiModeManager::class.java)
        return m?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !c.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}

object Library {
    class Item(val name: String, val uri: Uri, val size: Long, val modified: Long)
    private val exts = setOf("mp4", "m4a", "mkv", "webm")
    private fun ok(n: String?) = n != null && n.substringAfterLast('.', "").lowercase() in exts

    fun list(c: Context): List<Item> {
        val tree = Prefs.tree(c)
        val out = ArrayList<Item>()
        if (tree != null) {
            DocumentFile.fromTreeUri(c, tree)?.listFiles()?.forEach {
                if (it.isFile && ok(it.name)) out.add(Item(it.name!!, it.uri, it.length(), it.lastModified()))
            }
        } else {
            Usb.dir(c)?.listFiles()?.forEach {
                if (it.isFile && ok(it.name)) out.add(Item(it.name, Uri.fromFile(it), it.length(), it.lastModified()))
            }
        }
        return out.sortedByDescending { it.modified }
    }
}

/** Backup file kept next to the videos (USB / SD card). Survives uninstall + reinstall. */
object Backup {
    private const val NAME = "AdvancePlay-backup.json"

    @Synchronized fun save(c: Context): Boolean {
        try {
            val ts = System.currentTimeMillis()
            val txt = JSONObject().apply {
                put("total", Prefs.total(c)); put("cap", Prefs.cap(c)); put("quality", Prefs.quality(c))
                put("max", Prefs.maxSpeed(c)); put("layout", Prefs.layout(c)); put("lastDone", Prefs.lastDone(c))
                put("updated", ts)
            }.toString()
            val tree = Prefs.tree(c)
            if (tree != null) {
                val dir = DocumentFile.fromTreeUri(c, tree) ?: return false
                if (!dir.canWrite()) return false
                val f = dir.findFile(NAME) ?: dir.createFile("application/json", NAME) ?: return false
                c.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(txt.toByteArray()) } ?: return false
            } else {
                val d = Usb.dir(c) ?: return false
                File(d, NAME).writeText(txt)
            }
            Prefs.setBkTs(c, ts)
            return true
        } catch (e: Exception) { return false }
    }

    /** Returns true when data was restored from an existing backup. */
    @Synchronized fun restore(c: Context): Boolean {
        try {
            val tree = Prefs.tree(c)
            val txt: String = if (tree != null) {
                val f = DocumentFile.fromTreeUri(c, tree)?.findFile(NAME) ?: return false
                c.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes()) } ?: return false
            } else {
                val f = File(Usb.dir(c) ?: return false, NAME)
                if (!f.exists()) return false
                f.readText()
            }
            val j = JSONObject(txt)
            val ts = j.optLong("updated", 0)
            if (ts <= Prefs.bkTs(c)) return false
            Prefs.setTotal(c, maxOf(Prefs.total(c), j.optLong("total", 0)))
            if (j.optLong("cap", 0) > Prefs.cap(c)) Prefs.setCap(c, j.optLong("cap"))
            Prefs.setQuality(c, j.optInt("quality", 0))
            Prefs.setMaxSpeed(c, j.optBoolean("max", false))
            Prefs.setLayout(c, j.optInt("layout", 0))
            if (Prefs.lastDone(c).isEmpty()) Prefs.setLastDone(c, j.optString("lastDone", ""))
            Prefs.setBkTs(c, ts)
            Throttle.maxSpeed = Prefs.maxSpeed(c)
            return true
        } catch (e: Exception) { return false }
    }
}

/** Storage status for the screens, refreshed on a background thread so the TV never freezes on slow USB access. */
object UsbState {
    @Volatile var space: Pair<Long, Long>? = null
    @Volatile var err: String? = null
    private var busy = false
    private var last = 0L
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Synchronized fun refresh(c: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (busy || (!force && now - last < 5000)) return
        busy = true; last = now
        val app = c.applicationContext
        pool.execute {
            try { err = Usb.check(app); space = Usb.space(app) } catch (_: Exception) {}
            finally { busy = false }
        }
    }
}
