@file:OptIn(UnstableApi::class)

package app.freeup.engine

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
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
        var audioMime: String? = null
        var audioChannels: Int? = null
        var audioTracks = 0
        var subtitleTracks = 0
        val formats = trackFormats(context, uri) ?: return Probe(null, null, false, null, null, null, readable = false)
        run {
            for (f in formats) {
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    fps = f.number(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                    val transfer = f.number(MediaFormat.KEY_COLOR_TRANSFER)?.toInt()
                    hdr = mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION ||
                        transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                        transfer == MediaFormat.COLOR_TRANSFER_HLG
                } else if (mime.startsWith("audio/")) {
                    audioTracks++
                    if (audioMime == null) {
                        audioMime = mime
                        audioBitrate = f.number(MediaFormat.KEY_BIT_RATE)?.toInt()
                        audioChannels = f.number(MediaFormat.KEY_CHANNEL_COUNT)?.toInt()
                    }
                } else if (mime.startsWith("text/") || SUBTITLE_HINTS.any { mime.contains(it) }) {
                    subtitleTracks++
                }
            }
        }

        var created: Long? = null
        var location: Pair<Float, Float>? = null
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, originalUri(context, uri))
                created = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::parseDate)
                location = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
            }
        }
        val marked = runCatching { hasMarker(context, uri) }.getOrDefault(false)
        return Probe(
            videoMime, fps, hdr, audioBitrate, created, location,
            compressedByFreeUp = marked,
            audioMime = audioMime,
            audioChannels = audioChannels,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            audioDecodable = audioMime == null || hasDecoder(audioMime!!),
        )
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

    /**
     * Android hides the GPS tag in a gallery video unless the app holds ACCESS_MEDIA_LOCATION and
     * asks for the original bytes. Without the permission, opening the original throws, so the
     * plain URI is used and the location simply reads as missing.
     */
    private fun originalUri(context: Context, uri: Uri): Uri {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return uri
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) MediaStore.setRequireOriginal(uri) else uri
    }

    /**
     * Track formats, read with Media3's extractors (the same ones the encoder uses, so MKV tracks
     * like E-AC3 and subtitles are seen). Falls back to Android's own reader.
     */
    private fun trackFormats(context: Context, uri: Uri): List<MediaFormat>? {
        runCatching {
            val e = MediaExtractorCompat(context)
            try {
                e.setDataSource(context, uri, null)
                return (0 until e.trackCount).map(e::getTrackFormat)
            } finally {
                e.release()
            }
        }
        return runCatching {
            val e = MediaExtractor()
            try {
                e.setDataSource(context, uri, null)
                (0 until e.trackCount).map(e::getTrackFormat)
            } finally {
                e.release()
            }
        }.getOrNull()
    }

    private val decoderCache = mutableMapOf<String, Boolean>()

    /** Whether apps may decode [mime]: some phones list a Dolby decoder but keep it for their own apps. */
    @Synchronized
    fun hasDecoder(mime: String): Boolean = decoderCache.getOrPut(mime) {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    /** Subtitle track types as Android and Media3 name them (Media3 reports parsed cues). */
    private val SUBTITLE_HINTS = listOf("cues", "subrip", "x-ssa", "ttml", "vtt", "pgs", "vobsub", "dvbsubs", "cea-608", "cea-708", "tx3g")

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
