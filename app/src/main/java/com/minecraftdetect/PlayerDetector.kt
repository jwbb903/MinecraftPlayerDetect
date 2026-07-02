package com.minecraftdetect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class PlayerDetector(context: Context) {

    companion object {
        private const val TAG = "PlayerDetector"
        private const val MODEL_INPUT_SIZE = 640
        private const val NUM_CHANNELS = 3
        private const val NUM_DETECTIONS = 8400
        private const val NUM_OUTPUT_VALUES = 5 // [cx, cy, w, h, conf]
    }

    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context)
        val options = Interpreter.Options().apply {
            setNumThreads(4)

            // Try GPU delegate for faster inference
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(
                        GpuDelegate.Options().apply {
                            setPrecisionLossAllowed(true)
                            setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        }
                    )
                    addDelegate(delegate)
                    Log.i(TAG, "GPU delegate enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate not available, using CPU", e)
            }
        }
        interpreter = Interpreter(model, options)
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd("best.tflite")
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = afd.startOffset
        val declaredLength = afd.declaredLength
        val mapped = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        fileChannel.close()
        inputStream.close()
        return mapped
    }

    /**
     * Preprocess a Bitmap to NCHW float buffer.
     * Input: ARGB_8888 Bitmap
     * Output: [1, 3, 640, 640] float32 ByteBuffer (NCHW order)
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        // Resize to 640x640
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)

        val buffer = ByteBuffer.allocateDirect(1 * NUM_CHANNELS * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        // NCHW layout: for each channel, for each row, for each col
        for (c in 0 until NUM_CHANNELS) {
            for (y in 0 until MODEL_INPUT_SIZE) {
                for (x in 0 until MODEL_INPUT_SIZE) {
                    val pixel = pixels[y * MODEL_INPUT_SIZE + x]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f   // R
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f    // G
                        2 -> (pixel and 0xFF) / 255.0f             // B
                        else -> 0f
                    }
                    buffer.putFloat(value)
                }
            }
        }

        buffer.rewind()
        resized.recycle()
        return buffer
    }

    /**
     * Run detection on a Bitmap.
     * Returns list of detections after NMS.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val startTime = System.nanoTime()

        // 1. Preprocess
        val inputBuffer = preprocess(bitmap)

        // 2. Run inference
        // Output shape: [1, 5, 8400]
        val output = Array(1) { Array(NUM_OUTPUT_VALUES) { FloatArray(NUM_DETECTIONS) } }
        interpreter.run(inputBuffer, output)

        val inferenceTime = (System.nanoTime() - startTime) / 1_000_000
        Log.d(TAG, "Inference: ${inferenceTime}ms")

        // 3. NMS
        val raw = output[0] // [5][8400]
        val detections = NMS.filter(raw)

        return detections
    }

    fun close() {
        interpreter.close()
    }
}
