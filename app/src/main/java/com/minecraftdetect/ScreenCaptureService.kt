package com.minecraftdetect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture"
        private const val CAPTURE_INTERVAL_MS = 200L // ~5 FPS

        var onDetectionsReady: ((List<Detection>, Int, Int) -> Unit)? = null
    }

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var detector: PlayerDetector? = null
    private var mediaProjectionCallback: android.media.projection.MediaProjection.Callback? = null
    private val isRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        detector = PlayerDetector(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra("data", android.content.Intent::class.java)

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Invalid MediaProjection data")
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        val callback = object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }
        mediaProjectionCallback = callback
        mediaProjection?.registerCallback(callback, null)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Minecraft Detect")
            .setContentText("正在检测玩家...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startCapture()
        return START_STICKY
    }

    private fun startCapture() {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val displayMetrics = resources.displayMetrics

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        Log.d(TAG, "Display: ${width}x${height} @ ${density}dpi")

        // Use smaller capture size for performance (e.g., half resolution)
        val captureWidth = width.coerceAtMost(1440)
        val captureHeight = height.coerceAtMost(2560)
        // Scale capture to maintain aspect ratio
        val scale = minOf(
            captureWidth.toFloat() / width,
            captureHeight.toFloat() / height
        )
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        imageReader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2)

        // Start background thread for image processing
        captureThread = HandlerThread("CaptureProcessor").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        var lastCaptureTime = 0L
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (now - lastCaptureTime < CAPTURE_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastCaptureTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
            if (planes.isEmpty()) {
                image.close()
                return@setOnImageAvailableListener
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val imageWidth = image.width
            val imageHeight = image.height

            // Convert RGBA buffer to Bitmap
            val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            image.close()

            // Run detection
            val detections = detector?.detect(bitmap) ?: emptyList()
            bitmap.recycle()

            // Post results to UI
            onDetectionsReady?.invoke(detections, width, height)
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MinecraftCapture",
            scaledWidth, scaledHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        isRunning.set(true)
        Log.d(TAG, "Capture started: ${scaledWidth}x${scaledHeight}")
    }

    private fun stopCapture() {
        isRunning.set(false)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    override fun onDestroy() {
        stopCapture()
        mediaProjection?.stop()
        mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        mediaProjectionCallback = null
        mediaProjection = null
        detector?.close()
        detector = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
