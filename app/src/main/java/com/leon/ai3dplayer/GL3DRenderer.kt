package com.leon.ai3dplayer

import android.graphics.*
import android.opengl.*
import java.nio.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GL3DRenderer(
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
    private val onCleanFrameReady: (Bitmap) -> Unit
) : GLSurfaceView.Renderer {

    private var program = 0; private var copyProgram = 0
    private var videoTexId = 0; private var depthTexId = 0; private var prevDepthTexId = 0
    private var imageTexId = 0; private var fboId = 0; private var fboTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var viewW = 1; private var viewH = 1; private var lastCaptureTime = 0L; private var blendStartTime = 0L

    @Volatile var depthStrength = 0.022f; @Volatile var isCrossEye = true
    @Volatile var isImageMode = false; @Volatile var isPlayingVideo = false; @Volatile var isAiBusy = false
    @Volatile var mediaAspect = 16f / 9f
    @Volatile private var pendingDepthBitmap: Bitmap? = null
    @Volatile private var pendingImageBitmap: Bitmap? = null

    private val quadCoords = floatArrayOf(-1f, 1f, 0f, 0f, -1f, -1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, -1f, 1f, 1f)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadCoords).apply { position(0) }
    private val readBuf: ByteBuffer = ByteBuffer.allocateDirect(256 * 256 * 4).order(ByteOrder.nativeOrder())

    private val vsCode = "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }"
    private val copyFsCode = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES uVideoTex; void main() { gl_FragColor = texture2D(uVideoTex, vec2(vTexCoord.x, 1.0 - vTexCoord.y)); }"

    private val fsCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uVideoTex;
        uniform sampler2D uImageTex;
        uniform sampler2D uDepthTex;
        uniform sampler2D uPrevDepthTex;
        uniform float uBlend;
        uniform float uDepthStrength;
        uniform float uEyeDir;
        uniform int uUseImage;
        uniform vec2 uAspectScale;

        float getSmoothDepth(vec2 uv) {
            vec2 px = vec2(0.012, 0.012);
            float c = mix(texture2D(uPrevDepthTex, uv).r, texture2D(uDepthTex, uv).r, uBlend);
            float d = c * 0.36;
            d += mix(texture2D(uPrevDepthTex, uv + vec2(px.x, 0.0)).r, texture2D(uDepthTex, uv + vec2(px.x, 0.0)).r, uBlend) * 0.16;
            d += mix(texture2D(uPrevDepthTex, uv - vec2(px.x, 0.0)).r, texture2D(uDepthTex, uv - vec2(px.x, 0.0)).r, uBlend) * 0.16;
            d += mix(texture2D(uPrevDepthTex, uv + vec2(0.0, px.y)).r, texture2D(uDepthTex, uv + vec2(0.0, px.y)).r, uBlend) * 0.16;
            d += mix(texture2D(uPrevDepthTex, uv - vec2(0.0, px.y)).r, texture2D(uDepthTex, uv - vec2(0.0, px.y)).r, uBlend) * 0.16;
            return d;
        }

        void main() {
            float side = (vTexCoord.x < 0.5) ? -1.0 : 1.0;
            vec2 rawUV = vec2(fract(vTexCoord.x * 2.0), vTexCoord.y);
            vec2 fittedUV = (rawUV - 0.5) * uAspectScale + 0.5;
            if (fittedUV.x < 0.0 || fittedUV.x > 1.0 || fittedUV.y < 0.0 || fittedUV.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            vec2 eyeUV = (fittedUV - 0.5) * 0.94 + 0.5;
            float depth = getSmoothDepth(clamp(eyeUV, 0.005, 0.995));
            float parallax = side * uEyeDir * (depth - 0.45) * uDepthStrength;
            vec2 finalUV = clamp(vec2(eyeUV.x + parallax, eyeUV.y), 0.002, 0.998);
            gl_FragColor = (uUseImage == 1) ? texture2D(uImageTex, finalUV) : texture2D(uVideoTex, finalUV);
        }
    """.trimIndent()

    fun updateDepthMap(bmp: Bitmap) { pendingDepthBitmap = bmp }
    fun setStaticImage(bmp: Bitmap) { isImageMode = true; mediaAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1); pendingImageBitmap = bmp }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProg(vsCode, fsCode); copyProgram = buildProg(vsCode, copyFsCode)
        val texIds = IntArray(5); GLES20.glGenTextures(5, texIds, 0)
        videoTexId = texIds.component1(); depthTexId = texIds.component2(); prevDepthTexId = texIds.component3(); imageTexId = texIds.component4(); fboTexId = texIds.component5()
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        listOf(depthTexId, prevDepthTexId, imageTexId, fboTexId).forEach { setup2D(it) }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 256, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val fbos = IntArray(1); GLES20.glGenFramebuffers(1, fbos, 0); fboId = fbos.component1()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        surfaceTexture = SurfaceTexture(videoTexId).also { onSurfaceReady(it) }
    }

    private fun setup2D(id: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { viewW = w; viewH = h; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        if (!isImageMode) surfaceTexture?.updateTexImage()
        val now = android.os.SystemClock.uptimeMillis()
        if (!isImageMode && isPlayingVideo && !isAiBusy && now - lastCaptureTime > 120) {
            lastCaptureTime = now; isAiBusy = true
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId); GLES20.glViewport(0, 0, 256, 256)
            GLES20.glUseProgram(copyProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uVideoTex"), 0)
            drawQuad(copyProgram); readBuf.rewind()
            GLES20.glReadPixels(0, 0, 256, 256, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf); readBuf.rewind()
            val cleanBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(readBuf) }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, viewW, viewH)
            onCleanFrameReady(cleanBmp)
        }
        pendingDepthBitmap?.let {
            val tmp = prevDepthTexId; prevDepthTexId = depthTexId; depthTexId = tmp
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            blendStartTime = now; pendingDepthBitmap = null
        }
        pendingImageBitmap?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            pendingImageBitmap = null
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(program)
        val halfScreenAspect = (viewW * 0.5f) / viewH.coerceAtLeast(1)
        val sx = if (mediaAspect > halfScreenAspect) 1.0f else halfScreenAspect / mediaAspect
        val sy = if (mediaAspect > halfScreenAspect) mediaAspect / halfScreenAspect else 1.0f
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uAspectScale"), sx, sy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVideoTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepthTex"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImageTex"), 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevDepthTexId); GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrevDepthTex"), 3)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlend"), ((now - blendStartTime) / 120f).coerceIn(0f, 1f))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDepthStrength"), depthStrength)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEyeDir"), if (isCrossEye) -1.0f else 1.0f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uUseImage"), if (isImageMode) 1 else 0)
        drawQuad(program)
    }

    private fun drawQuad(prog: Int) {
        val p = GLES20.glGetAttribLocation(prog, "aPosition"); val t = GLES20.glGetAttribLocation(prog, "aTexCoord")
        vertexBuffer.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer); GLES20.glEnableVertexAttribArray(p)
        vertexBuffer.position(2); GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer); GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProg(v: String, f: String): Int = GLES20.glCreateProgram().also {
        GLES20.glAttachShader(it, GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { s -> GLES20.glShaderSource(s, v); GLES20.glCompileShader(s) })
        GLES20.glAttachShader(it, GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { s -> GLES20.glShaderSource(s, f); GLES20.glCompileShader(s) })
        GLES20.glLinkProgram(it)
    }
}
