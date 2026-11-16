package com.moinstudioz.advanceplay

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * The Moin Studioz intro video, shown on top of the whole screen while the app starts.
 * It always plays the complete video (about 8 seconds, cannot be skipped), then fades out once the real screen has its content.
 * It is played with the same player as the app's videos (ExoPlayer), which also works on TVs where the simple VideoView fails.
 */
class SplashView(c: Context) : FrameLayout(c) {
    private val tex = TextureView(c)
    private var player: ExoPlayer? = null
    private var vw = 1280
    private var vh = 720
    private var videoDone = false
    private var contentReady = false
    private var closed = false

    /** True while the intro is still covering the screen. */
    val isOpen: Boolean get() = !closed

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true                                   // blocks touches on the screen below until the intro is over
        addView(tex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        try {
            val p = ExoPlayer.Builder(c).build()
            player = p
            p.setVideoTextureView(tex)
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) { vw = videoSize.width; vh = videoSize.height; fit() }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) { videoDone = true; tryClose() }
                }
                override fun onPlayerError(error: PlaybackException) { videoDone = true; tryClose() }
            })
            p.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://" + c.packageName + "/" + R.raw.loading_intro)))
            p.prepare()
            p.playWhenReady = true
        } catch (_: Exception) { videoDone = true }
        postDelayed({ close() }, 25000)                      // safety: never stay longer than 25 seconds if something goes wrong
    }

    /** Keep the 16:9 picture centred and not stretched, whatever the screen shape. */
    private fun fit() {
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / vw, height.toFloat() / vh)
        tex.layoutParams = LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    /** Call when the real screen has its content (first videos or first page loaded). */
    fun contentReady() { contentReady = true; tryClose() }

    private fun tryClose() { if (videoDone && contentReady) close() }

    private fun stopPlayer() { try { player?.release() } catch (_: Exception) {}; player = null }

    private fun close() {
        if (closed) return
        closed = true
        stopPlayer()
        tex.visibility = View.INVISIBLE
        animate().alpha(0f).setDuration(300).withEndAction { visibility = View.GONE }.start()
    }

    override fun onDetachedFromWindow() { stopPlayer(); super.onDetachedFromWindow() }
}

object Splash {
    @Volatile private var played = false

    /** The intro view, or null when it already played during this app start (so it is not shown again). */
    fun create(c: Context): SplashView? {
        if (played) return null
        played = true
        return SplashView(c)
    }

    /** While the intro is on screen, swallow all remote-control keys so the video plays to the end. Returns true when the key was used. */
    fun blocks(s: SplashView?, @Suppress("UNUSED_PARAMETER") e: KeyEvent): Boolean = s != null && s.isOpen
}
