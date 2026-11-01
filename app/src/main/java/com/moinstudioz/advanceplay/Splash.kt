package com.moinstudioz.advanceplay

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView

/**
 * The Moin Studioz intro video, shown on top of the whole screen while the app starts.
 * It always plays the complete video (about 8 seconds, cannot be skipped), then fades out once the real screen has its content.
 */
class SplashView(c: Context) : FrameLayout(c) {
    private val video = VideoView(c)
    private var videoDone = false
    private var contentReady = false
    private var closed = false

    /** True while the intro is still covering the screen. */
    val isOpen: Boolean get() = !closed

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true                                   // blocks touches on the screen below until the intro is over
        video.setZOrderOnTop(true)                           // so the video is drawn above the screen below
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        video.setOnPreparedListener { mp -> mp.isLooping = false; video.start() }
        video.setOnCompletionListener { videoDone = true; tryClose() }
        video.setOnErrorListener { _, _, _ -> videoDone = true; tryClose(); true }
        try {
            video.setVideoURI(Uri.parse("android.resource://" + c.packageName + "/" + R.raw.loading_intro))
        } catch (_: Exception) { videoDone = true }
        postDelayed({ close() }, 25000)                      // safety: never stay longer than 25 seconds if something goes wrong
    }

    /** Call when the real screen has its content (first videos or first page loaded). */
    fun contentReady() { contentReady = true; tryClose() }

    private fun tryClose() { if (videoDone && contentReady) close() }

    private fun close() {
        if (closed) return
        closed = true
        try { video.stopPlayback() } catch (_: Exception) {}
        video.visibility = View.INVISIBLE
        animate().alpha(0f).setDuration(300).withEndAction { visibility = View.GONE }.start()
    }

    override fun onDetachedFromWindow() {
        try { video.stopPlayback() } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }
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
