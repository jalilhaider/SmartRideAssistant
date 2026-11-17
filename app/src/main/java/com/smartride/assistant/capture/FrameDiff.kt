package com.smartride.assistant.capture

import kotlin.math.abs

/**
 * Cheap change detector. Frames are first shrunk to a tiny grid (done by the caller), then compared by
 * mean absolute luminance difference. OCR only runs when the screen has actually changed.
 */
class FrameDiff(private val threshold: Double = 2.0) {
    private var previous: IntArray? = null

    /** [argbPixels] is a downscaled frame. Returns true when it differs enough from the last accepted frame. */
    fun hasChanged(argbPixels: IntArray): Boolean {
        val lum = IntArray(argbPixels.size) { luminance(argbPixels[it]) }
        val prev = previous
        if (prev == null || prev.size != lum.size) { previous = lum; return true }
        var sum = 0L
        for (i in lum.indices) sum += abs(lum[i] - prev[i])
        val mean = sum.toDouble() / lum.size
        return if (mean > threshold) { previous = lum; true } else false
    }

    fun reset() { previous = null }

    companion object {
        fun luminance(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }
    }
}
