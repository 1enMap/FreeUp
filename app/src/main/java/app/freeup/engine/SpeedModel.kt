package app.freeup.engine

import android.content.Context
import androidx.core.content.edit

/**
 * How many pixels per second this phone's encoder gets through, learned from real runs.
 * ETA for a video = width × height × fps × seconds ÷ this rate.
 */
class SpeedModel(context: Context) {
    private val prefs = context.getSharedPreferences("speed", Context.MODE_PRIVATE)

    /** Measured rate, or a cautious default (about 1080p at 90 fps) before the first measurement. */
    val pixelsPerSecond: Double
        get() = prefs.getFloat(KEY, 0f).toDouble().takeIf { it > 0 } ?: DEFAULT

    val isMeasured: Boolean get() = prefs.contains(KEY)

    fun record(measuredPixelsPerSecond: Double) {
        if (measuredPixelsPerSecond <= 0 || measuredPixelsPerSecond.isNaN()) return
        val old = prefs.getFloat(KEY, 0f).toDouble()
        // Weighted toward the newest run: thermal state and encoder choice vary.
        val next = if (old > 0) old * 0.4 + measuredPixelsPerSecond * 0.6 else measuredPixelsPerSecond
        prefs.edit { putFloat(KEY, next.toFloat()) }
    }

    fun secondsFor(width: Int, height: Int, fps: Float, durationMs: Long): Double =
        workOf(width, height, fps, durationMs) / pixelsPerSecond

    companion object {
        private const val KEY = "pps"
        private const val DEFAULT = 1920.0 * 1080 * 90

        fun workOf(width: Int, height: Int, fps: Float, durationMs: Long): Double =
            width.toDouble() * height * fps.coerceIn(1f, 60f) * durationMs / 1000.0
    }
}
