package com.leon.ai3dplayer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.*
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import java.util.concurrent.Executors

class Screen3DOverlayService : Service() {
    companion object { var isRunning = false }
    private var wm: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var stopPill: Button? = null
    private var glView: GLSurfaceView? = null
    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var depthEstimator: DepthEstimator? = null
    private val aiExec = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("data")
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val chId = "ai_3d_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(chId, "AI 3D Screen Overlay", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, chId).setContentTitle("AI 3D Overlay Active").setSmallIcon(R.drawable.ic_3d).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1001, notif)

        isRunning = true
        depthEstimator = DepthEstimator(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels; val dpi = dm.densityDpi
        setupOverlay(code, data, w, h, dpi)
        return START_NOT_STICKY
    }

    private fun setupOverlay(code: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)

        lateinit var renderer: GL3DRenderer
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = GL3DRenderer(
                onSurfaceReady = { st ->
                    st.setDefaultBufferSize(w, h)
                    vDisplay = projection?.createVirtualDisplay("AI3D", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, Surface(st), null, null)
                },
                onCleanFrameReady = { bmp ->
                    aiExec.execute { depthEstimator?.estimateDepth(bmp)?.let { renderer.updateDepthMap(it) }; renderer.isAiBusy = false }
                }
            ).apply {
                isPlayingVideo = true
                mediaAspect = w.toFloat() / h.coerceAtLeast(1)
                viewMode = MainActivity.savedViewMode
                depthStrength = MainActivity.savedDepth
                eyeSpacing = MainActivity.savedIpd
            }
            setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        overlayRoot = FrameLayout(this).apply { addView(glView) }
        val passFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, passFlags, PixelFormat.TRANSLUCENT)
        wm?.addView(overlayRoot, lp)

        stopPill = Button(this).apply {
            text = "Exit 3D"; alpha = 0.65f
            setOnClickListener { stopSelf() }
        }
        val btnLp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        wm?.addView(stopPill, btnLp)
    }

    override fun onDestroy() {
        super.onDestroy(); isRunning = false
        try { overlayRoot?.let { wm?.removeView(it) }; stopPill?.let { wm?.removeView(it) } } catch (_: Exception) {}
        vDisplay?.release(); projection?.stop(); depthEstimator?.close()
    }
}
