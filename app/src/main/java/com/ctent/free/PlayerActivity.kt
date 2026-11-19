package com.ctent.free

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var url: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra("url") ?: run { finish(); return }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)

        // Bigger buffer = smoother play on slow or shaky links
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_500, 5_000)
            .build()

        val p = ExoPlayer.Builder(this)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        val view = PlayerView(this)
        view.player = p
        setContentView(view)

        p.setMediaItem(MediaItem.fromUri(url))
        p.seekTo(getSharedPreferences("pos", MODE_PRIVATE).getLong(url, 0L))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onPause() {
        player?.let {
            getSharedPreferences("pos", MODE_PRIVATE).edit().putLong(url, it.currentPosition).apply()
            it.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
