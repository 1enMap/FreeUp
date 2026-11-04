package app.freeup.engine

import kotlin.math.pow

enum class Quality(val label: String, val factor: Double) {
    BALANCED("Balanced", 1.0),
    SMALLER("Smaller", 0.67),
}

enum class SkipReason(val label: String) {
    ALREADY_SMALL("Already small"),
    ALREADY_DONE("Already compressed"),
    HDR("HDR video"),
    HIGH_FPS("Slow motion / high frame rate"),
    UNSUPPORTED("Format not supported"),
    TOO_SHORT("Too short"),
    UNREADABLE("Can't read this file"),
}

sealed interface Plan {
    data class Compress(
        val videoBitrate: Int,
        val outputMime: String,
        val estimatedBytes: Long,
    ) : Plan

    data class Skip(val reason: SkipReason) : Plan
}

data class PlanInput(
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val containerMime: String?,
    val fps: Float?,
    val captureFps: Float?,
    val audioBitrate: Int?,
    val isHdr: Boolean,
    /** Codec of the source video track, e.g. video/hevc; null until the headers are read. */
    val sourceCodec: String? = null,
    val readable: Boolean = true,
    val alreadyDone: Boolean = false,
)

/**
 * Picks a target video bitrate and predicts the output size. Pure Kotlin so it can be unit tested.
 *
 * The target grows with pixels per second to the power 0.75: bigger frames compress better per
 * pixel. Calibrated so Balanced HEVC gives ~6 Mbps at 1080p30 (~17 Mbps at 4K30) and Smaller ~4 Mbps.
 * Measured on a vivo V2319 (MediaTek HEVC encoder) with a 17.7 Mbps 1080p30 camera clip: 6 Mbps
 * scored VMAF 96.0 (63% smaller), 4 Mbps scored 94.1 (70% smaller); 95+ is generally indistinguishable.
 */
object BitratePlanner {
    const val HEVC = "video/hevc"
    const val AVC = "video/avc"

    private const val K = 8.55
    private const val AVC_PENALTY = 1.5
    private const val MIN_BITRATE = 600_000
    private const val DEFAULT_FPS = 30f
    private const val DEFAULT_AUDIO = 128_000
    private const val CONTAINER_OVERHEAD = 1.02

    /** Below this saving it isn't worth another lossy generation. */
    const val MIN_SAVING = 0.15

    /**
     * HEVC sources are already efficient (and may be FreeUp's own output from before its history
     * was lost), so re-encoding them must save much more to be worth the quality cost.
     */
    const val MIN_SAVING_HEVC_SOURCE = 0.40

    private val SUPPORTED_CONTAINERS = setOf("video/mp4", "video/quicktime")

    fun targetBitrate(width: Int, height: Int, fps: Float, quality: Quality, hevc: Boolean): Int {
        val pixelsPerSecond = width.toDouble() * height * fps.coerceIn(1f, 60f)
        val base = K * pixelsPerSecond.pow(0.75) * quality.factor * (if (hevc) 1.0 else AVC_PENALTY)
        return base.toInt().coerceAtLeast(MIN_BITRATE)
    }

    fun plan(input: PlanInput, quality: Quality, hevcAvailable: Boolean): Plan {
        if (input.alreadyDone) return Plan.Skip(SkipReason.ALREADY_DONE)
        if (!input.readable) return Plan.Skip(SkipReason.UNREADABLE)
        if (input.containerMime !in SUPPORTED_CONTAINERS) return Plan.Skip(SkipReason.UNSUPPORTED)
        if (input.isHdr) return Plan.Skip(SkipReason.HDR)
        val fps = input.fps ?: DEFAULT_FPS
        if (fps > 61f || (input.captureFps ?: 0f) > 61f) return Plan.Skip(SkipReason.HIGH_FPS)
        if (input.durationMs < 1_000 || input.width <= 0 || input.height <= 0) {
            return Plan.Skip(SkipReason.TOO_SHORT)
        }

        val audio = input.audioBitrate ?: DEFAULT_AUDIO
        val totalBitrate = input.sizeBytes * 8.0 * 1000 / input.durationMs
        val sourceVideoBitrate = (totalBitrate - audio).coerceAtLeast(1.0)
        val target = targetBitrate(input.width, input.height, fps, quality, hevcAvailable)
        val minSaving = if (input.sourceCodec == HEVC) MIN_SAVING_HEVC_SOURCE else MIN_SAVING
        if (target > sourceVideoBitrate * (1 - minSaving)) return Plan.Skip(SkipReason.ALREADY_SMALL)

        val estimated = ((target + audio) * input.durationMs / 1000.0 / 8 * CONTAINER_OVERHEAD).toLong()
        if (estimated > input.sizeBytes * (1 - MIN_SAVING)) return Plan.Skip(SkipReason.ALREADY_SMALL)
        return Plan.Compress(target, if (hevcAvailable) HEVC else AVC, estimated)
    }
}
