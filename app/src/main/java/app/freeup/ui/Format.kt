package app.freeup.ui

import java.util.Locale
import kotlin.math.roundToLong

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1e9 -> String.format(Locale.US, "%.1f GB", b / 1e9)
        b >= 1e6 -> String.format(Locale.US, "%.0f MB", b / 1e6)
        b >= 1e3 -> String.format(Locale.US, "%.0f KB", b / 1e3)
        else -> "${b.toLong()} B"
    }
}

/** "under a minute", "about 4 min", "about 1 h 20 min". */
fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3600 -> "about ${(seconds / 60.0).roundToLong()} min"
    else -> {
        val h = seconds / 3600
        val m = ((seconds % 3600) / 60.0).roundToLong()
        if (m == 0L) "about $h h" else "about $h h $m min"
    }
}

/** Video length like "0:42" or "1:05:09". */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
