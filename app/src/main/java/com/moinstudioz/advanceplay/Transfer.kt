package com.moinstudioz.advanceplay

import android.content.ContentUris
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A tiny web server inside the app. A phone or computer on the same Wi-Fi opens http://<TV address>:<port>, types the PIN
 * shown on the TV, then can (1) send video links that the TV downloads and (2) upload files to the TV's storage.
 * It runs only while the "Send to TV" screen is open.
 */
class TransferServer(private val c: Context, val pin: String, private val sniffer: ((String) -> SniffResult?)? = null) {
    /** The app logo, shown on the phone page. */
    private val logoBytes: ByteArray by lazy {
        try { c.resources.openRawResource(R.drawable.ic_launcher_foreground).use { it.readBytes() } } catch (_: Exception) { ByteArray(0) }
    }

    private var ss: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var port = 0
    @Volatile private var running = false
    @Volatile private var upName = ""
    @Volatile private var upDone = 0L
    @Volatile private var upTotal = 0L
    /** What arrived (newest last), shown on the TV and on the web page. */
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun note(t: String) { log.add(SimpleDateFormat("HH:mm", Locale.US).format(Date()) + "  " + t); while (log.size > 12) log.removeAt(0) }

    fun start(): Boolean {
        for (p in 8080..8090) {
            try { ss = ServerSocket(p); port = p; break } catch (_: IOException) {}
        }
        val s = ss ?: return false
        running = true
        pool.execute {
            while (running) {
                try { val k = s.accept(); pool.execute { handle(k) } } catch (_: Exception) { if (!running) break }
            }
        }
        return true
    }

    fun stop() {
        for (v in sessions.values) if (!v.done) { runCatching { v.ch.close() }; v.out.delete() }
        running = false
        try { ss?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun readLine(ins: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun send(out: java.io.OutputStream, code: Int, type: String, body: ByteArray) {
        val text = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 411 -> "Length Required"; else -> "Error" }
        out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.size +
            "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun json(out: java.io.OutputStream, code: Int, o: JSONObject) = send(out, code, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 60000
            val ins = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val out = s.getOutputStream()
            val req = readLine(ins) ?: return
            val parts = req.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val path = target.substringBefore('?')
            val query = HashMap<String, String>()
            for (kv in target.substringAfter('?', "").split('&')) {
                if (kv.isEmpty()) continue
                query[URLDecoder.decode(kv.substringBefore('='), "UTF-8")] = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }
            val pinOk = headers["x-pin"] == pin
            when {
                method == "GET" && path == "/" -> send(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
                method == "GET" && path == "/api/status" -> json(out, 200, status())
                method == "GET" && path == "/logo.png" -> send(out, 200, "image/png", logoBytes)
                method == "GET" && path == "/api/ls" -> {
                    if (!(pinOk || query["pin"] == pin)) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    json(out, 200, listDir(query["root"] ?: "int", query["path"] ?: ""))
                }
                method == "GET" && path == "/api/get" -> {
                    if (!(pinOk || query["pin"] == pin)) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    sendFile(out, query["root"] ?: "int", query["path"] ?: "", query["id"], query["u"])
                }
                method == "POST" && path == "/api/chunk" -> {
                    if (!pinOk) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    json(out, 200, chunk(ins, headers, query))
                }
                method == "POST" && path == "/api/signin" -> {
                    if (!pinOk) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 4000)
                    val buf = ByteArray(len)
                    var got = 0
                    while (got < len) { val n = ins.read(buf, got, len - got); if (n < 0) break; got += n }
                    val o = JSONObject(String(buf, 0, got, Charsets.UTF_8))
                    val ok = Vault.save(c, o.optString("user").trim(), o.optString("pass"))
                    note(if (ok) "Sign-in saved on the TV" else "Could not save the sign-in")
                    json(out, 200, JSONObject().put("ok", ok))
                }
                method == "POST" && path == "/api/queue" -> {
                    if (!pinOk) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 200_000)
                    val buf = ByteArray(len)
                    var got = 0
                    while (got < len) { val n = ins.read(buf, got, len - got); if (n < 0) break; got += n }
                    val links = Regex("https?://\\S+").findAll(String(buf, 0, got, Charsets.UTF_8)).map { it.value }.distinct().toList()
                    val res = JSONArray()
                    for (l in links.take(50)) res.put(JSONObject().put("link", l).put("msg", queueOne(l)))
                    json(out, 200, JSONObject().put("results", res))
                }
                method == "POST" && path == "/api/upload" -> {
                    if (!pinOk) { json(out, 403, JSONObject().put("error", "Wrong PIN")); return }
                    val len = headers["content-length"]?.toLongOrNull() ?: -1L
                    if (len < 0) { json(out, 411, JSONObject().put("error", "No file size")); return }
                    val name = (query["name"] ?: "upload.bin").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "upload.bin" }
                    upload(ins, len, name)
                    json(out, 200, JSONObject().put("ok", true).put("name", name).put("size", len))
                }
                else -> send(out, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (e: Exception) {
            try {
                json(s.getOutputStream(), 400, JSONObject().put("error", (e.message ?: e.javaClass.simpleName).take(200)))
            } catch (_: Exception) {}
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    // ---------- the TV's own files (internal storage and USB), to download on the phone ----------

    private fun rootDir(root: String): File? = when (root) {
        "int" -> Environment.getExternalStorageDirectory()
        "usb" -> Prefs.media(c)?.let { Usb.mediaVolumeDir(c, it) } ?: Usb.volumeRoots(c).firstOrNull()
        else -> null
    }

    /** The file inside [base] for the relative path [rel]; null when the path tries to leave the storage. */
    private fun safeFile(base: File, rel: String): File? {
        val f = File(base, rel).canonicalFile
        val b = base.canonicalFile
        return if (f.path == b.path || f.path.startsWith(b.path + "/")) f else null
    }

    private fun listDir(root: String, rel: String): JSONObject {
        val o = JSONObject().put("root", root).put("path", rel)
        if (root == "usbm") return listUsbMedia(o)
        if (root == "int" && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            return o.put("error", "\"All files access\" is switched off for Advance Play. On the TV: Settings > Apps > Advance Play > Permissions > All files access.")
        val base = rootDir(root) ?: return o.put("error", "No storage found")
        val dir = safeFile(base, rel) ?: return o.put("error", "Not allowed")
        val files = dir.listFiles()
        if (files == null) {
            // this TV blocks reading the USB folders directly: show the media list instead
            if (root == "usb") return listUsbMedia(o).put("note", "This TV does not let apps read the USB folders, so this is the list of files Android knows on the drive, plus the files this app saved.")
            return o.put("error", "Cannot read this folder")
        }
        val arr = JSONArray()
        for (f in files.filter { !it.name.startsWith(".") }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).take(1500)) {
            arr.put(JSONObject().put("n", f.name).put("d", f.isDirectory).put("s", if (f.isFile) f.length() else 0L).put("m", f.lastModified()))
        }
        return o.put("entries", arr)
    }

    /** USB (MediaStore) mode: a flat list of the media files Android knows on the drive, newest first. */
    private fun listUsbMedia(o: JSONObject): JSONObject {
        val vol = Prefs.media(c) ?: return o.put("error", "The USB media list works in USB drive (MediaStore) mode only")
        if (Build.VERSION.SDK_INT < 29) return o.put("error", "Needs Android 10 or newer")
        val coll = MediaStore.Files.getContentUri(vol)
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH
        )
        val sel = MediaStore.MediaColumns.IS_PENDING + " = 0 AND " + MediaStore.MediaColumns.SIZE + " > 0"
        val arr = JSONArray()
        val seen = HashSet<String>()
        // files this app saved (their exact links were remembered)
        for (r in History.libList(c)) {
            seen.add(r.name.lowercase())
            arr.put(JSONObject().put("n", r.name).put("d", false).put("s", r.size).put("m", r.at).put("u", r.uri))
        }
        try {
            Usb.mediaQuery(c, coll, proj, sel, emptyArray<String>(), false, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cu ->
                var n = 0
                while (cu.moveToNext() && n < 800) {
                    val nm = cu.getString(1) ?: ""
                    if (seen.contains(nm.lowercase())) continue
                    arr.put(JSONObject().put("n", (cu.getString(4) ?: "") + nm).put("d", false)
                        .put("s", cu.getLong(2)).put("m", cu.getLong(3) * 1000).put("id", cu.getLong(0)))
                    n++
                }
            }
        } catch (e: Exception) { o.put("note", "Media list problem: " + (e.message ?: e.javaClass.simpleName).take(80)) }
        return o.put("entries", arr)
    }

    /** Sends one file of the TV to the phone (as a download). */
    private fun sendFile(out: OutputStream, root: String, rel: String, id: String?, uriParam: String?) {
        val name: String
        val len: Long
        val src: InputStream
        if (root == "usbm" && uriParam != null) {
            // a file this app saved: only links that the app itself remembered are allowed
            val rec = History.libList(c).firstOrNull { it.uri == uriParam } ?: throw IOException("Unknown file")
            val uri = Uri.parse(rec.uri)
            src = c.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            name = rec.name; len = if (rec.size > 0) rec.size else -1L
        } else if (root == "usbm") {
            val vol = Prefs.media(c) ?: throw IOException("No USB drive")
            val rowId = id?.toLongOrNull() ?: throw IOException("Bad file number")
            val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(vol), rowId)
            var nm = "file"
            var sz = -1L
            c.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { cu ->
                if (cu.moveToFirst()) { nm = cu.getString(0) ?: "file"; sz = cu.getLong(1) }
            }
            src = c.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
            name = nm; len = sz
        } else {
            val base = rootDir(root) ?: throw IOException("No storage found")
            val f = safeFile(base, rel) ?: throw IOException("Not allowed")
            if (!f.isFile) throw IOException("Not a file")
            name = f.name; len = f.length(); src = FileInputStream(f)
        }
        src.use { ins ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val head = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\n" + (if (len >= 0) "Content-Length: " + len + "\r\n" else "") +
                "Content-Disposition: attachment; filename*=UTF-8''" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
                "\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.flush()
        }
        note("Sent to phone: $name")
    }

    // ---------- big uploads: the phone sends the file in 4 MB pieces (3 at a time); a piece that fails is simply sent again ----------

    private class UpSession(val out: Usb.Out, val ch: FileChannel, val total: Long, val name: String) {
        val got = ConcurrentHashMap<Int, Boolean>()
        val bytes = AtomicLong(0)
        @Volatile var last = System.currentTimeMillis()
        @Volatile var done = false
    }
    private val sessions = ConcurrentHashMap<String, UpSession>()

    private fun openSession(name: String, total: Long): UpSession {
        // forget uploads that stopped long ago (and finished ones after a while)
        val now = System.currentTimeMillis()
        for ((k, v) in sessions.entries.toList()) {
            if (v.done && now - v.last > 10 * 60_000) sessions.remove(k)
            else if (!v.done && now - v.last > 20 * 60_000) { sessions.remove(k); runCatching { v.ch.close() }; v.out.delete() }
        }
        val free = Usb.space(c)
        if (free != null && free.first < total) throw IOException("Not enough free space on the TV")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val o = Usb.openAny(c, name, mime)
        return UpSession(o, ParcelFileDescriptor.AutoCloseOutputStream(o.pfd).channel, total, name)
    }

    private fun chunk(ins: BufferedInputStream, headers: Map<String, String>, query: Map<String, String>): JSONObject {
        val id = query["id"] ?: throw IOException("No upload id")
        val len = headers["content-length"]?.toLongOrNull() ?: throw IOException("No size")
        val idx = query["idx"]?.toIntOrNull() ?: throw IOException("No piece number")
        val total = query["total"]?.toLongOrNull() ?: throw IOException("No total size")
        val cs = query["cs"]?.toLongOrNull() ?: throw IOException("No piece size")
        val name = (query["name"] ?: "upload.bin").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "upload.bin" }
        val s = synchronized(sessions) { sessions[id] ?: openSession(name, total).also { sessions[id] = it } }
        if (s.done) return JSONObject().put("ok", true).put("done", true)
        var pos = idx * cs
        var left = len
        val buf = ByteArray(256 * 1024)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("The upload was interrupted")
            val bb = ByteBuffer.wrap(buf, 0, n)
            while (bb.hasRemaining()) pos += s.ch.write(bb, pos)
            left -= n
        }
        s.last = System.currentTimeMillis()
        if (s.got.putIfAbsent(idx, true) == null) s.bytes.addAndGet(len)
        upName = s.name; upTotal = s.total; upDone = s.bytes.get()
        var finishing = false
        if (s.bytes.get() >= s.total) synchronized(s) { if (!s.done) { s.done = true; finishing = true } }
        if (finishing) {
            try {
                runCatching { s.ch.force(false) }
                s.ch.close()
                s.out.finish()
                s.out.uri?.let { if (Library.ok(s.name)) History.addLib(c, s.name, it.toString(), s.total) }
                note("Received file: " + s.name)
            } catch (e: Exception) {
                runCatching { s.ch.close() }
                s.out.delete()
                sessions.remove(id)
                note("Upload failed: " + s.name)
                throw e
            } finally { upName = ""; upDone = 0; upTotal = 0 }
        }
        return JSONObject().put("ok", true).put("done", s.done)
    }

    private fun status(): JSONObject {
        val free = Usb.space(c)
        return JSONObject()
            .put("active", DL.active).put("title", DL.title).put("pct", DL.pct).put("queue", DL.queue)
            .put("last", DL.lastDone).put("err", DL.errMsg)
            .put("upName", upName).put("upDone", upDone).put("upTotal", upTotal)
            .put("free", if (free != null) free.first else -1L)
            .put("log", JSONArray(log.toList()))
    }

    /** Streams the sent file straight into the TV's storage (no copy in the TV's memory). */
    private fun upload(ins: BufferedInputStream, len: Long, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val o = Usb.openAny(c, name, mime)
        upName = name; upDone = 0; upTotal = len
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(o.pfd).use { os ->
                val buf = ByteArray(256 * 1024)
                var left = len
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("The upload was interrupted")
                    os.write(buf, 0, n)
                    left -= n; upDone += n
                }
            }
            o.finish()
            o.uri?.let { if (Library.ok(name)) History.addLib(c, name, it.toString(), len) }      // videos / audio show up in the Library
            note("Received file: $name")
        } catch (e: Exception) {
            runCatching { o.pfd.close() }
            o.delete()
            note("Upload failed: $name")
            throw e
        } finally { upName = ""; upDone = 0; upTotal = 0 }
    }

    /** One link from the page: YouTube link, Facebook / Instagram page link, or a direct video file link. */
    private fun queueOne(link: String): String {
        try {
            if (Dl.canonical(link) != null) {
                val err = Dl.enqueue(c, link)
                note((if (err == null) "Queued (YouTube): " else "Not queued: ") + link.take(60))
                return err ?: "Added to the TV's download queue (YouTube)"
            }
            val host = try { java.net.URI(link).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            val path = link.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            val stamp = SimpleDateFormat("yyMMdd-HHmmss", Locale.US).format(Date())
            val media: String
            val title: String
            var audio: String? = null
            if (ext in setOf("mp4", "mkv", "webm", "m4v", "mov")) {
                media = link
                title = java.net.URLDecoder.decode(path.substringAfterLast('/').substringBeforeLast('.'), "UTF-8").take(70).ifBlank { "video $stamp" }
            } else if (host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") || host.contains("instagram.com")) {
                val label = if (host.contains("instagram")) "Instagram" else "Facebook"
                // 1) open the link in a hidden browser inside the app (signed in like the app's own web screen) and catch the video files
                val sn = try { sniffer?.invoke(link) } catch (_: Exception) { null }
                if (sn != null && sn.video.isEmpty()) {
                    // the page offered a plain video file or a stream instead of Facebook's picture + sound files
                    val m = Universal.enqueue(c, link, sn)
                    note(m.take(60))
                    return m
                }
                if (sn != null) {
                    media = sn.video
                    audio = sn.audio
                    title = sn.title.trim().replace(Regex("\\s+"), " ").take(60).ifBlank { label + " video" } + " [" + label + " " + stamp + "]"
                } else {
                    // 2) ask the website directly
                    val notes = StringBuilder()
                    media = WebGrab.resolve(link, notes) ?: return "Could not find the video. Sign in to " + label + " on the TV first (Apps > " + label +
                        "), then send the link again. " + notes.toString().take(80)
                    title = label + " video " + stamp
                }
            } else {
                // any other website: open the link in the hidden browser and catch the video it plays
                val sn = try { sniffer?.invoke(link) } catch (_: Exception) { null }
                if (sn == null) return "No video found on this page."
                val m = Universal.enqueue(c, link, sn)
                note(m.take(60))
                return m
            }
            val err = Dl.enqueueDirect(c, "web:" + media.substringBefore('?'), media, title, audio)
            note((if (err == null) "Queued: " else "Not queued: ") + title.take(50))
            return err ?: "Added to the TV's download queue"
        } catch (e: Exception) { return "Error: " + (e.message ?: e.javaClass.simpleName).take(100) }
    }

    companion object {
        /** The web page that the phone / computer sees. */
        val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Moin Studioz - Advance Play</title>
<style>
body{margin:0;background:#0b0b0b;color:#eee;font-family:system-ui,Arial,sans-serif}
.w{max-width:680px;margin:0 auto;padding:16px}
.hd{display:flex;align-items:center;gap:12px;margin:4px 0 14px}
.hd img{width:60px;height:60px}
.brand{font-size:24px;font-weight:800;letter-spacing:.5px}
.tag{font-size:12px;color:#8ab4ff;margin-top:2px}
h1{font-size:20px;margin:8px 0 14px;color:#ccc;font-weight:600}
h2{font-size:17px;margin:0 0 6px}
.c{background:#151515;border:1px solid #2a2a2a;border-radius:16px;padding:14px;margin-bottom:14px}
p{color:#aaa;font-size:13px;margin:4px 0 10px}
input[type=text],input[type=password],textarea{width:100%;box-sizing:border-box;background:#0e0e0e;color:#fff;border:1px solid #444;border-radius:10px;padding:10px;font-size:15px}
button{background:#e1306c;color:#fff;border:0;border-radius:22px;padding:11px 20px;font-size:15px;font-weight:bold;margin-top:10px}
button:disabled{opacity:.5}
.lnk{display:inline-block;margin:4px 8px 6px 0;padding:9px 14px;border:1px solid #555;border-radius:18px;color:#fff;text-decoration:none;font-size:14px}
pre{white-space:pre-wrap;color:#9be29b;font-size:13px}
.bar{height:6px;background:#333;border-radius:3px;overflow:hidden;margin:4px 0 10px}
.bar div{height:100%;background:#1db954;width:0}
small{color:#888}
.sm{padding:8px 14px;font-size:13px;margin:4px 6px 4px 0}
.fi{padding:10px 6px;border-bottom:1px solid #262626;font-size:14px;word-break:break-all}
.fi a{color:#8ab4ff;text-decoration:none}
</style></head><body><div class="w">
<div class="hd"><img src="/logo.png" alt=""><div><div class="brand">Moin Studioz</div><div class="tag">Websites &bull; Apps &bull; Solutions</div></div></div>
<h1>Advance Play &middot; Send to TV</h1>
<div class="c"><h2>PIN shown on the TV</h2><input type="text" id="pin" inputmode="numeric" maxlength="4" placeholder="4 digits"></div>
<div class="c"><h2>1. Videos for the TV to download</h2>
<p>Paste video links, one per line: YouTube, Facebook, Instagram or a direct .mp4 link. You can also load a .txt file with links. Facebook and Instagram links can take up to 30 seconds each.</p>
<textarea id="links" rows="6" placeholder="https://..."></textarea>
<input type="file" id="txt" accept=".txt,text/plain" style="margin-top:8px">
<button id="send">Download on the TV</button><pre id="res"></pre></div>
<div class="c"><h2>2. Upload files to the TV</h2>
<p>Pick videos, music, pictures or any files. They are saved on the TV's storage.</p>
<input type="file" id="files" multiple>
<p>Files start uploading as soon as you pick them. You can keep using this page meanwhile.</p><div id="ups"></div></div>
<div class="c"><h2>3. Sign in on the TV without typing</h2>
<p>New? Create the account here on your phone first:</p>
<a class="lnk" href="https://www.facebook.com/r.php" target="_blank">Create Facebook account</a>
<a class="lnk" href="https://www.instagram.com/accounts/emailsignup/" target="_blank">Create Instagram account</a>
<p>Then type your email (or phone / username) and password once. The TV fills them in on the Facebook, Instagram, ChatGPT and Claude login pages. Use this only on your own home Wi-Fi.</p>
<input type="text" id="su" placeholder="Email, phone or username" autocomplete="off">
<input type="password" id="sp" placeholder="Password" autocomplete="off" style="margin-top:8px">
<button id="sv">Save on the TV</button><pre id="sres"></pre></div>
<div class="c"><h2>4. Files on the TV</h2>
<p>Browse the TV's storage and download a file to this phone.</p>
<button class="sm" onclick="ls('int','')">Internal storage</button><button class="sm" onclick="ls('usb','')">USB drive</button><button class="sm" onclick="ls('usbm','')">USB media list</button>
<div id="crumb"><small></small></div><div id="fl"></div></div>
<div class="c"><h2>TV status</h2><div id="st">...</div></div>
</div>
<script>
var pin=document.getElementById('pin');
pin.value=localStorage.getItem('appin')||'';
try{var q=new URLSearchParams(location.search);var qp=q.get('pin');if(qp){pin.value=qp;localStorage.setItem('appin',qp);history.replaceState(null,'','/');}}catch(e){}
pin.onchange=function(){localStorage.setItem('appin',pin.value);};
document.getElementById('txt').onchange=function(e){
  var f=e.target.files[0]; if(!f) return;
  var r=new FileReader(); r.onload=function(){document.getElementById('links').value=r.result;}; r.readAsText(f);
};
document.getElementById('send').onclick=function(){
  var b=this, res=document.getElementById('res'); b.disabled=true; res.textContent='Sending... (Facebook / Instagram links take a while)';
  fetch('/api/queue',{method:'POST',headers:{'X-Pin':pin.value},body:document.getElementById('links').value})
   .then(function(r){return r.json();})
   .then(function(j){
     if(j.error){res.textContent=j.error;}
     else{res.textContent=(j.results.length?'':'No links found.\n')+j.results.map(function(x){return x.msg+'\n   '+x.link.substring(0,60);}).join('\n');}
     b.disabled=false;})
   .catch(function(){res.textContent='Could not reach the TV.';b.disabled=false;});
};
document.getElementById('sv').onclick=function(){
  var b=this, r=document.getElementById('sres'); b.disabled=true;
  fetch('/api/signin',{method:'POST',headers:{'X-Pin':pin.value},body:JSON.stringify({user:document.getElementById('su').value,pass:document.getElementById('sp').value})})
   .then(function(x){return x.json();})
   .then(function(j){r.textContent=j.ok?'Saved on the TV. Open Facebook or Instagram on the TV: the login boxes fill in by themselves.':(j.error||'Not saved');b.disabled=false;document.getElementById('sp').value='';})
   .catch(function(){r.textContent='Could not reach the TV.';b.disabled=false;});
};
function esc(t){return String(t).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
var upQueue=[], upBusy=false;
document.getElementById('files').onchange=function(){
  for(var k=0;k<this.files.length;k++){ upQueue.push(this.files[k]); }
  this.value='';
  if(!upBusy) nextUp();
};
function nextUp(){
  var box=document.getElementById('ups');
  if(!upQueue.length){upBusy=false;return;}
  upBusy=true;
  var f=upQueue.shift(), row=document.createElement('div');
  row.innerHTML='<small>'+esc(f.name)+'</small><div class="bar"><div></div></div><small class="m">starting</small>';
  box.appendChild(row);
  var bar=row.querySelector('.bar div'), m=row.querySelector('.m');
  var CS=4*1024*1024, n=Math.max(1,Math.ceil(f.size/CS));
  var id=Date.now().toString(36)+Math.random().toString(36).slice(2,8);
  var next=0, active=0, doneBytes=0, inflight={}, failed=false, finished=false, t0=Date.now();
  function report(){
    var cur=doneBytes; for(var k in inflight){cur+=inflight[k];}
    var pct=f.size?100*cur/f.size:100; bar.style.width=pct+'%';
    var sp=cur/Math.max(1,(Date.now()-t0)/1000)/1048576;
    m.textContent=Math.round(pct)+'%   '+sp.toFixed(1)+' MB/s';
  }
  function finish(ok,msg){ if(finished) return; finished=true; m.textContent=msg; if(ok) bar.style.width='100%'; nextUp(); }
  function send(i,attempt){
    var start=i*CS, end=Math.min(start+CS,f.size), x=new XMLHttpRequest();
    x.open('POST','/api/chunk?id='+id+'&name='+encodeURIComponent(f.name)+'&total='+f.size+'&idx='+i+'&cs='+CS);
    x.setRequestHeader('X-Pin',pin.value);
    x.upload.onprogress=function(e){inflight[i]=e.loaded;report();};
    x.onload=function(){
      var j={}; try{j=JSON.parse(x.responseText);}catch(e){}
      if(x.status===200&&j.ok){ delete inflight[i]; doneBytes+=end-start; active--; report(); if(j.done){finish(true,'Saved on the TV');} else pump(); }
      else retry(i,attempt,j.error||('HTTP '+x.status));
    };
    x.onerror=function(){retry(i,attempt,'connection lost');};
    x.send(f.slice(start,end));
  }
  function retry(i,attempt,why){
    delete inflight[i];
    if(attempt>=6){ active--; if(!failed){failed=true; finish(false,'Failed: '+why);} return; }
    m.textContent='Retrying ('+why+')...';
    setTimeout(function(){send(i,attempt+1);},1500*(attempt+1));
  }
  function pump(){ while(!failed&&active<3&&next<n){active++; send(next++,0);} }
  pump();
}
function sz(n){return n>1073741824?(n/1073741824).toFixed(1)+' GB':n>1048576?(n/1048576).toFixed(0)+' MB':Math.max(1,Math.round(n/1024))+' KB';}
function ls(root,path){
  var box=document.getElementById('fl'), cr=document.getElementById('crumb');
  box.textContent='Loading...';
  fetch('/api/ls?root='+root+'&path='+encodeURIComponent(path),{headers:{'X-Pin':pin.value}}).then(function(r){return r.json();}).then(function(j){
    if(j.error){box.textContent=j.error;return;}
    cr.innerHTML='<small>/'+esc(path)+'</small>'+(j.note?'<br><small>'+esc(j.note)+'</small>':'');
    box.innerHTML='';
    if(path){var up=document.createElement('div');up.className='fi';up.textContent='\u2B06 ..';
      up.onclick=function(){ls(root,path.indexOf('/')<0?'':path.substring(0,path.lastIndexOf('/')));};box.appendChild(up);}
    (j.entries||[]).forEach(function(e){
      var d=document.createElement('div');d.className='fi';
      if(e.d){d.textContent='\uD83D\uDCC1 '+e.n;d.onclick=function(){ls(root,path?path+'/'+e.n:e.n);};}
      else{var a=document.createElement('a');a.textContent=e.n+'  ('+sz(e.s)+')';
        var rr=(j.root||root); a.href='/api/get?root='+rr+'&pin='+encodeURIComponent(pin.value)+(e.u?'&u='+encodeURIComponent(e.u):(e.id?'&id='+e.id:'&path='+encodeURIComponent(path?path+'/'+e.n:e.n)));d.appendChild(a);}
      box.appendChild(d);
    });
    if(!(j.entries||[]).length) box.textContent='Empty';
  }).catch(function(){box.textContent='Could not reach the TV.';});
}
function mb(n){return (n/1048576).toFixed(0)+' MB';}
function poll(){
  fetch('/api/status').then(function(r){return r.json();}).then(function(j){
    var t='';
    if(j.active) t+='Downloading: '+j.title+' ('+j.pct+'%)<br>';
    if(j.queue>0) t+='Waiting in queue: '+j.queue+'<br>';
    if(j.upName) t+='Receiving: '+j.upName+' ('+mb(j.upDone)+' / '+mb(j.upTotal)+')<br>';
    if(!j.active && j.last) t+='Last finished: '+j.last+'<br>';
    if(j.free>=0) t+='Free space: '+(j.free/1073741824).toFixed(1)+' GB<br>';
    if(j.log.length) t+='<small>'+j.log.slice().reverse().join('<br>')+'</small>';
    document.getElementById('st').innerHTML=t||'Idle';
  }).catch(function(){document.getElementById('st').textContent='TV not reachable';});
}
poll(); setInterval(poll,2000);
</script></body></html>
"""
    }
}

/** The "Send to TV" screen: the logo, a QR code (scan it: the phone opens the page with the PIN already typed), address and PIN. */
class TransferActivity : ComponentActivity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var server: TransferServer? = null
    private val h = Handler(Looper.getMainLooper())
    private lateinit var rootLayout: FrameLayout
    private lateinit var tAddr: TextView
    private lateinit var tPin: TextView
    private lateinit var tInfo: TextView
    private lateinit var qrImg: ImageView
    private val sniffLock = Any()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    /** The TV's address on the home network (Wi-Fi or cable), or null. */
    private fun localIp(): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
            }
        } catch (_: Exception) {}
        return null
    }

    /** Draws [text] as a QR code picture (black on white). */
    private fun qr(text: String, px: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        for (x in 0 until px) for (y in 0 until px) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        return bmp
    }

    /**
     * Facebook / Instagram links: opens the link in a hidden browser (one at a time) and waits for the video files.
     * Called from the web server's thread; waits for the answer.
     */
    private fun sniff(link: String): SniffResult? = synchronized(sniffLock) {
        val latch = CountDownLatch(1)
        var result: SniffResult? = null
        h.post { LinkSniffer(this, rootLayout) { r -> result = r; latch.countDown() }.start(link) }
        latch.await(45, TimeUnit.SECONDS)
        result
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val col = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val extra = if (Dev.isTv(this)) dp(40) else dp(12)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.setPadding(i.left + extra, i.top + extra, i.right + extra, i.bottom + extra); ins
        }
        fun tv(sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = sp; setTextColor(c); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        // QR code (white square so that every phone camera reads it)
        val qrSize = if (wide) dp(230) else dp(210)
        qrImg = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val qrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        qrBox.addView(qrImg, LinearLayout.LayoutParams(qrSize, qrSize))
        qrBox.addView(tv(13f, 0xFF9A9A9A.toInt()).apply { text = "Scan with your phone camera"; setPadding(0, dp(8), 0, 0) }, WRAP, WRAP)

        // logo (only the logo, no text), address, PIN, what arrived
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val logo = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_foreground)) }
        info.addView(logo, LinearLayout.LayoutParams(dp(84), dp(84)))
        info.addView(tv(14f, 0xFF9A9A9A.toInt()).apply {
            text = "Or open this address in a browser on the same Wi-Fi and type the PIN"
            setPadding(0, dp(6), 0, dp(8))
        }, WRAP, WRAP)
        tAddr = tv(28f, 0xFF4FC3F7.toInt(), true)
        info.addView(tAddr, WRAP, WRAP)
        tPin = tv(42f, Color.WHITE, true).apply {
            setPadding(dp(28), dp(8), dp(28), dp(8)); letterSpacing = 0.25f
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF1A1A1A.toInt()); setStroke(dp(2), 0xFF444444.toInt()) }
        }
        info.addView(tPin, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, dp(10), 0, dp(10)) })
        tInfo = tv(14f, 0xFFBBBBBB.toInt()).apply { gravity = Gravity.START }
        info.addView(ScrollView(this).apply { addView(tInfo, MATCH, WRAP) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        info.addView(tv(12f, 0xFF777777.toInt()).apply { text = "Press BACK to close. Downloads that were sent keep running." }, WRAP, WRAP)

        if (wide) {
            col.addView(qrBox, LinearLayout.LayoutParams(WRAP, WRAP))
            col.addView(info, LinearLayout.LayoutParams(0, MATCH, 1f))
        } else {
            col.addView(info, LinearLayout.LayoutParams(MATCH, 0, 1f))
            col.addView(qrBox, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        rootLayout.addView(col, MATCH, MATCH)
        setContentView(rootLayout); Fx.install(this)

        val pin = (1000 + java.util.Random().nextInt(9000)).toString()
        val s = TransferServer(this, pin, this::sniff)
        val ok = s.start()
        server = s
        val ip = localIp()
        tPin.text = pin
        if (!ok) tAddr.text = "Could not start"
        else if (ip == null) tAddr.text = "No Wi-Fi / network found"
        else {
            val url = "http://" + ip + ":" + s.port
            tAddr.text = url
            // the QR code carries the PIN, so the phone does not ask for it
            try { qrImg.setImageBitmap(qr(url + "/?pin=" + pin, 400)) } catch (_: Exception) {}
        }
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = server
            val sb = StringBuilder()
            if (DL.active) sb.append("\u2B07 ").append(DL.title.take(50)).append("  ").append(DL.pct).append("%\n")
            if (DL.queue > 0) sb.append("Waiting in queue: ").append(DL.queue).append('\n')
            if (s != null) for (l in s.log.toList().asReversed()) sb.append(l).append('\n')
            tInfo.text = if (sb.isEmpty()) "Waiting for links or files..." else sb.toString()
            h.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        server?.stop()
        super.onDestroy()
    }
}
