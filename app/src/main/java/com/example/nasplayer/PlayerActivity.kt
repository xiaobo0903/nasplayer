@file:OptIn(UnstableApi::class)
package com.example.nasplayer

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.ui.R as UiR

class PlayerActivity : ComponentActivity() {
    private lateinit var pv: PlayerView
    private lateinit var store: Store
    private lateinit var cfg: NasConfig
    private lateinit var path: String
    private lateinit var name: String
    private var player: ExoPlayer? = null
    private var session: SmbSession? = null
    private var finished = false
    private val ui = Handler(Looper.getMainLooper())
    private val saver = object : Runnable { override fun run() { savePos(); ui.postDelayed(this, 5000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        cfg = store.cfg ?: run { finish(); return }
        path = intent.getStringExtra("path") ?: run { finish(); return }
        name = intent.getStringExtra("name") ?: path
        pv = PlayerView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true                       // 播放时不熄屏
            controllerShowTimeoutMs = 5000            // 5 秒无操作自动隐藏控制条
            controllerHideOnTouch = false
            setShowSubtitleButton(true)               // 字幕按钮：可选轨道 / 关闭字幕
            setShowNextButton(false); setShowPreviousButton(false)
        }
        setContentView(pv)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = endPlayback()    // 返回键：立即退出并释放
        })
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val dir = path.substringBeforeLast('/', "")
                val base = name.substringBeforeLast('.')
                val subs = withContext(Dispatchers.IO) {
                    val s = SmbSession(cfg).also { session = it }
                    s.list(dir).filter { !it.isDir && it.ext in SUB_EXT && it.name.startsWith(base, ignoreCase = true) }.sortedBy { it.name }
                }
                val saved = store.pos(path)
                val resume = saved?.takeIf { it.first > 10_000 && (it.second <= 0 || it.first < it.second - 15_000) }?.first
                if (resume == null) start(subs, 0) else
                    AlertDialog.Builder(this@PlayerActivity).setTitle(name)
                        .setMessage("上次播放到 ${fmtTime(resume)}")
                        .setPositiveButton("继续播放") { _, _ -> start(subs, resume) }
                        .setNegativeButton("从头播放") { _, _ -> start(subs, 0) }
                        .setOnCancelListener { endPlayback() }.show()
            } catch (e: Exception) {
                release()
                if (e !is CancellationException) Toast.makeText(this@PlayerActivity, "无法打开：${(e.cause ?: e).message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun start(subs: List<NasItem>, startMs: Long) {
        val s = session ?: return
        val dir = path.substringBeforeLast('/', "")
        val subCfgs = subs.mapIndexed { i, f ->
            MediaItem.SubtitleConfiguration.Builder(smbUri(cfg, f.path))
                .setMimeType(when (f.ext) { "ass", "ssa" -> MimeTypes.TEXT_SSA; "vtt" -> MimeTypes.TEXT_VTT; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLabel(f.name)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)   // 首个同名字幕默认显示
                .build()
        }
        val item = MediaItem.Builder().setUri(smbUri(cfg, path)).setSubtitleConfigurations(subCfgs).build()

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)  // 只用 MediaCodec 硬解
            .setEnableDecoderFallback(true)                                                  // 首选解码器失败时换备选
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, 2_500, 5_000).build()
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { SmbDataSource(s) }))
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { store.clearPos(path); finished = true; endPlayback() }  // 播完返回列表
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_LONG).show()
                endPlayback()
            }
        })
        player = p
        pv.player = p
        p.setMediaItem(item, startMs)
        p.prepare()
        p.playWhenReady = true
        ui.postDelayed(saver, 5000)
    }

    private fun savePos() {
        val p = player ?: return
        if (finished || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return
        store.savePos(path, p.currentPosition, p.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    private fun endPlayback() { savePos(); release(); finish() }

    /** 释放所有资源：定时器、播放器（含解码器和数据源）、SMB 连接。可重复调用。 */
    private fun release() {
        ui.removeCallbacksAndMessages(null)
        pv.player = null
        player?.release(); player = null
        val s = session; session = null
        if (s != null) Thread { s.close() }.start()   // 网络关闭不能在主线程
    }

    override fun onStop() { super.onStop(); if (!isFinishing) endPlayback() }   // 按 Home 等离开时也彻底释放
    override fun onDestroy() { release(); super.onDestroy() }

    private fun seekBy(sign: Int, repeat: Int) {
        val p = player ?: return
        val step = 10_000L * (1 + minOf(repeat / 4, 5))            // 长按加速：10s → 60s
        var t = p.currentPosition + sign * step
        val d = p.duration
        t = t.coerceAtLeast(0)
        if (d != C.TIME_UNSET) t = t.coerceAtMost(d - 1_000)
        p.seekTo(t)
        pv.showController()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val onBar = currentFocus?.id == UiR.id.exo_progress
        if (!pv.isControllerFullyVisible || onBar) {
            val down = e.action == KeyEvent.ACTION_DOWN
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) seekBy(-1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) seekBy(1, e.repeatCount); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (down && e.repeatCount == 0) player?.let { it.playWhenReady = !it.playWhenReady }
                    pv.showController(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!pv.isControllerFullyVisible) {
                    if (down) pv.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
