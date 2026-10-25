package com.moinstudioz.advanceplay

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Plays a downloaded video straight from the USB / SD card. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var key = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        key = intent.getStringExtra("uri") ?: run { finish(); return }
        val name = intent.getStringExtra("name") ?: ""
        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true }
        setContentView(view)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val p = ExoPlayer.Builder(this).build()
        view.player = p
        p.setMediaItem(
            MediaItem.Builder().setUri(Uri.parse(key))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build()
        )
        val pos = Prefs.pos(this, key)
        if (pos > 0) p.seekTo(pos)
        p.prepare(); p.playWhenReady = true
        player = p
        view.requestFocus()
    }

    override fun onStop() {
        super.onStop()
        player?.let { Prefs.setPos(this, key, if (it.currentPosition > it.duration - 5000) 0 else it.currentPosition); it.pause() }
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
