package com.minecraftdetect

import android.content.Intent
import android.graphics.*
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAPTURE_INTERVAL_MS = 200L
    }

    // Common UI
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var fpsText: TextView
    private var lastFrameTime = 0L

    // Video mode
    private var textureView: TextureView? = null
    private var mediaPlayer: MediaPlayer? = null
    private var detector: PlayerDetector? = null
    private var videoDetectionThread: HandlerThread? = null
    private var videoDetectionHandler: Handler? = null
    private var isVideoMode = false
    private var btnPlayPause: Button? = null
    private var seekBar: SeekBar? = null
    private var videoControlsRow: LinearLayout? = null
    private var isDraggingSeekBar = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            statusText.text = "权限被拒绝"
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Take persistable permission so it still works after config changes
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistable permission", e)
            }
            startVideoMode(uri)
        }
    }

    /** Shared callback for both screen capture and video modes */
    private val detectionCallback: (List<Detection>, Int, Int) -> Unit = { detections, displayWidth, displayHeight ->
        overlayView.setDetections(detections, displayWidth, displayHeight)
        val now = System.currentTimeMillis()
        if (lastFrameTime > 0) {
            val fps = 1000f / (now - lastFrameTime)
            fpsText.text = "FPS: %.1f".format(fps)
        }
        lastFrameTime = now
        val prefix = if (isVideoMode) "视频" else ""
        statusText.text = "${prefix}检测中... ${detections.size} 个玩家"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // TextureView for video playback (hidden initially)
        textureView = TextureView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(textureView)

        // Detection overlay (on top of everything)
        overlayView = DetectionOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // Status text at top
        statusText = TextView(this).apply {
            text = "点击下方按钮开始检测"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.topMargin = 100
            lp.gravity = Gravity.CENTER_HORIZONTAL
        })

        // FPS counter at top right
        fpsText = TextView(this).apply {
            text = ""
            setTextColor(Color.GREEN)
            textSize = 16f
        }
        root.addView(fpsText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = 100
            lp.rightMargin = 20
        })

        // ===== Bottom control rows =====

        // Row 1: mode selection buttons
        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnScreenCapture = Button(this).apply {
            text = "屏幕捕捉"
            setOnClickListener { requestScreenCapture() }
        }
        btnRow1.addView(btnScreenCapture, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 0, 16, 0) })

        val btnSelectVideo = Button(this).apply {
            text = "选择视频"
            setOnClickListener {
                if (isVideoMode) stopVideoMode()
                videoPickerLauncher.launch(arrayOf("video/*"))
            }
        }
        btnRow1.addView(btnSelectVideo, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(btnRow1, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            lp.bottomMargin = 200
        })

        // Row 2: video playback controls (hidden initially)
        videoControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        btnPlayPause = Button(this).apply {
            text = "暂停"
            setOnClickListener { togglePlayPause() }
        }
        videoControlsRow!!.addView(btnPlayPause!!, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 0, 16, 0) })

        val btnStop = Button(this).apply {
            text = "停止"
            setOnClickListener { stopVideoMode() }
        }
        videoControlsRow!!.addView(btnStop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(videoControlsRow!!, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            lp.bottomMargin = 110
        })

        // SeekBar (hidden initially)
        seekBar = SeekBar(this).apply {
            visibility = View.GONE
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && mediaPlayer != null && mediaPlayer!!.duration > 0) {
                        val seekTo = (mediaPlayer!!.duration.toLong() * progress) / 1000
                        mediaPlayer!!.seekTo(seekTo.toInt())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isDraggingSeekBar = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isDraggingSeekBar = false
                }
            })
        }
        root.addView(seekBar!!, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.marginStart = 32
            lp.marginEnd = 32
            lp.gravity = Gravity.BOTTOM
            lp.bottomMargin = 260
        })

        setContentView(root)

        // Register detection callback for screen capture mode
        ScreenCaptureService.onDetectionsReady = detectionCallback
    }

    // ===== Screen Capture Mode =====

    private fun requestScreenCapture() {
        if (isVideoMode) stopVideoMode()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        startForegroundService(intent)
        statusText.text = "正在连接..."
    }

    // ===== Video Player Mode =====

    private fun startVideoMode(uri: Uri) {
        isVideoMode = true

        // Stop screen capture if running
        stopService(Intent(this, ScreenCaptureService::class.java))

        // Init detector for video mode
        if (detector == null) {
            detector = PlayerDetector(this)
        }

        // Show video-related views
        textureView?.visibility = View.VISIBLE
        videoControlsRow?.visibility = View.VISIBLE
        seekBar?.visibility = View.VISIBLE
        statusText.text = "正在加载视频..."

        // If TextureView surface is already ready, start playback immediately
        if (textureView!!.isAvailable) {
            startMediaPlayer(textureView!!.surfaceTexture!!)
        }

        // Listen for TextureView surface events
        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startMediaPlayer(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopMediaPlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun startMediaPlayer(surfaceTexture: SurfaceTexture) {
        if (mediaPlayer != null) {
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        statusText.text = "正在缓冲..."

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@MainActivity, videoUri)
                setSurface(Surface(surfaceTexture))
                isLooping = false

                setOnPreparedListener { mp ->
                    mp.start()
                    statusText.text = "视频检测中..."
                    startDetectionLoop()
                    startSeekBarUpdater()
                }

                setOnCompletionListener {
                    runOnUiThread { statusText.text = "播放完成" }
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    runOnUiThread { statusText.text = "视频播放出错 ($what)" }
                    true
                }

                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up MediaPlayer", e)
                statusText.text = "无法播放视频: ${e.message}"
                isVideoMode = false
            }
        }
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null
    }

    /** Periodically grab frames from TextureView and run detection */
    private fun startDetectionLoop() {
        videoDetectionThread = HandlerThread("VideoDetection").apply { start() }
        videoDetectionHandler = Handler(videoDetectionThread!!.looper)

        val runnable = object : Runnable {
            override fun run() {
                if (!isVideoMode) return
                if (mediaPlayer == null || !mediaPlayer!!.isPlaying) {
                    videoDetectionHandler?.postDelayed(this, CAPTURE_INTERVAL_MS)
                    return
                }

                try {
                    // Capture current frame from TextureView
                    val tv = textureView ?: return
                    val bitmap = tv.bitmap ?: run {
                        videoDetectionHandler?.postDelayed(this, CAPTURE_INTERVAL_MS)
                        return
                    }

                    if (bitmap.width <= 1 || bitmap.height <= 1) {
                        bitmap.recycle()
                        videoDetectionHandler?.postDelayed(this, CAPTURE_INTERVAL_MS)
                        return
                    }

                    val detections = detector?.detect(bitmap) ?: emptyList()
                    bitmap.recycle()

                    val displayMetrics = resources.displayMetrics
                    runOnUiThread {
                        detectionCallback(detections, displayMetrics.widthPixels, displayMetrics.heightPixels)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Detection error", e)
                }

                videoDetectionHandler?.postDelayed(this, CAPTURE_INTERVAL_MS)
            }
        }

        videoDetectionHandler?.post(runnable)
    }

    private fun startSeekBarUpdater() {
        val handler = Handler(mainLooper)
        val updater = object : Runnable {
            override fun run() {
                if (!isVideoMode || mediaPlayer == null) return
                if (!isDraggingSeekBar && mediaPlayer!!.duration > 0) {
                    val progress = (mediaPlayer!!.currentPosition.toLong() * 1000) / mediaPlayer!!.duration
                    seekBar?.progress = progress.toInt()
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(updater)
    }

    private fun togglePlayPause() {
        if (mediaPlayer == null) return
        if (mediaPlayer!!.isPlaying) {
            mediaPlayer!!.pause()
            btnPlayPause?.text = "继续"
        } else {
            mediaPlayer!!.start()
            btnPlayPause?.text = "暂停"
        }
    }

    private fun stopVideoMode() {
        isVideoMode = false
        stopDetectionLoop()
        stopMediaPlayer()
        videoUri = null

        textureView?.visibility = View.GONE
        videoControlsRow?.visibility = View.GONE
        seekBar?.visibility = View.GONE
        btnPlayPause?.text = "暂停"
        seekBar?.progress = 0

        statusText.text = "点击下方按钮开始检测"
        fpsText.text = ""
        overlayView.setDetections(emptyList(), 1, 1)
    }

    private fun stopDetectionLoop() {
        videoDetectionHandler?.removeCallbacksAndMessages(null)
        videoDetectionThread?.quitSafely()
        videoDetectionThread = null
        videoDetectionHandler = null
    }

    // ===== Lifecycle =====

    override fun onDestroy() {
        stopVideoMode()
        ScreenCaptureService.onDetectionsReady = null
        detector?.close()
        detector = null
        super.onDestroy()
    }
}

/**
 * Custom View that draws detection bounding boxes.
 */
class DetectionOverlayView(context: android.content.Context) : View(context) {

    private val detections = mutableListOf<Detection>()
    @Volatile private var displayWidth = 1080
    @Volatile private var displayHeight = 1920

    private val boxPaint = Paint().apply {
        color = Color.argb(200, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(40, 0, 255, 0)
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint().apply {
        color = Color.argb(200, 0, 255, 0)
        textSize = 40f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setDetections(dets: List<Detection>, dispW: Int, dispH: Int) {
        detections.clear()
        detections.addAll(dets)
        displayWidth = dispW
        displayHeight = dispH
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / displayWidth
        val scaleY = height.toFloat() / displayHeight

        for (d in detections) {
            val box = d.toRectF(displayWidth, displayHeight)
            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            val rect = RectF(left, top, right, bottom)

            // Fill
            canvas.drawRect(rect, fillPaint)

            // Border
            canvas.drawRect(rect, boxPaint)

            // Label background
            val labelText = "Player %.0f%%".format(d.confidence * 100)
            val labelWidth = labelPaint.measureText(labelText)
            val labelHeight = 48f
            canvas.drawRect(
                left,
                (top - labelHeight).coerceAtLeast(0f),
                left + labelWidth + 16f,
                top,
                labelBgPaint
            )

            // Label text
            canvas.drawText(labelText, left + 8f, top - 8f, labelPaint)
        }
    }
}
