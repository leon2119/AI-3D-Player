package com.leon.ai3dplayer

import android.content.ContentUris
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
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
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var folderImages = listOf<Uri>()
    private var currentImageIndex = -1

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            loadMediaUri(it, scanFolder = true)
        }
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
            val list = mutableListOf<Uri>()
            var targetName: String? = null
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) targetName = c.getString(0)
                }
                if (targetName == null) targetName = uri.lastPathSegment?.substringAfterLast("/")
                var bucketId: String? = null
                if (targetName != null) {
                    contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.BUCKET_ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(targetName!!), null)?.use { c ->
                        if (c.moveToFirst()) bucketId = c.getString(0)
                    }
                }
                val sel = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID} = ?" else null
                val args = if (bucketId != null) arrayOf(bucketId!!) else null
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), sel, args, "${MediaStore.Images.Media.DISPLAY_NAME} ASC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    var idx = 0
                    while (c.moveToNext()) {
                        list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)))
                        if (targetName != null && c.getString(nameCol) == targetName) currentImageIndex = idx
                        idx++
                    }
                }
            } catch (_: Exception) {}
            if (list.isNotEmpty()) {
                folderImages = list
                if (currentImageIndex !in list.indices) currentImageIndex = 0
            }
        }
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIncomingIntent(intent) }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) mainHandler.postDelayed({ loadMediaUri(uri, scanFolder = true) }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
        instance = this; is3DActive = true; depthEstimator = DepthEstimator(this)
        val root = FrameLayout(this)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE; return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (renderer.isImageMode && e1 != null && abs(e2.x - e1.x) > 80) {
                    if (folderImages.size > 1) {
                        currentImageIndex = if (e2.x < e1.x) (currentImageIndex + 1) % folderImages.size else (currentImageIndex - 1 + folderImages.size) % folderImages.size
                        loadMediaUri(folderImages[currentImageIndex], scanFolder = false)
                        Toast.makeText(this@MainActivity, "Image ${currentImageIndex + 1} / ${folderImages.size}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Only 1 image accessible in this folder", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        })

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = GL3DRenderer(
                onSurfaceReady = { st -> mainHandler.post {
                    player = ExoPlayer.Builder(this@MainActivity).build().apply {
                        setVideoSurface(Surface(st)); repeatMode = ExoPlayer.REPEAT_MODE_ALL
                        addListener(object : Player.Listener {
                            override fun onVideoSizeChanged(vs: VideoSize) {
                                if (vs.width > 0 && vs.height > 0) renderer.mediaAspect = vs.width.toFloat() / vs.height
                            }
                        })
                    }
                    startTimerLoop(); handleIncomingIntent(intent)
                } },
                onCleanFrameReady = { bmp -> aiExecutor.execute { depthEstimator?.estimateDepth(bmp)?.let { renderer.updateDepthMap(it) }; renderer.isAiBusy = false } }
            )
            setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        }
        root.addView(glView)

        controlsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(0xAA000000.toInt()); setPadding(24, 12, 24, 12) }
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeText = TextView(this).apply { text = "00:00 / 00:00"; setTextColor(0xFFFFFFFF.toInt()) }
        videoSeekBar = SeekBar(this).apply {
            max = 1000; layoutParams = LinearLayout.LayoutParams(620, -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { if (u && (player?.duration ?: 0L) > 0) player?.seekTo((player!!.duration * p) / 1000L) }
                override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }; override fun onStopTrackingTouch(sb: SeekBar?) { isUserSeeking = false }
            })
        }
        tRow.addView(timeText); tRow.addView(videoSeekBar)

        val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pickBtn = Button(this).apply { text = "Open"; setOnClickListener { pickMedia.launch(arrayOf("video/*", "image/*")) } }
        playPauseBtn = Button(this).apply { text = "Pause"; setOnClickListener { player?.let { if (it.isPlaying) { it.pause(); text = "Play" } else { it.play(); text = "Pause" } } } }
        val stopBtn = Button(this).apply { text = "Stop"; setOnClickListener { player?.let { it.pause(); it.seekTo(0); playPauseBtn.text = "Play"; videoSeekBar.progress = 0 } } }
        val modeNames = listOf("K4 Goggles", "Cross-Eye", "VR Full")
        val modeBtn = Button(this).apply { text = "K4 Goggles"; setOnClickListener { renderer.viewMode = (renderer.viewMode + 1) % 3; text = modeNames[renderer.viewMode] } }
        val dLabel = TextView(this).apply { text = " Depth:"; setTextColor(0xFFFFFFFF.toInt()) }
        val dSlider = SeekBar(this).apply {
            max = 60; progress = 22; layoutParams = LinearLayout.LayoutParams(160, -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { renderer.depthStrength = p / 1000f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val sLabel = TextView(this).apply { text = " IPD:"; setTextColor(0xFFFFFFFF.toInt()) }
        val sSlider = SeekBar(this).apply {
            max = 45; progress = 25; layoutParams = LinearLayout.LayoutParams(160, -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { renderer.eyeSpacing = (55 + p) / 100f }
                override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bRow.addView(pickBtn); bRow.addView(playPauseBtn); bRow.addView(stopBtn); bRow.addView(modeBtn); bRow.addView(dLabel); bRow.addView(dSlider); bRow.addView(sLabel); bRow.addView(sSlider)
        controlsPanel.addView(tRow); controlsPanel.addView(bRow)
        root.addView(controlsPanel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        window.insetsController?.let { it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
    }

    private fun formatTime(ms: Long): String { val s = ms.coerceAtLeast(0L) / 1000L; return String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L) }
    private fun startTimerLoop() {
        mainHandler.postDelayed(object : Runnable { override fun run() {
            val p = player; renderer.isPlayingVideo = (p?.isPlaying == true)
            if (p != null && !renderer.isImageMode && !isUserSeeking && p.duration > 0) { videoSeekBar.progress = ((p.currentPosition * 1000L) / p.duration).toInt(); timeText.text = "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}" }
            mainHandler.postDelayed(this, 200)
        }}, 200)
    }
    override fun onDestroy() { super.onDestroy(); is3DActive = false; instance = null; player?.release(); depthEstimator?.close() }
}
