package com.leon.ai3dplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        var is3DActive = false
        var instance: MainActivity? = null
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GL3DRenderer
    private lateinit var controlsPanel: LinearLayout
    private lateinit var playPauseBtn: Button
    private lateinit var videoSeekBar: SeekBar
    private lateinit var timeText: TextView

    private var player: ExoPlayer? = null
    private var depthEstimator: DepthEstimator? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProcessingAI = false
    private var isUserSeeking = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val mime = contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) {
            player?.pause()
            playPauseBtn.text = "Play"
            val stream = contentResolver.openInputStream(uri)
            val bmp = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bmp != null) {
                renderer.setStaticImage(bmp)
                aiExecutor.execute {
                    val depthMap = depthEstimator?.estimateDepth(bmp)
                    if (depthMap != null) renderer.updateDepthMap(depthMap)
                }
            }
        } else {
            renderer.isImageMode = false
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            player?.play()
            playPauseBtn.text = "Pause"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        is3DActive = true
        depthEstimator = DepthEstimator(this)

        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = GL3DRenderer { surfaceTexture ->
                mainHandler.post {
                    player = ExoPlayer.Builder(this@MainActivity).build().apply {
                        setVideoSurface(Surface(surfaceTexture))
                        repeatMode = ExoPlayer.REPEAT_MODE_ALL
                    }
                    startLoops()
                }
            }
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnClickListener {
                controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(glView)

        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 14, 28, 14)
        }
        val timelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(0xFFFFFFFF.toInt())
        }
        videoSeekBar = SeekBar(this).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(650, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player?.duration ?: 0L
                        if (dur > 0) player?.seekTo((dur * p) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { isUserSeeking = false }
            })
        }
        timelineRow.addView(timeText)
        timelineRow.addView(videoSeekBar)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pickBtn = Button(this).apply {
            text = "Open File"
            setOnClickListener { pickMedia.launch("*/*") }
        }
        playPauseBtn = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) { p.pause(); text = "Play" } else { p.play(); text = "Pause" }
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                p.pause(); p.seekTo(0); playPauseBtn.text = "Play"; videoSeekBar.progress = 0
            }
        }
        val depthLabel = TextView(this).apply { text = "  3D Depth: "; setTextColor(0xFFFFFFFF.toInt()) }
        val depthSlider = SeekBar(this).apply {
            max = 120; progress = 45
            layoutParams = LinearLayout.LayoutParams(280, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { renderer.depthStrength = p / 1000f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        buttonRow.addView(pickBtn); buttonRow.addView(playPauseBtn); buttonRow.addView(stopBtn)
        buttonRow.addView(depthLabel); buttonRow.addView(depthSlider)
        controlsPanel.addView(timelineRow); controlsPanel.addView(buttonRow)

        val ctrlParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        root.addView(controlsPanel, ctrlParams)
        setContentView(root)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val s = ms / 1000L
        return String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L)
    }

    private fun startLoops() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val p = player
                if (p != null && !renderer.isImageMode && !isUserSeeking && p.duration > 0) {
                    videoSeekBar.progress = ((p.currentPosition * 1000L) / p.duration).toInt()
                    timeText.text = "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}"
                }
                mainHandler.postDelayed(this, 300)
            }
        }, 300)

        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!renderer.isImageMode && player?.isPlaying == true && !isProcessingAI && glView.width > 0) {
                    isProcessingAI = true
                    val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    try {
                        PixelCopy.request(glView, bmp, { res ->
                            if (res == PixelCopy.SUCCESS) {
                                aiExecutor.execute {
                                    depthEstimator?.estimateDepth(bmp)?.let { renderer.updateDepthMap(it) }
                                    isProcessingAI = false
                                }
                            } else isProcessingAI = false
                        }, mainHandler)
                    } catch (e: Exception) { isProcessingAI = false }
                }
                mainHandler.postDelayed(this, 75)
            }
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy(); is3DActive = false; instance = null
        player?.release(); depthEstimator?.close()
    }
}
