package com.leon.ai3dplayer

import android.content.ContentUris
import android.content.Intent
import android.graphics.*
import android.media.ImageReader
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    companion object { var is3DActive = false; var instance: MainActivity? = null }
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GL3DRenderer
    private lateinit var controlsPanel: LinearLayout
    private lateinit var playPauseBtn: Button
    private lateinit var videoSeekBar: SeekBar
    private lateinit var timeText: TextView
    private var player: ExoPlayer? = null
    private var depthEstimator: DepthEstimator? = null
    private var imageReader: ImageReader? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProcessingAI = false
    private var isUserSeeking = false
    private var folderImages = listOf<Uri>()
    private var currentImageIndex = -1
    private var lastDepthTime = 0L

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadMediaUri(it, scanFolder = true) }
    }

    private fun loadMediaUri(uri: Uri, scanFolder: Boolean) {
        val mime = contentResolver.getType(uri) ?: uri.toString().lowercase(Locale.US)
        val isImg = mime.startsWith("image/") || mime.endsWith(".jpg") || mime.endsWith(".jpeg") || mime.endsWith(".png") || mime.endsWith(".webp")
        if (isImg) {
            player?.pause(); if (::playPauseBtn.isInitialized) playPauseBtn.text = "Play"
            if (scanFolder) loadSiblingImages(uri)
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bmp ->
                    renderer.setStaticImage(bmp)
                    aiExecutor.execute { depthEstimator?.estimateDepth(bmp)?.let { renderer.updateDepthMap(it) } }
                }
            }
        } else {
            folderImages = emptyList()
            renderer.isImageMode = false
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare(); player?.play()
            if (::playPauseBtn.isInitialized) playPauseBtn.text = "Pause"
        }
    }

    private fun loadSiblingImages(uri: Uri) {
        aiExecutor.execute {
            try {
                var bucketId: String? = null
                contentResolver.query(uri, arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null)?.use { c ->
                    if (c.moveToFirst()) bucketId = c.getString(0)
                }
                val list = mutableListOf<Uri>()
                val sel = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID} = ?" else null
                val args = if (bucketId != null) arrayOf(bucketId!!) else null
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (c.moveToNext()) list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)))
                }
                if (list.isNotEmpty()) {
                    folderImages = list
                    currentImageIndex = list.indexOfFirst { it == uri || it.lastPathSegment == uri.lastPathSegment }.coerceAtLeast(0)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIncomingIntent(intent) }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) mainHandler.postDelayed({ loadMediaUri(uri, scanFolder = true) }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this; is3DActive = true; depthEstimator = DepthEstimator(this)
        val root = FrameLayout(this)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (renderer.isImageMode && folderImages.size > 1 && e1 != null && abs(e2.x - e1.x) > 100 && abs(vx) > 150) {
                    currentImageIndex = if (e2.x < e1.x) (currentImageIndex + 1) % folderImages.size else (currentImageIndex - 1 + folderImages.size) % folderImages.size
                    loadMediaUri(folderImages[currentImageIndex], scanFolder = false)
                    Toast.makeText(this@MainActivity, "Image ${currentImageIndex + 1} / ${folderImages.size}", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }
        })

        // Hidden TextureView receives clean raw frames for AI (zero feedback loop!)
        val rawSamplerView = TextureView(this).apply { alpha = 0.01f }
        root.addView(rawSamplerView, FrameLayout.LayoutParams(256, 256))

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = GL3DRenderer { st ->
                mainHandler.post {
                    player = ExoPlayer.Builder(this@MainActivity).build().apply {
                        setVideoSurface(Surface(st))
                        repeatMode = ExoPlayer.REPEAT_MODE_ALL
                    }
                    startLoops(rawSamplerView)
                    handleIncomingIntent(intent)
                }
            }
            setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev); true }
        }
        root.addView(glView)

        controlsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xAA000000.toInt()); setPadding(28, 14, 28, 14) }
        val timelineRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeText = TextView(this).apply { text = "00:00 / 00:00"; setTextColor(0xFFFFFFFF.toInt()) }
        videoSeekBar = SeekBar(this).apply {
            max = 1000; layoutParams = LinearLayout.LayoutParams(650, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { if (u && (player?.duration ?: 0L) > 0) player?.seekTo((player!!.duration * p) / 1000L) }
                override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { isUserSeeking = false }
            })
        }
        timelineRow.addView(timeText); timelineRow.addView(videoSeekBar)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pickBtn = Button(this).apply { text = "Open File"; setOnClickListener { pickMedia.launch("*/*") } }
        playPauseBtn = Button(this).apply { text = "Pause"; setOnClickListener { player?.let { if (it.isPlaying) { it.pause(); text = "Play" } else { it.play(); text = "Pause" } } } }
        val stopBtn = Button(this).apply { text = "Stop"; setOnClickListener { player?.let { it.pause(); it.seekTo(0); playPauseBtn.text = "Play"; videoSeekBar.progress = 0 } } }
        val dLabel = TextView(this).apply { text = "  3D Depth: "; setTextColor(0xFFFFFFFF.toInt()) }
        val dSlider = SeekBar(this).apply {
            max = 60; progress = 20; layoutParams = LinearLayout.LayoutParams(280, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { renderer.depthStrength = p / 1000f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        btnRow.addView(pickBtn); btnRow.addView(playPauseBtn); btnRow.addView(stopBtn); btnRow.addView(dLabel); btnRow.addView(dSlider)
        controlsPanel.addView(timelineRow); controlsPanel.addView(btnRow)
        root.addView(controlsPanel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        window.insetsController?.let { it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }

    private fun formatTime(ms: Long): String { val s = ms.coerceAtLeast(0L) / 1000L; return String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L) }

    private fun startLoops(rawView: TextureView) {
        mainHandler.postDelayed(object : Runnable { override fun run() {
            player?.let { if (!renderer.isImageMode && !isUserSeeking && it.duration > 0) { videoSeekBar.progress = ((it.currentPosition * 1000L) / it.duration).toInt(); timeText.text = "${formatTime(it.currentPosition)} / ${formatTime(it.duration)}" } }
            mainHandler.postDelayed(this, 300)
        }}, 300)
        mainHandler.postDelayed(object : Runnable { override fun run() {
            if (!renderer.isImageMode && player?.isPlaying == true && !isProcessingAI && glView.width > 0) {
                isProcessingAI = true
                // Capture ONLY the Left Eye half (0..width/2) so AI never sees a double-split image!
                val fullBmp = Bitmap.createBitmap(glView.width / 2, glView.height, Bitmap.Config.ARGB_8888)
                try {
                    PixelCopy.request(glView, Rect(0, 0, glView.width / 2, glView.height), fullBmp, { r ->
                        if (r == PixelCopy.SUCCESS) {
                            aiExecutor.execute {
                                depthEstimator?.estimateDepth(fullBmp)?.let { renderer.updateDepthMap(it) }
                                isProcessingAI = false
                            }
                        } else isProcessingAI = false
                    }, mainHandler)
                } catch (_: Exception) { isProcessingAI = false }
            }
            mainHandler.postDelayed(this, 100)
        }}, 500)
    }

    override fun onDestroy() { super.onDestroy(); is3DActive = false; instance = null; player?.release(); depthEstimator?.close() }
}
