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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        var is3DActive = false
        var instance: MainActivity? = null
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GL3DRenderer
    private var player: ExoPlayer? = null
    private var depthEstimator: DepthEstimator? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProcessingAI = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val mime = contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) {
            player?.pause()
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
                    startDepthLoop()
                }
            }
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
        }

        val pickBtn = Button(this).apply {
            text = "Open Video / Photo"
            setOnClickListener { pickMedia.launch("*/*") }
        }

        val depthLabel = TextView(this).apply {
            text = "  3D Pop-Out: "
            setTextColor(0xFFFFFFFF.toInt())
        }

        val depthSlider = SeekBar(this).apply {
            max = 100
            progress = 35
            layoutParams = LinearLayout.LayoutParams(350, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    renderer.depthStrength = p / 1000f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        controls.addView(pickBtn)
        controls.addView(depthLabel)
        controls.addView(depthSlider)

        val ctrlParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        root.addView(controls, ctrlParams)

        setContentView(root)
        hideSystemUI()
    }

    private fun startDepthLoop() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!renderer.isImageMode && player?.isPlaying == true && !isProcessingAI && glView.width > 0) {
                    isProcessingAI = true
                    val captureBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    try {
                        PixelCopy.request(glView, captureBmp, { result ->
                            if (result == PixelCopy.SUCCESS) {
                                aiExecutor.execute {
                                    val depth = depthEstimator?.estimateDepth(captureBmp)
                                    if (depth != null) renderer.updateDepthMap(depth)
                                    isProcessingAI = false
                                }
                            } else {
                                isProcessingAI = false
                            }
                        }, mainHandler)
                    } catch (e: Exception) {
                        isProcessingAI = false
                    }
                }
                mainHandler.postDelayed(this, 80)
            }
        }, 500)
    }

    private fun hideSystemUI() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        is3DActive = false
        instance = null
        player?.release()
        depthEstimator?.close()
    }
}
