package com.leon.ai3dplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DepthEstimator(context: Context) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 256

    init {
        val model = loadModelFile(context, "midas.tflite")
        val options = Interpreter.Options()
        try {
            gpuDelegate = GpuDelegate()
            options.addDelegate(gpuDelegate)
        } catch (e: Exception) {
            options.setNumThreads(4)
        }
        interpreter = Interpreter(model, options)
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun estimateDepth(bitmap: Bitmap): Bitmap {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f - 0.485f) / 0.229f)
            inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f - 0.456f) / 0.224f)
            inputBuffer.putFloat(((pixel and 0xFF) / 255.0f - 0.406f) / 0.225f)
        }

        val outputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        interpreter?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val depths = FloatArray(inputSize * inputSize)
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        for (i in depths.indices) {
            val v = outputBuffer.float
            depths[i] = v
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = (maxVal - minVal).coerceAtLeast(1e-5f)
        val outPixels = IntArray(inputSize * inputSize)
        for (i in depths.indices) {
            val norm = ((depths[i] - minVal) / range * 255f).toInt().coerceIn(0, 255)
            outPixels[i] = Color.rgb(norm, norm, norm)
        }

        return Bitmap.createBitmap(outPixels, inputSize, inputSize, Bitmap.Config.ARGB_8888)
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
