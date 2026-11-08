package com.moinstudioz.advanceplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A tiny web server inside the app. A phone or computer on the same Wi-Fi opens http://<TV address>:<port>, types the PIN
 * shown on the TV, then can (1) send video links that the TV downloads and (2) upload files to the TV's storage.
 * It runs only while the "Send to TV" screen is open.
 */
class TransferServer(private val c: Context, val pin: String) {
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
            if (ext in setOf("mp4", "mkv", "webm", "m4v", "mov")) {
                media = link
                title = java.net.URLDecoder.decode(path.substringAfterLast('/').substringBeforeLast('.'), "UTF-8").take(70).ifBlank { "video $stamp" }
            } else if (host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.com") || host.contains("instagram.com")) {
                val notes = StringBuilder()
                media = WebGrab.resolve(link, notes) ?: return "Could not find the video (" + notes.toString().take(120) + "). Open the video in the app's Facebook / Instagram screen and press Download there."
                title = (if (host.contains("instagram")) "Instagram video " else "Facebook video ") + stamp
            } else return "Not supported (use YouTube, Facebook, Instagram or a direct .mp4 link)"
            val err = Dl.enqueueDirect(c, "web:" + media.substringBefore('?'), media, title)
            note((if (err == null) "Queued: " else "Not queued: ") + title.take(50))
            return err ?: "Added to the TV's download queue"
        } catch (e: Exception) { return "Error: " + (e.message ?: e.javaClass.simpleName).take(100) }
    }

    companion object {
        /** The web page that the phone / computer sees. */
        val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Advance Play - Send to TV</title>
<style>
body{margin:0;background:#0b0b0b;color:#eee;font-family:system-ui,Arial,sans-serif}
.w{max-width:680px;margin:0 auto;padding:16px}
h1{font-size:22px;margin:8px 0 14px}
h2{font-size:17px;margin:0 0 6px}
.c{background:#151515;border:1px solid #2a2a2a;border-radius:16px;padding:14px;margin-bottom:14px}
p{color:#aaa;font-size:13px;margin:4px 0 10px}
input[type=text],textarea{width:100%;box-sizing:border-box;background:#0e0e0e;color:#fff;border:1px solid #444;border-radius:10px;padding:10px;font-size:15px}
button{background:#e1306c;color:#fff;border:0;border-radius:22px;padding:11px 20px;font-size:15px;font-weight:bold;margin-top:10px}
button:disabled{opacity:.5}
pre{white-space:pre-wrap;color:#9be29b;font-size:13px}
.bar{height:6px;background:#333;border-radius:3px;overflow:hidden;margin:4px 0 10px}
.bar div{height:100%;background:#1db954;width:0}
small{color:#888}
</style></head><body><div class="w">
<h1>Advance Play &middot; Send to TV</h1>
<div class="c"><h2>PIN shown on the TV</h2><input type="text" id="pin" inputmode="numeric" maxlength="4" placeholder="4 digits"></div>
<div class="c"><h2>1. Videos for the TV to download</h2>
<p>Paste video links, one per line: YouTube, Facebook, Instagram or a direct .mp4 link. You can also load a .txt file with links.</p>
<textarea id="links" rows="6" placeholder="https://..."></textarea>
<input type="file" id="txt" accept=".txt,text/plain" style="margin-top:8px">
<button id="send">Download on the TV</button><pre id="res"></pre></div>
<div class="c"><h2>2. Upload files to the TV</h2>
<p>Pick videos, music, pictures or any files. They are saved on the TV's storage.</p>
<input type="file" id="files" multiple><button id="up">Upload to the TV</button><div id="ups"></div></div>
<div class="c"><h2>TV status</h2><div id="st">...</div></div>
</div>
<script>
var pin=document.getElementById('pin');
pin.value=localStorage.getItem('appin')||'';
pin.onchange=function(){localStorage.setItem('appin',pin.value);};
document.getElementById('txt').onchange=function(e){
  var f=e.target.files[0]; if(!f) return;
  var r=new FileReader(); r.onload=function(){document.getElementById('links').value=r.result;}; r.readAsText(f);
};
document.getElementById('send').onclick=function(){
  var b=this, res=document.getElementById('res'); b.disabled=true; res.textContent='Sending...';
  fetch('/api/queue',{method:'POST',headers:{'X-Pin':pin.value},body:document.getElementById('links').value})
   .then(function(r){return r.json();})
   .then(function(j){
     if(j.error){res.textContent=j.error;}
     else{res.textContent=(j.results.length?'':'No links found.\n')+j.results.map(function(x){return x.msg+'\n   '+x.link.substring(0,60);}).join('\n');}
     b.disabled=false;})
   .catch(function(){res.textContent='Could not reach the TV.';b.disabled=false;});
};
document.getElementById('up').onclick=function(){
  var files=document.getElementById('files').files, box=document.getElementById('ups'), btn=this;
  if(!files.length){box.textContent='Pick files first.';return;}
  btn.disabled=true; box.innerHTML='';
  var i=0;
  function next(){
    if(i>=files.length){btn.disabled=false;return;}
    var f=files[i++], row=document.createElement('div');
    row.innerHTML='<small>'+f.name+'</small><div class="bar"><div></div></div><small class="m">waiting</small>';
    box.appendChild(row);
    var bar=row.querySelector('.bar div'), m=row.querySelector('.m');
    var x=new XMLHttpRequest();
    x.open('POST','/api/upload?name='+encodeURIComponent(f.name));
    x.setRequestHeader('X-Pin',pin.value);
    x.upload.onprogress=function(e){if(e.lengthComputable){bar.style.width=(100*e.loaded/e.total)+'%';m.textContent=Math.round(100*e.loaded/e.total)+'%';}};
    x.onload=function(){var j={};try{j=JSON.parse(x.responseText);}catch(e){} m.textContent=j.ok?'Saved on the TV':(j.error||'Failed'); next();};
    x.onerror=function(){m.textContent='Connection lost'; next();};
    x.send(f);
  }
  next();
};
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

/** The "Send to TV" screen: shows the address and PIN, and what has arrived. */
class TransferActivity : ComponentActivity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var server: TransferServer? = null
    private val h = Handler(Looper.getMainLooper())
    private lateinit var tAddr: TextView
    private lateinit var tPin: TextView
    private lateinit var tInfo: TextView

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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val extra = if (Dev.isTv(this)) dp(40) else dp(12)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            col.setPadding(i.left + extra, i.top + extra, i.right + extra, i.bottom + extra); ins
        }
        fun tv(sp: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = sp; setTextColor(c); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        col.addView(tv(24f, Color.WHITE, true).apply { text = "Send to TV" }, WRAP, WRAP)
        col.addView(tv(14f, 0xFF9A9A9A.toInt()).apply {
            text = "On your phone or computer (same Wi-Fi), open this address in a browser and type the PIN:"
            setPadding(0, dp(8), 0, dp(12))
        }, WRAP, WRAP)
        tAddr = tv(34f, 0xFF4FC3F7.toInt(), true)
        col.addView(tAddr, WRAP, WRAP)
        tPin = tv(46f, Color.WHITE, true).apply {
            setPadding(dp(30), dp(10), dp(30), dp(10)); letterSpacing = 0.25f
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF1A1A1A.toInt()); setStroke(dp(2), 0xFF444444.toInt()) }
        }
        col.addView(tPin, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, dp(14), 0, dp(14)) })
        tInfo = tv(14f, 0xFFBBBBBB.toInt()).apply { gravity = Gravity.START }
        col.addView(ScrollView(this).apply { addView(tInfo, MATCH, WRAP) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(tv(12f, 0xFF777777.toInt()).apply { text = "Press BACK to close. Downloads that were sent keep running." }, WRAP, WRAP)
        root.addView(col, MATCH, MATCH)
        setContentView(root)

        val pin = (1000 + java.util.Random().nextInt(9000)).toString()
        val s = TransferServer(this, pin)
        val ok = s.start()
        server = s
        val ip = localIp()
        tPin.text = pin
        tAddr.text = if (!ok) "Could not start" else if (ip == null) "No Wi-Fi / network found" else "http://" + ip + ":" + s.port
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
