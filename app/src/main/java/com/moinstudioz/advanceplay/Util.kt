package com.moinstudioz.advanceplay

import android.Manifest
import android.app.UiModeManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    /** Choosing a picker folder (non-null) switches off the other two modes. */
    fun setTree(c: Context, u: Uri?) {
        val e = p(c).edit().putString("tree", u?.toString())
        if (u != null) e.remove("media")
        e.apply()
    }
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
    /** Choosing a direct folder path (non-null) switches off MediaStore mode. */
    fun setDirect(c: Context, v: String?) {
        val e = p(c).edit().putString("direct", v)
        if (v != null) e.remove("media")
        e.apply()
    }
    /** MediaStore volume name of the USB drive (for example "7245-1da0"), or null when MediaStore mode is not used. */
    fun media(c: Context): String? = p(c).getString("media", null)
    /** Choosing MediaStore mode (non-null) switches off the direct path and the picker folder. */
    fun setMedia(c: Context, v: String?) {
        val e = p(c).edit().putString("media", v)
        if (v != null) e.remove("direct").remove("tree")
        e.apply()
    }
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
    /**
     * An open file we are saving into.
     * [delete] removes a half-finished file. [finish] is called once the download succeeded:
     * only the MediaStore mode uses it (it clears the "pending" flag so the video becomes visible); other modes do nothing.
     */
    class Out(val pfd: ParcelFileDescriptor, val delete: () -> Unit, val finish: () -> Unit = {})
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

    /**
     * "Use USB drive": finds the plugged-in drive and the first folder on it we are allowed to write to.
     * If no folder works (some TVs), it falls back to MediaStore mode (Android 10 and newer).
     */
    fun setupDirect(c: Context): Setup {
        val roots = volumeRoots(c)
        val mediaVols = mediaVolumes(c)
        if (roots.isEmpty() && mediaVols.isEmpty()) {
            Prefs.setDiag(c, "No USB drive found by the app.")
            return Setup(false, "No USB drive found. Plug it in and try again.")
        }
        val log = StringBuilder("Tried these places:\n")
        for (d in candidates(c)) {
            val e = tryWrite(d)
            if (e == null) { Prefs.setDirect(c, d.path); Prefs.setTree(c, null); Prefs.setDiag(c, ""); return Setup(true, d.path) }
            log.append(d.path.removePrefix("/storage/")).append("  ->  ").append(e.take(70)).append('\n')
        }
        setupMedia(c, mediaVols, log)?.let { return it }
        Prefs.setDiag(c, log.toString())
        val needPerm = if (Build.VERSION.SDK_INT >= 30) !Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        return Setup(false, "The TV would not let the app save on this USB drive. Details are shown on the screen.", needPerm)
    }

    // ---------------------------------------------------------------------------------------------
    // MediaStore mode: saves through Android's media database using the volume's real name
    // (for example "7245-1da0"), because some TVs reject plain file paths such as /storage/sda1.
    // ---------------------------------------------------------------------------------------------

    /** Short readable text of an error, to show on the screen. */
    private fun why(e: Throwable): String = (e.javaClass.simpleName + ": " + (e.message ?: "")).trim().take(160)

    /** Names MediaStore uses for the plugged-in USB drives / SD cards (never the TV's own memory). Android 10+ only. */
    fun mediaVolumes(c: Context): List<String> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = LinkedHashSet<String>()
        try {
            c.getSystemService(StorageManager::class.java).storageVolumes.forEach { v ->
                val n = v.mediaStoreVolumeName
                if (n != null && (v.isRemovable || !v.isPrimary) && v.state == Environment.MEDIA_MOUNTED) out.add(n)
            }
        } catch (_: Exception) {}
        try {
            MediaStore.getExternalVolumeNames(c).forEach { out.add(it) }
        } catch (_: Exception) {}
        out.removeAll(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY, "external", "internal"))
        return out.toList()
    }

    /** True when this MediaStore volume is plugged in and mounted right now. */
    fun mediaAttached(c: Context, vol: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val listed = MediaStore.getExternalVolumeNames(c).any { it.equals(vol, true) }
            if (!listed) false
            else {
                val sv = c.getSystemService(StorageManager::class.java).storageVolumes
                    .firstOrNull { it.mediaStoreVolumeName.equals(vol, true) }
                sv == null || sv.state == Environment.MEDIA_MOUNTED
            }
        } catch (_: Exception) { false }
    }

    /** Folder of the USB drive on the TV's file system (used only for free space and the optional re-scan). */
    fun mediaVolumeDir(c: Context, vol: String): File? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val sv = c.getSystemService(StorageManager::class.java).storageVolumes
                    .firstOrNull { it.mediaStoreVolumeName.equals(vol, true) }
                val d = sv?.let { volDir(it) }
                if (d != null) return d
            }
        } catch (_: Exception) {}
        volumeRoots(c).firstOrNull { it.name.equals(vol, true) }?.let { return it }
        for (n in listOf(vol, vol.uppercase())) {
            val f = File("/storage/$n")
            if (f.isDirectory) return f
        }
        return null
    }

    /** Runs a MediaStore query. includePending = also return files that are still being written (Android 12+). */
    fun mediaQuery(
        c: Context, coll: Uri, proj: Array<String>, sel: String, args: Array<String>,
        includePending: Boolean = false, sort: String? = null
    ): Cursor? {
        if (includePending && Build.VERSION.SDK_INT >= 31) {
            val b = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            return c.contentResolver.query(coll, proj, b, null)
        }
        return c.contentResolver.query(coll, proj, sel, args, sort)
    }

    /** ids of the files in this collection with exactly this name inside this folder (e.g. "Movies/AdvancePlay"). */
    fun mediaFind(c: Context, coll: Uri, name: String, rel: String, includePending: Boolean): List<Long> {
        val out = ArrayList<Long>()
        val sel = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND (" +
            MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?)"
        mediaQuery(c, coll, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf(name, rel, "$rel/"), includePending)?.use { cu ->
            while (cu.moveToNext()) out.add(cu.getLong(0))
        }
        return out
    }

    /** Real test: creates a tiny file on the volume through MediaStore, writes to it, deletes it. null = works, else the reason. */
    private fun testMedia(c: Context, vol: String): String? {
        var uri: Uri? = null
        var err: String? = null
        val cr = c.contentResolver
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "ap_test_" + System.currentTimeMillis() + ".mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/AdvancePlay")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = cr.insert(MediaStore.Video.Media.getContentUri(vol), cv)
            if (uri == null) throw IOException("MediaStore did not create the test file")
            val pfd = cr.openFileDescriptor(uri, "w") ?: throw IOException("MediaStore could not open the test file")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(ByteArray(16) { 1 }) }
        } catch (e: Exception) {
            err = why(e)
        } finally {
            if (uri != null) { try { cr.delete(uri, null, null) } catch (_: Exception) {} }
        }
        return err
    }

    /** Tries every USB volume MediaStore knows; the first one that really accepts a test file becomes the saving target. */
    private fun setupMedia(c: Context, vols: List<String>, log: StringBuilder): Setup? {
        if (Build.VERSION.SDK_INT < 29) return null
        log.append("MediaStore volumes: ").append(if (vols.isEmpty()) "none" else vols.joinToString(", ")).append('\n')
        for (v in vols) {
            val e = testMedia(c, v)
            if (e == null) {
                Prefs.setMedia(c, v); Prefs.setDiag(c, "")
                Thread { scanFolder(c) }.start()
                return Setup(true, "USB drive ($v)")
            }
            log.append("MediaStore ").append(v).append("  ->  ").append(e).append('\n')
        }
        return null
    }

    /**
     * Best effort: asks Android to index videos that are already inside the AdvancePlay folders on the drive.
     * Limitation: on a TV that cannot use file paths this may do nothing, and a video that was copied onto the drive
     * by hand (never indexed) will then not show in the Library. Videos downloaded by the app always show.
     */
    fun scanFolder(c: Context) {
        try {
            val vol = Prefs.media(c) ?: return
            val root = mediaVolumeDir(c, vol) ?: return
            val files = ArrayList<String>()
            for (rel in listOf("Movies/AdvancePlay", "Music/AdvancePlay")) {
                File(root, rel).listFiles()?.forEach { if (it.isFile && Library.ok(it.name)) files.add(it.path) }
            }
            if (files.isNotEmpty()) MediaScannerConnection.scanFile(c, files.toTypedArray(), null, null)
        } catch (_: Exception) {}
    }

    /** Silent: on every start (and after a reinstall) find the USB folder again by itself and restore the backup. */
    fun autoSetup(c: Context) {
        if (Prefs.tree(c) != null) return
        val med = Prefs.media(c)
        if (med != null) {
            // MediaStore mode: nothing to do while the same drive is still plugged in
            if (mediaAttached(c, med)) { Thread { scanFolder(c) }.start(); return }
        } else {
            val cur = Prefs.direct(c)
            if (cur != null && File(cur).isDirectory) return
        }
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
        val med = Prefs.media(c)
        if (med != null)   // MediaStore mode: fine while the drive is plugged in and mounted
            return if (mediaAttached(c, med)) null else "No USB drive found. Plug it in, then open Settings and press Use USB drive"
        val d = dir(c) ?: return if (Dev.isTv(c)) "No USB drive found. Plug it in, then open Settings and press Use USB drive"
        else "Insert an SD card or choose a download folder in Settings"
        return if (writable(d)) null else "Cannot save to the USB yet. Open Settings and press Use USB drive"
    }

    fun hasTarget(c: Context) = check(c) == null

    /**
     * Where the two half files (video + sound) are kept before they are merged.
     * MediaStore mode cannot use a plain folder on the drive, so we use the app's own folder on the USB
     * when it is writable AND has at least 2 GB free; otherwise the app's cache folder in TV memory.
     */
    fun tempDir(c: Context): File {
        if (Prefs.media(c) != null) {
            val usb = appDir(c)?.takeIf { writable(it) && it.usableSpace >= 2L * 1024 * 1024 * 1024 }
            return usb ?: c.cacheDir
        }
        return dir(c)?.takeIf { writable(it) } ?: c.cacheDir
    }

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
        } else if (Prefs.media(c) != null) {
            // MediaStore mode: free space of the USB volume whose MediaStore name we saved
            val m = Prefs.media(c)!!
            if (mediaAttached(c, m)) mediaVolumeDir(c, m) else null
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
            return Out(pfd, { f.delete() })
        }
        val med = Prefs.media(c)
        if (med != null) return openMedia(c, med, name, mime)
        val d = dir(c) ?: throw IOException("No USB drive found. Open Settings and press Use USB drive")
        val file = File(d, name)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return Out(pfd, { file.delete() })
    }

    /** USB drive (MediaStore) mode: removes our file with this name through MediaStore. True when something was deleted. */
    fun mediaDeleteByName(c: Context, name: String): Boolean {
        val vol = Prefs.media(c) ?: return false
        if (Build.VERSION.SDK_INT < 29) return false
        var n = 0
        for (coll in listOf(MediaStore.Video.Media.getContentUri(vol), MediaStore.Audio.Media.getContentUri(vol), MediaStore.Files.getContentUri(vol))) {
            try {
                n += c.contentResolver.delete(coll, MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " + MediaStore.MediaColumns.DATA + " LIKE ?",
                    arrayOf(name, "%/AdvancePlay/%"))
            } catch (_: Exception) {}
        }
        return n > 0
    }

    private fun isAudioName(n: String) = n.substringAfterLast('.', "").lowercase() == "m4a"

    /**
     * MediaStore mode: creates the file through MediaStore (videos in Movies/AdvancePlay, audio in Music/AdvancePlay)
     * and returns an open file for the download engine. The file stays "pending" (invisible) until finish() is called.
     */
    private fun openMedia(c: Context, vol: String, name: String, mime: String): Out {
        if (Build.VERSION.SDK_INT < 29) throw IOException("USB drive mode needs Android 10 or newer")
        val cr = c.contentResolver
        try {
            val audio = isAudioName(name)
            val coll = if (audio) MediaStore.Audio.Media.getContentUri(vol) else MediaStore.Video.Media.getContentUri(vol)
            val rel = if (audio) "Music/AdvancePlay" else "Movies/AdvancePlay"
            val type = if (audio) (if (mime.startsWith("audio/")) mime else "audio/mp4")
                       else (if (mime.startsWith("video/")) mime else "video/mp4")
            // remove an older file with the same name first, otherwise Android would call the new one "name (1).mp4"
            try {
                for (id in mediaFind(c, coll, name, rel, true)) {
                    try { cr.delete(ContentUris.withAppendedId(coll, id), null, null) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(coll, cv) ?: throw IOException("The TV would not create the file on the USB drive")
            val pfd: ParcelFileDescriptor? = try {
                try { cr.openFileDescriptor(uri, "rw") } catch (e: Exception) { cr.openFileDescriptor(uri, "w") }
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
            if (pfd == null) {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
                throw IOException("Cannot open file on USB")
            }
            return Out(
                pfd,
                { try { cr.delete(uri, null, null) } catch (_: Exception) {} },
                {
                    // download finished: clear the pending flag so the video shows up in the Library
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (cr.update(uri, done, null, null) <= 0) throw IOException("Could not finish the file on the USB drive")
                }
            )
        } catch (e: Exception) {
            val m = why(e)
            Prefs.setDiag(c, "Saving to the USB drive (MediaStore) failed:\n$m")
            throw IOException(m)
        }
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
    /** True for file names the Library shows (mp4, m4a, mkv, webm). */
    fun ok(n: String?) = n != null && n.substringAfterLast('.', "").lowercase() in exts

    fun list(c: Context): List<Item> {
        val tree = Prefs.tree(c)
        val media = Prefs.media(c)
        val out = ArrayList<Item>()
        if (tree != null) {
            DocumentFile.fromTreeUri(c, tree)?.listFiles()?.forEach {
                if (it.isFile && ok(it.name)) out.add(Item(it.name!!, it.uri, it.length(), it.lastModified()))
            }
        } else if (media != null) {
            listMedia(c, media, out)
        } else {
            Usb.dir(c)?.listFiles()?.forEach {
                if (it.isFile && ok(it.name)) out.add(Item(it.name, Uri.fromFile(it), it.length(), it.lastModified()))
            }
        }
        return out.sortedByDescending { it.modified }
    }

    /**
     * USB drive (MediaStore) mode: lists our finished videos and audio files. Three sources are combined, because some TVs
     * do not fill in every MediaStore field: (1) the Video and Audio lists, (2) the "Files" list, (3) the AdvancePlay folders
     * on the drive itself. A file found by several sources is shown once. If nothing is found, the counts are written to the
     * Settings screen so they can be photographed.
     */
    private fun listMedia(c: Context, vol: String, out: MutableList<Item>) {
        val byName = LinkedHashMap<String, Item>()
        fun put(n: String, uri: Uri, size: Long, mod: Long) {
            val k = n.lowercase()
            val old = byName[k]
            if (old == null) byName[k] = Item(n, uri, size, mod)
            else if (old.size <= 0L || old.modified <= 0L)          // keep the first link, but fill in missing size / date
                byName[k] = Item(old.name, old.uri, if (old.size > 0L) old.size else size, if (old.modified > 0L) old.modified else mod)
        }
        var nStore = 0; var nFiles = 0; var nFolder = 0
        var problem: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val proj = arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                val sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.DATA + " LIKE ?) AND " +
                    MediaStore.MediaColumns.IS_PENDING + " = 0"
                val sets = listOf(
                    Triple(MediaStore.Video.Media.getContentUri(vol), "Movies/AdvancePlay", 0),
                    Triple(MediaStore.Audio.Media.getContentUri(vol), "Music/AdvancePlay", 0),
                    Triple(MediaStore.Files.getContentUri(vol), "%AdvancePlay", 1)
                )
                for ((coll, rel, kind) in sets) {
                    try {
                        Usb.mediaQuery(c, coll, proj, sel, arrayOf("$rel%", "%/AdvancePlay/%"))?.use { cu ->
                            while (cu.moveToNext()) {
                                val name = cu.getString(1)
                                if (!ok(name)) continue
                                put(name, ContentUris.withAppendedId(coll, cu.getLong(0)), cu.getLong(2), cu.getLong(3) * 1000)
                                if (kind == 0) nStore++ else nFiles++
                            }
                        }
                    } catch (e: Exception) { problem = (e.message ?: e.javaClass.simpleName).take(120) }
                }
            } catch (e: Exception) { problem = (e.message ?: e.javaClass.simpleName).take(120) }
        }
        try {
            val root = Usb.mediaVolumeDir(c, vol)
            if (root != null) for (rel in listOf("Movies/AdvancePlay", "Music/AdvancePlay")) {
                File(root, rel).listFiles()?.forEach { f ->
                    if (f.isFile && !f.name.startsWith(".") && ok(f.name)) { put(f.name, Uri.fromFile(f), f.length(), f.lastModified()); nFolder++ }
                }
            }
        } catch (_: Exception) {}
        out.addAll(byName.values)
        val d = Prefs.diag(c)
        if (out.isEmpty()) Prefs.setDiag(c, "Library found nothing on the USB drive.\nMediaStore videos/audio: $nStore, files list: $nFiles, folder: $nFolder" +
            (if (problem != null) "\nError: $problem" else ""))
        else if (d.startsWith("Library found nothing")) Prefs.setDiag(c, "")
    }
}

/** Backup file kept next to the videos (USB / SD card). Survives uninstall + reinstall. */
object Backup {
    private const val NAME = "AdvancePlay-backup.json"
    private const val REL = "Download/AdvancePlay"      // MediaStore mode: folder on the USB drive

    @Synchronized fun save(c: Context): Boolean {
        try {
            val ts = System.currentTimeMillis()
            val txt = JSONObject().apply {
                put("total", Prefs.total(c)); put("cap", Prefs.cap(c)); put("quality", Prefs.quality(c))
                put("max", Prefs.maxSpeed(c)); put("layout", Prefs.layout(c)); put("lastDone", Prefs.lastDone(c))
                put("updated", ts)
                put("history", History.export(c)); put("recent", Recents.export(c))   // downloaded / watched videos and recent searches
            }.toString()
            val tree = Prefs.tree(c)
            if (tree != null) {
                val dir = DocumentFile.fromTreeUri(c, tree) ?: return false
                if (!dir.canWrite()) return false
                val f = dir.findFile(NAME) ?: dir.createFile("application/json", NAME) ?: return false
                c.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(txt.toByteArray()) } ?: return false
            } else if (Prefs.media(c) != null) {
                if (!saveMedia(c, Prefs.media(c)!!, txt)) return false
            } else {
                val d = Usb.dir(c) ?: return false
                File(d, NAME).writeText(txt)
            }
            Prefs.setBkTs(c, ts)
            return true
        } catch (e: Exception) { return false }
    }

    /**
     * MediaStore mode: writes the backup into Download/AdvancePlay on the USB drive.
     * First tries to overwrite the existing file; if that is not allowed it deletes the old one and creates a new one.
     * Never throws: returns false when it did not work.
     */
    private fun saveMedia(c: Context, vol: String, txt: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        try {
            val cr = c.contentResolver
            val coll = MediaStore.Downloads.getContentUri(vol)
            val ids = Usb.mediaFind(c, coll, NAME, REL, true)
            for (id in ids) {
                try {
                    val os = cr.openOutputStream(ContentUris.withAppendedId(coll, id), "wt")
                    if (os != null) { os.use { it.write(txt.toByteArray()) }; return true }
                } catch (_: Exception) {}
            }
            for (id in ids) { try { cr.delete(ContentUris.withAppendedId(coll, id), null, null) } catch (_: Exception) {} }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(coll, cv) ?: return false
            try {
                val os = cr.openOutputStream(uri, "w") ?: throw IOException("cannot open backup file")
                os.use { it.write(txt.toByteArray()) }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(uri, done, null, null)
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
                return false
            }
            return true
        } catch (e: Exception) { return false }
    }

    /** MediaStore mode: reads the newest backup text from Download/AdvancePlay on the USB drive, or null (also after a reinstall if Android refuses). */
    private fun readMedia(c: Context, vol: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        try {
            val coll = MediaStore.Downloads.getContentUri(vol)
            val sel = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND (" + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " +
                MediaStore.MediaColumns.RELATIVE_PATH + " = ?) AND " + MediaStore.MediaColumns.IS_PENDING + " = 0"
            var id = -1L
            Usb.mediaQuery(c, coll, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf(NAME, REL, "$REL/"), false,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cu -> if (cu.moveToFirst()) id = cu.getLong(0) }
            if (id < 0) return null
            return c.contentResolver.openInputStream(ContentUris.withAppendedId(coll, id))?.use { String(it.readBytes()) }
        } catch (e: Exception) { return null }
    }

    /** Returns true when data was restored from an existing backup. */
    @Synchronized fun restore(c: Context): Boolean {
        try {
            val tree = Prefs.tree(c)
            val txt: String = if (tree != null) {
                val f = DocumentFile.fromTreeUri(c, tree)?.findFile(NAME) ?: return false
                c.contentResolver.openInputStream(f.uri)?.use { String(it.readBytes()) } ?: return false
            } else if (Prefs.media(c) != null) {
                readMedia(c, Prefs.media(c)!!) ?: return false
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
            History.import(c, j.optJSONObject("history")); Recents.import(c, j.optJSONArray("recent"))
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
