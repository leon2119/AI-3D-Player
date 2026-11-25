package com.leon.ai3dplayer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GL3DRenderer(private val onSurfaceReady: (SurfaceTexture) -> Unit) : GLSurfaceView.Renderer {

    private var program = 0
    private var videoTexId = 0
    private var depthTexId = 0
    private var imageTexId = 0
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile var depthStrength = 0.045f
    @Volatile var isImageMode = false
    @Volatile private var pendingDepthBitmap: Bitmap? = null
    @Volatile private var pendingImageBitmap: Bitmap? = null

    private val quadCoords = floatArrayOf(
        -1f,  1f, 0f, 0f,
        -1f, -1f, 0f, 1f,
         1f,  1f, 1f, 0f,
         1f, -1f, 1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadCoords).apply { position(0) }

    private val vertexShaderCode = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uVideoTex;
        uniform sampler2D uImageTex;
        uniform sampler2D uDepthTex;
        uniform float uDepthStrength;
        uniform int uUseImage;

        float getSmoothDepth(vec2 uv) {
            vec2 px = vec2(0.004, 0.004);
            float d = texture2D(uDepthTex, uv).r * 0.4;
            d += texture2D(uDepthTex, uv + vec2(px.x, 0.0)).r * 0.15;
            d += texture2D(uDepthTex, uv - vec2(px.x, 0.0)).r * 0.15;
            d += texture2D(uDepthTex, uv + vec2(0.0, px.y)).r * 0.15;
            d += texture2D(uDepthTex, uv - vec2(0.0, px.y)).r * 0.15;
            return d;
        }

        void main() {
            float eyeSide = (vTexCoord.x < 0.5) ? -1.0 : 1.0;
            vec2 rawUV = vec2(fract(vTexCoord.x * 2.0), vTexCoord.y);

            // Slight zoom (1.10x) so edge warping never leaves stretched borders
            vec2 eyeUV = (rawUV - 0.5) * 0.90 + 0.5;

            // Subtle 3D stereoscopic perspective tilt (toe-in camera angle)
            float tilt = (eyeUV.x - 0.5) * eyeSide * (uDepthStrength * 0.45);
            eyeUV.y = (eyeUV.y - 0.5) * (1.0 + tilt) + 0.5;

            // Multi-step ray-marched depth parallax for true 3D volume
            vec2 bestUV = eyeUV;
            float maxDepth = -1.0;
            for (int i = 0; i < 6; i++) {
                float stepFrac = float(i) / 5.0;
                vec2 sampleUV = eyeUV + vec2(eyeSide * (stepFrac - 0.5) * uDepthStrength, 0.0);
                float d = getSmoothDepth(clamp(sampleUV, 0.002, 0.998));
                if (d >= stepFrac && d > maxDepth) {
                    maxDepth = d;
                    bestUV = sampleUV;
                }
            }

            float finalDepth = getSmoothDepth(clamp(bestUV, 0.002, 0.998));
            float parallax = eyeSide * (finalDepth - 0.45) * uDepthStrength;
            vec2 finalUV = vec2(eyeUV.x + parallax, eyeUV.y);

            if (finalUV.x < 0.0 || finalUV.x > 1.0 || finalUV.y < 0.0 || finalUV.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else if (uUseImage == 1) {
                gl_FragColor = texture2D(uImageTex, finalUV);
            } else {
                gl_FragColor = texture2D(uVideoTex, finalUV);
            }
        }
    """.trimIndent()

    fun updateDepthMap(bmp: Bitmap) { pendingDepthBitmap = bmp }
    fun setStaticImage(bmp: Bitmap) { isImageMode = true; pendingImageBitmap = bmp }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        val texIds = IntArray(3)
        GLES20.glGenTextures(3, texIds, 0)
        videoTexId = texIds.component1()
        depthTexId = texIds.component2()
        imageTexId = texIds.component3()

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        setup2DTexture(depthTexId)
        setup2DTexture(imageTexId)

        surfaceTexture = SurfaceTexture(videoTexId).also { onSurfaceReady(it) }
    }

    private fun setup2DTexture(id: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!isImageMode) {
            surfaceTexture?.updateTexImage()
        }

        pendingDepthBitmap?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            pendingDepthBitmap = null
        }

        pendingImageBitmap?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            pendingImageBitmap = null
        }

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVideoTex"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uDepthTex"), 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImageTex"), 2)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDepthStrength"), depthStrength)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uUseImage"), if (isImageMode) 1 else 0)

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(posHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
    }
}
