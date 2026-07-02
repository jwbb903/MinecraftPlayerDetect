package com.minecraftdetect

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1000
    }

    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var fpsText: TextView
    private var lastFrameTime = 0L

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            statusText.text = "权限被拒绝"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root layout
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Detection overlay (fills the screen)
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
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        })

        // FPS counter
        fpsText = TextView(this).apply {
            text = ""
            setTextColor(Color.GREEN)
            textSize = 16f
        }
        root.addView(fpsText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.topMargin = 100
            lp.rightMargin = 20
        })

        // Start button at bottom
        val startButton = Button(this).apply {
            text = "开始检测"
            setOnClickListener {
                requestScreenCapture()
            }
        }
        root.addView(startButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also { lp ->
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
            lp.bottomMargin = 100
        })

        setContentView(root)

        // Set up detection callback
        ScreenCaptureService.onDetectionsReady = { detections, displayWidth, displayHeight ->
            runOnUiThread {
                overlayView.setDetections(detections, displayWidth, displayHeight)
                val now = System.currentTimeMillis()
                if (lastFrameTime > 0) {
                    val fps = 1000f / (now - lastFrameTime)
                    fpsText.text = "FPS: %.1f".format(fps)
                }
                lastFrameTime = now
                statusText.text = "检测中... ${detections.size} 个玩家"
            }
        }
    }

    private fun requestScreenCapture() {
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

    override fun onDestroy() {
        ScreenCaptureService.onDetectionsReady = null
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
            // Scale to view coordinates
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
