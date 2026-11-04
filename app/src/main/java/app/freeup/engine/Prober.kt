package app.freeup.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.freeup.data.Probe
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads codec, frame rate, HDR, audio bitrate, creation time and location from a video's headers. */
object Prober {

    fun probe(context: Context, uri: Uri): Probe {
        var videoMime: String? = null
        var fps: Float? = null
        var hdr = false
        var audioBitrate: Int? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    fps = f.number(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                    val transfer = f.number(MediaFormat.KEY_COLOR_TRANSFER)?.toInt()
                    hdr = mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION ||
                        transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                        transfer == MediaFormat.COLOR_TRANSFER_HLG
                } else if (mime.startsWith("audio/") && audioBitrate == null) {
                    audioBitrate = f.number(MediaFormat.KEY_BIT_RATE)?.toInt()
                }
            }
        } catch (_: Exception) {
            return Probe(null, null, false, null, null, null, readable = false)
        } finally {
            extractor.release()
        }

        var created: Long? = null
        var location: Pair<Float, Float>? = null
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                created = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseDate)
                location = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
            }
        }
        val marked = runCatching { hasMarker(context, uri) }.getOrDefault(false)
        return Probe(videoMime, fps, hdr, audioBitrate, created, location, compressedByFreeUp = marked)
    }

    private const val SCAN_BYTES = 2L * 1024 * 1024

    /** Looks for FreeUp's tag in the MP4 header, which sits within the first or last 2 MB. */
    private fun hasMarker(context: Context, uri: Uri): Boolean {
        val marker = Transcoder.MARKER_KEY.toByteArray()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
        return pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val ranges = if (size <= SCAN_BYTES * 2) listOf(0L to size) else listOf(0L to SCAN_BYTES, size - SCAN_BYTES to SCAN_BYTES)
                ranges.any { (start, length) ->
                    val buf = ByteBuffer.allocate(length.toInt())
                    ch.position(start)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    indexOf(buf.array(), buf.position(), marker) >= 0
                }
            }
        }
    }

    internal fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        outer@ for (i in 0..length - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun MediaFormat.number(key: String): Number? {
        if (!containsKey(key)) return null
        return runCatching { getInteger(key) as Number }.getOrNull()
            ?: runCatching { getFloat(key) as Number }.getOrNull()
    }

    /** MediaMetadataRetriever dates look like `20240131T101530.000Z`. 1904/1970 placeholders mean "unknown". */
    internal fun parseDate(value: String): Long? {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val ms = runCatching { fmt.parse(value.take(15))?.time }.getOrNull() ?: return null
        return ms.takeIf { it > 86_400_000L * 365 }
    }

    /** ISO 6709, for example `+37.4219-122.0840/` or `+37.4219-122.0840+010.000/`. */
    internal fun parseIso6709(value: String): Pair<Float, Float>? {
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
        val lat = m.groupValues[1].toFloatOrNull() ?: return null
        val lon = m.groupValues[2].toFloatOrNull() ?: return null
        if (lat == 0f && lon == 0f) return null
        return lat to lon
    }
}
