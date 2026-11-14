// Media3 Transformer's encoder, muxer and metadata APIs are marked unstable.
@file:OptIn(UnstableApi::class)

package app.freeup.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.effect.Presentation
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
     * [maxShortSide] scales the video down (720 = 720p). [toneMapHdr] converts HDR to standard range.
     */
    suspend fun transcode(
        context: Context,
        input: Uri,
        output: File,
        videoMime: String,
        videoBitrate: Int,
        metadata: SourceMetadata,
        clipMs: Long? = null,
        maxShortSide: Int? = null,
        toneMapHdr: Boolean = false,
        downmixFromChannels: Int? = null,
        onProgress: (Float) -> Unit = {},
    ): ExportResult {
        output.parentFile?.mkdirs()
        output.delete()
        // Each encode gets its own thread. If a hardware encoder hangs, Transformer.cancel() can
        // block forever; on its own thread that can't freeze the app or stop the watchdog below.
        val thread = HandlerThread("FreeUp-encode").apply { start() }
        val handler = Handler(thread.looper)
        val result = CompletableDeferred<ExportResult>()
        var transformer: Transformer? = null
        val progress = AtomicInteger(-1)

        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
            .setEnableFallback(true)
            .build()

        val muxerFactory = InAppMp4Muxer.Factory { entries ->
            // Lets FreeUp recognise its own output later, even if its job history is gone.
            entries.removeAll { it is MdtaMetadataEntry && it.key == MARKER_KEY }
            entries.add(MdtaMetadataEntry(MARKER_KEY, MARKER_VALUE.toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING))
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
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progress.set(progressHolder.progress)
                    onProgress(progressHolder.progress / 100f)
                }
                handler.postDelayed(this, 500)
            }
        }

        handler.post {
            try {
                val t = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(videoMime)
                    .setEncoderFactory(encoderFactory)
                    .setMuxerFactory(muxerFactory)
                    .setLooper(thread.looper)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(poll)
                            onProgress(1f)
                            result.complete(exportResult)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(poll)
                            result.completeExceptionally(exportException)
                        }
                    })
                    .build()
                transformer = t

                val item = MediaItem.Builder().setUri(input).apply {
                    if (clipMs != null) {
                        setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build())
                    }
                }.build()
                val audioEffects = downmixFromChannels?.let { channels ->
                    runCatching {
                        listOf(
                            ChannelMixingAudioProcessor().apply {
                                putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
                            },
                        )
                    }.getOrNull()
                } ?: emptyList()
                val videoEffects = if (maxShortSide != null) listOf(Presentation.createForShortSide(maxShortSide)) else emptyList()
                val edited = EditedMediaItem.Builder(item).apply {
                    if (audioEffects.isNotEmpty() || videoEffects.isNotEmpty()) setEffects(Effects(audioEffects, videoEffects))
                }.build()
                val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                    .setHdrMode(if (toneMapHdr) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
                    .build()
                t.start(composition, output.absolutePath)
                handler.post(poll)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }

        try {
            return coroutineScope {
                // Watchdog: runs in the caller's coroutine, so a stuck encoder thread can't block it.
                val watchdog = launch {
                    var lastProgress = -2
                    var lastLength = -1L
                    var lastChange = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(1_000)
                        val p = progress.get()
                        val length = output.length()
                        val now = SystemClock.elapsedRealtime()
                        if (p != lastProgress || length != lastLength) {
                            lastProgress = p
                            lastLength = length
                            lastChange = now
                        } else if (now - lastChange > STALL_MS) {
                            result.completeExceptionally(EncoderStalledException())
                        }
                    }
                }
                try {
                    result.await()
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            val failed = !result.isCompleted || result.isCancelled || result.getCompletionExceptionOrNull() != null
            handler.post {
                handler.removeCallbacks(poll)
                if (failed) runCatching { transformer?.cancel() }
                thread.quitSafely()
            }
            // Let the hardware encoder finish shutting down before the next video starts: some
            // MediaTek encoders stall when a new session opens while the last is still closing.
            // A hung encoder never finishes, so don't wait forever on a failed run.
            withContext(NonCancellable + Dispatchers.IO) {
                thread.join(if (failed) 2_000 else 5_000)
                delay(500)
            }
        }
    }
}
