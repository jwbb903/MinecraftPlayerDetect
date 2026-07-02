package com.minecraftdetect

import android.graphics.RectF

data class Detection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val confidence: Float
) {
    val area: Float get() = w * h

    fun toRectF(imageWidth: Int, imageHeight: Int): RectF {
        val left = (cx - w / 2f) * imageWidth
        val top = (cy - h / 2f) * imageHeight
        val right = (cx + w / 2f) * imageWidth
        val bottom = (cy + h / 2f) * imageHeight
        return RectF(left, top, right, bottom)
    }
}

object NMS {

    fun filter(
        raw: Array<FloatArray>,       // [5][8400]: cx, cy, w, h, conf
        confThreshold: Float = 0.35f,
        iouThreshold: Float = 0.45f
    ): List<Detection> {
        val numDetections = raw[0].size  // 8400
        val candidates = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            val conf = raw[4][i]
            if (conf < confThreshold) continue
            candidates.add(
                Detection(
                    cx = raw[0][i],
                    cy = raw[1][i],
                    w = raw[2][i],
                    h = raw[3][i],
                    confidence = conf
                )
            )
        }

        // Sort descending by confidence
        candidates.sortByDescending { it.confidence }

        val result = mutableListOf<Detection>()
        for (candidate in candidates) {
            var keep = true
            for (selected in result) {
                if (iou(candidate, selected) >= iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                result.add(candidate)
            }
        }

        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val interLeft = maxOf(a.cx - a.w / 2f, b.cx - b.w / 2f)
        val interTop = maxOf(a.cy - a.h / 2f, b.cy - b.h / 2f)
        val interRight = minOf(a.cx + a.w / 2f, b.cx + b.w / 2f)
        val interBottom = minOf(a.cy + a.h / 2f, b.cy + b.h / 2f)

        if (interLeft >= interRight || interTop >= interBottom) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = a.area + b.area - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}
