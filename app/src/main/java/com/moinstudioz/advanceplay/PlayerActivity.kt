package com.moinstudioz.advanceplay

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.app.AlertDialog
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Full-screen player (no other UI). Plays a downloaded file from the USB / SD card,
 * or streams a YouTube video / live stream directly.
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var key = ""
    private var yt: String? = null
    private var fkey = ""                    // file name when playing from the Library (used for resume and "watched")
    private var thumb = ""                   // YouTube thumbnail link (for the Watched list)
    private var title = ""
    private var resumeMs = 0L                // where a YouTube video continues from (0 = from the start)
    private val saver = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable { override fun run() { saveProgress(); saver.postDelayed(this, 15000) } }
    private lateinit var overlay: FrameLayout
    private lateinit var msg: TextView
    private lateinit var speedPill: TextView
    private var qualityPill: TextView? = null
    private val speeds = floatArrayOf(0.5f, 1f, 2f, 3f, 4f, 5f)
    private var speed = 1f

    /** One YouTube quality: picture link, and a sound link when the picture file has no sound (null = one file with both). */
    private class Q(val h: Int, val vUrl: String, val aUrl: String?)
    private var qualities: List<Q> = emptyList()
    private var curQ: Q? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        yt = intent.getStringExtra("yt")
        key = intent.getStringExtra("uri") ?: ""
        val name = intent.getStringExtra("name") ?: ""
        fkey = intent.getStringExtra("fkey") ?: ""
        thumb = intent.getStringExtra("thumb") ?: ""
        title = name
        if (yt == null && key.isEmpty()) { finish(); return }
        if (yt != null && !intent.getBooleanExtra("fromStart", false)) {
            // continue where we stopped last time (not when it was almost finished)
            val w = History.progressOf(this, History.idOf(yt!!))
            if (w != null && !w.finished && w.posMs > 5000) resumeMs = w.posMs
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true }
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // title (and Download button for YouTube) shown together with the player controls
        overlay = FrameLayout(this)
        overlay.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(20), dp(if (yt != null) 470 else 150), 0)
        })
        fun pill(label: String, click: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xCC161616.toInt()); setStroke(dp(1), 0xFF555555.toInt()) }
            setOnFocusChangeListener { v, f ->
                (v.background as GradientDrawable).setStroke(dp(if (f) 3 else 1), if (f) Color.WHITE else 0xFF555555.toInt())
            }
            setOnClickListener { click() }
        }
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addPill(v: View) = pills.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), 0, 0, 0) })
        speedPill = pill("1x") { chooseSpeed() }
        addPill(speedPill)
        if (yt != null) {
            overlay.addView(TextView(this).apply {
                text = "Hold OK or press MENU to download"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), dp(52), 0, 0)
            })
            val qp = pill("Quality") { chooseQuality() }
            qp.visibility = View.GONE                    // shown when the video's qualities are known
            qualityPill = qp
            addPill(qp)
            addPill(pill("\u2B07 Download") { download() })
        }
        overlay.addView(pills, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(14), dp(24), 0)
        })
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        msg = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(30), dp(20), dp(30), dp(20))
        }
        root.addView(msg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> overlay.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE })

        val p = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 1_500, 4_000).build())
            .build()
        view.player = p
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { say("Cannot play this video (${e.errorCodeName})") }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) msg.visibility = View.GONE }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Throttle.streaming = isPlaying
                if (isPlaying && yt != null) History.markWatched(this@PlayerActivity, History.idOf(yt!!))
            }
        })
        if (yt != null) loadYoutube(yt!!)
        else {
            p.setMediaItem(MediaItem.Builder().setUri(Uri.parse(key)).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build())
            val pos = if (intent.getBooleanExtra("fromStart", false)) 0L else Prefs.pos(this, if (fkey.isNotEmpty()) fkey else key)
            if (pos > 0) p.seekTo(pos)
            p.prepare(); p.playWhenReady = true
        }
        view.requestFocus()
    }

    private fun say(t: String) { runOnUiThread { msg.text = t; msg.visibility = View.VISIBLE } }

    /** Media source for one YouTube quality (picture + sound are joined when they are two files). */
    private fun sourceFor(q: Q): MediaSource {
        val dsf = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
        val pf = ProgressiveMediaSource.Factory(dsf)
        val a = q.aUrl
        return if (a != null) MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(q.vUrl)), pf.createMediaSource(MediaItem.fromUri(a)))
        else pf.createMediaSource(MediaItem.fromUri(q.vUrl))
    }

    /** Speed list: 0.5x 1x 2x 3x 4x 5x. */
    private fun chooseSpeed() {
        val labels = Array(speeds.size) { i -> (if (speeds[i] == speed) "\u2714  " else "      ") + (if (speeds[i] % 1f == 0f) speeds[i].toInt().toString() else speeds[i].toString()) + "x" }
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, i ->
            speed = speeds[i]
            player?.setPlaybackSpeed(speed)
            speedPill.text = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"
        }.show()
    }

    /** Resolution list while streaming; the video continues from the same second. */
    private fun chooseQuality() {
        val qs = qualities
        if (qs.size < 2) return
        val labels = Array(qs.size) { i -> (if (qs[i] === curQ) "\u2714  " else "      ") + qs[i].h + "p" }
        AlertDialog.Builder(this).setTitle("Resolution").setItems(labels) { _, i ->
            val q = qs[i]
            val p = player ?: return@setItems
            if (q === curQ) return@setItems
            val pos = p.currentPosition
            val playing = p.playWhenReady
            p.setMediaSource(sourceFor(q))
            p.seekTo(pos)
            p.prepare()
            p.playWhenReady = playing
            p.setPlaybackSpeed(speed)
            curQ = q
            qualityPill?.text = q.h.toString() + "p"
        }.show()
    }

    private fun download() {
        val err = Dl.enqueue(this, yt ?: return)
        Toast.makeText(this, err ?: "Added to download queue", Toast.LENGTH_SHORT).show()
    }

    private fun loadYoutube(url: String) {
        say("Loading...")
        Thread {
            try {
                Np.init()
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                fun ok(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                val progAll = info.videoStreams.filter { ok(it) }
                val vOnlyAll = info.videoOnlyStreams
                    .filter { ok(it) && it.format == NpFormat.MPEG_4 && it.codec?.startsWith("avc1") == true && it.height <= 1080 }
                val aud = info.audioStreams.filter { ok(it) && it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate }
                val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                // every quality we can play: highest first
                val qs = ArrayList<Q>()
                val heights = (vOnlyAll.map { it.height } + progAll.map { it.height }).filter { it > 0 }.distinct().sortedDescending()
                for (hgt in heights) {
                    val vo = vOnlyAll.firstOrNull { it.height == hgt }
                    val pg = progAll.firstOrNull { it.height == hgt }
                    if (pg != null && (vo == null || aud == null || hgt <= 360)) qs.add(Q(hgt, pg.content, null))
                    else if (vo != null && aud != null) qs.add(Q(hgt, vo.content, aud.content))
                }
                val bestMerged = qs.firstOrNull { it.aUrl != null }
                val bestProg = qs.firstOrNull { it.aUrl == null }
                val first = if (bestMerged != null && (bestProg == null || (Prefs.playHd(this) && bestMerged.h > bestProg.h))) bestMerged else (bestProg ?: bestMerged)
                runOnUiThread {
                    val p = player ?: return@runOnUiThread
                    val dsf = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                    when {
                        live && info.hlsUrl.isNotEmpty() ->
                            p.setMediaSource(HlsMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(info.hlsUrl)))
                        first != null -> {
                            p.setMediaSource(sourceFor(first))
                            curQ = first; qualities = qs
                            qualityPill?.let { it.text = first.h.toString() + "p"; it.visibility = if (qs.size > 1) View.VISIBLE else View.GONE }
                        }
                        else -> { say("No playable stream found for this video"); return@runOnUiThread }
                    }
                    if (!live && resumeMs > 0) p.seekTo(resumeMs)
                    p.prepare(); p.playWhenReady = true
                    p.setPlaybackSpeed(speed)
                    saver.postDelayed(saveTick, 15000)
                }
            } catch (e: Exception) { say("Could not load this video: " + (e.message ?: "error")) }
        }.start()
    }

    private var okHeld = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (yt != null) {
            if ((k == KeyEvent.KEYCODE_PROG_BLUE || k == KeyEvent.KEYCODE_MENU) && e.action == KeyEvent.ACTION_UP) { download(); return true }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1) { okHeld = true; download(); return true }
                if (e.action == KeyEvent.ACTION_DOWN && okHeld) return true
                if (e.action == KeyEvent.ACTION_UP && okHeld) { okHeld = false; return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** YouTube: remember how far we watched (shown in the Watched list). */
    private fun saveProgress() {
        val u = yt ?: return
        val p = player ?: return
        if (p.isCurrentMediaItemLive || p.duration <= 0) return
        History.saveProgress(this, History.idOf(u), title, thumb, p.currentPosition, p.duration)
    }

    override fun onStop() {
        super.onStop()
        saver.removeCallbacks(saveTick)
        player?.let {
            if (yt == null && key.isNotEmpty()) {
                val k = if (fkey.isNotEmpty()) fkey else key
                val finished = it.duration > 0 && it.currentPosition > it.duration * 0.9
                Prefs.setPos(this, k, if (it.duration > 0 && it.currentPosition > it.duration - 5000) 0 else it.currentPosition)
                if (finished && fkey.isNotEmpty()) History.markWatched(this, "f:$fkey")
            }
            saveProgress()
            it.pause()
        }
        Throttle.streaming = false
    }

    override fun onDestroy() { Throttle.streaming = false; player?.release(); player = null; super.onDestroy() }
}
