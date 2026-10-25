package com.moinstudioz.advanceplay

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
    private lateinit var overlay: FrameLayout
    private lateinit var msg: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        yt = intent.getStringExtra("yt")
        key = intent.getStringExtra("uri") ?: ""
        val name = intent.getStringExtra("name") ?: ""
        if (yt == null && key.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true }
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // title (and Download button for YouTube) shown together with the player controls
        overlay = FrameLayout(this)
        overlay.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(20), dp(180), 0)
        })
        if (yt != null) {
            overlay.addView(TextView(this).apply {
                text = "Hold OK or press MENU to download"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), dp(52), 0, 0)
            })
            overlay.addView(TextView(this).apply {
                text = "\u2B07 Download"; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true; isClickable = true
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xCC161616.toInt()); setStroke(dp(1), 0xFF555555.toInt()) }
                setOnFocusChangeListener { v, f ->
                    (v.background as GradientDrawable).setStroke(dp(if (f) 3 else 1), if (f) Color.WHITE else 0xFF555555.toInt())
                }
                setOnClickListener { download() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
                setMargins(0, dp(14), dp(24), 0)
            })
        }
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { Throttle.streaming = isPlaying }
        })
        if (yt != null) loadYoutube(yt!!)
        else {
            p.setMediaItem(MediaItem.Builder().setUri(Uri.parse(key)).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build())
            val pos = Prefs.pos(this, key)
            if (pos > 0) p.seekTo(pos)
            p.prepare(); p.playWhenReady = true
        }
        view.requestFocus()
    }

    private fun say(t: String) { runOnUiThread { msg.text = t; msg.visibility = View.VISIBLE } }

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
                val prog = info.videoStreams.filter { ok(it) }.maxByOrNull { it.height }
                val vOnly = info.videoOnlyStreams
                    .filter { ok(it) && it.format == NpFormat.MPEG_4 && it.codec?.startsWith("avc1") == true && it.height <= 1080 }
                    .maxByOrNull { it.height }
                val aud = info.audioStreams.filter { ok(it) && it.format == NpFormat.M4A }.maxByOrNull { it.averageBitrate }
                val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                runOnUiThread {
                    val p = player ?: return@runOnUiThread
                    val dsf = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
                    val pf = ProgressiveMediaSource.Factory(dsf)
                    when {
                        live && info.hlsUrl.isNotEmpty() ->
                            p.setMediaSource(HlsMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(info.hlsUrl)))
                        vOnly != null && aud != null && (prog == null || (Prefs.playHd(this) && vOnly.height > prog.height)) ->
                            p.setMediaSource(MergingMediaSource(
                                pf.createMediaSource(MediaItem.fromUri(vOnly.content)),
                                pf.createMediaSource(MediaItem.fromUri(aud.content))))
                        prog != null -> p.setMediaSource(pf.createMediaSource(MediaItem.fromUri(prog.content)))
                        else -> { say("No playable stream found for this video"); return@runOnUiThread }
                    }
                    p.prepare(); p.playWhenReady = true
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

    override fun onStop() {
        super.onStop()
        player?.let {
            if (yt == null && key.isNotEmpty()) Prefs.setPos(this, key, if (it.duration > 0 && it.currentPosition > it.duration - 5000) 0 else it.currentPosition)
            it.pause()
        }
        Throttle.streaming = false
    }

    override fun onDestroy() { Throttle.streaming = false; player?.release(); player = null; super.onDestroy() }
}
