// Media3 Transformer's encoder, muxer and metadata APIs are marked unstable.
@file:OptIn(UnstableApi::class)

package app.freeup.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Metadata written into the new MP4 so galleries keep the video at the same date and place. */
data class SourceMetadata(
    val creationTimeMs: Long?,
    val location: Pair<Float, Float>?,
)

/** The encoder made no progress for [Transcoder.STALL_MS]; a fresh attempt usually works. */
class EncoderStalledException : Exception("The video encoder stopped responding")

object Transcoder {
    const val STALL_MS = 60_000L
    const val MARKER_KEY = "app.freeup.compressed"
    const val MARKER_VALUE = "1"

    val hevcEncoderAvailable: Boolean by lazy {
        runCatching { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty() }.getOrDefault(false)
    }

    /**
     * Re-encodes [input] to [output] with the phone's hardware encoder. Audio is copied as is when
     * possible. [clipMs] limits the encode to the first part of the video (used to measure speed).
     */
    suspend fun transcode(
        context: Context,
        input: Uri,
        output: File,
        videoMime: String,
        videoBitrate: Int,
        metadata: SourceMetadata,
        clipMs: Long? = null,
        onProgress: (Float) -> Unit = {},
    ): ExportResult {
        output.parentFile?.mkdirs()
        output.delete()
        val main = Handler(Looper.getMainLooper())
        return suspendCancellableCoroutine { cont ->
            val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(videoBitrate).build(),
                )
                .setEnableFallback(true)
                .build()

            val muxerFactory = InAppMp4Muxer.Factory { entries ->
                // Lets FreeUp recognise its own output later, even if its job history is gone.
                entries.removeAll { it is MdtaMetadataEntry && it.key == MARKER_KEY }
                entries.add(
                    MdtaMetadataEntry(MARKER_KEY, MARKER_VALUE.toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING),
                )
                if (metadata.creationTimeMs != null) {
                    entries.removeAll { it is Mp4TimestampData }
                    val t = Mp4TimestampData.unixTimeToMp4TimeSeconds(metadata.creationTimeMs)
                    entries.add(Mp4TimestampData(t, t))
                }
                if (metadata.location != null) {
                    entries.removeAll { it is Mp4LocationData }
                    entries.add(Mp4LocationData(metadata.location.first, metadata.location.second))
                }
            }

            val progressHolder = ProgressHolder()
            lateinit var transformer: Transformer
            // Some hardware encoders occasionally stop taking frames and never recover. Watch the
            // progress and the output file; if neither moves for STALL_MS, give up on this attempt.
            var lastProgress = -1
            var lastLength = -1L
            var lastChange = SystemClock.elapsedRealtime()
            val poll = object : Runnable {
                override fun run() {
                    var p = lastProgress
                    if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        p = progressHolder.progress
                        onProgress(p / 100f)
                    }
                    val length = output.length()
                    val now = SystemClock.elapsedRealtime()
                    if (p != lastProgress || length != lastLength) {
                        lastProgress = p
                        lastLength = length
                        lastChange = now
                    } else if (now - lastChange > STALL_MS) {
                        runCatching { transformer.cancel() }
                        output.delete()
                        if (cont.isActive) cont.resumeWithException(EncoderStalledException())
                        return
                    }
                    main.postDelayed(this, 500)
                }
            }

            main.post {
                transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(videoMime)
                    .setEncoderFactory(encoderFactory)
                    .setMuxerFactory(muxerFactory)
                    .setLooper(Looper.getMainLooper())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacks(poll)
                            onProgress(1f)
                            if (cont.isActive) cont.resume(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            main.removeCallbacks(poll)
                            output.delete()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                val item = MediaItem.Builder().setUri(input).apply {
                    if (clipMs != null) {
                        setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build(),
                        )
                    }
                }.build()
                transformer.start(EditedMediaItem.Builder(item).build(), output.absolutePath)
                main.post(poll)
            }

            cont.invokeOnCancellation {
                main.post {
                    main.removeCallbacks(poll)
                    runCatching { transformer.cancel() }
                    output.delete()
                }
            }
        }
    }
}
