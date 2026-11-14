package app.freeup.data

import android.net.Uri

/** One video from the MediaStore, with only what a quick query can tell us. */
data class Video(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val mime: String?,
    val dateTakenMs: Long?,
    val bucket: String?,
    /** Folder inside shared storage, e.g. "Download/" or "DCIM/Camera/". */
    val relativePath: String?,
    /** Capture frame rate the camera recorded (slow motion is often 120/240), when MediaStore knows it. */
    val captureFps: Float?,
)

/** What reading a video's container headers tells us. Costs a few milliseconds per file. */
data class Probe(
    val videoMime: String?,
    val fps: Float?,
    val isHdr: Boolean,
    val audioBitrate: Int?,
    val creationTimeMs: Long?,
    /** Latitude, longitude from the file's location tag. */
    val location: Pair<Float, Float>?,
    val readable: Boolean = true,
    /** The file carries FreeUp's own "already compressed" tag. */
    val compressedByFreeUp: Boolean = false,
    val audioMime: String? = null,
    val audioChannels: Int? = null,
    val audioTracks: Int = 0,
    val subtitleTracks: Int = 0,
    /** This phone lets apps decode the audio track (Dolby AC-3/E-AC-3 often isn't). */
    val audioDecodable: Boolean = true,
)
