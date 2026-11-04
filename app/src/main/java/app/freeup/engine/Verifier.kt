package app.freeup.engine

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs

/** Checks a compressed file before it is allowed to replace the original. Returns why it failed, or null. */
object Verifier {
    private const val DURATION_TOLERANCE_MS = 500

    fun check(output: File, expectedDurationMs: Long, originalSize: Long): String? {
        if (!output.exists() || output.length() == 0L) return "No output was written"
        if (output.length() >= originalSize * (1 - BitratePlanner.MIN_SAVING / 2)) {
            return "Compressed copy wasn't smaller enough"
        }
        return try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(output.absolutePath)
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    return "Compressed copy has no video"
                }
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: return "Couldn't read the compressed copy"
                if (abs(duration - expectedDurationMs) > DURATION_TOLERANCE_MS) {
                    return "Compressed copy is a different length"
                }
                val frame = r.getFrameAtTime(duration * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return "Compressed copy doesn't play"
                frame.recycle()
                null
            }
        } catch (e: Exception) {
            "Couldn't read the compressed copy"
        }
    }
}
