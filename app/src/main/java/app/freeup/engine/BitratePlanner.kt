package app.freeup.engine

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How hard to compress. [maxShortSide] also lowers the resolution (720 = 720p): the biggest
 * saving, for videos you want to keep but don't need in full detail, such as a long concert.
 */
enum class Quality(
    val label: String,
    val factor: Double,
    val maxShortSide: Int?,
    val summary: String,
    val caveat: String,
) {
    BALANCED(
        "Balanced", 1.0, null,
        "Looks the same as the original",
        "On a phone or TV you shouldn't be able to tell the difference.",
    ),
    SMALLER(
        "Smaller", 0.67, null,
        "Saves more, tiny loss of detail",
        "Fine detail like leaves, hair and crowds can soften slightly. Hard to notice on a phone.",
    ),
    SMALLEST(
        "Smallest", 0.7, 720,
        "720p, for keeping memories small",
        "Videos are reduced to 720p. They look fine on a phone but soft on a TV or when you zoom in. " +
            "Dark, fast scenes (concerts, parties, fireworks) show blocky patches first.",
    ),
}

enum class SkipReason(
    val label: String,
    /** Whether "Compress anyway" is offered. */
    val overridable: Boolean,
    /** Why FreeUp left it alone, shown when you tap the video. */
    val why: String,
    /** What happens if you compress it anyway. */
    val caveat: String? = null,
) {
    ALREADY_SMALL(
        "Already small", true,
        "This video is already compressed efficiently, so FreeUp would save little at this quality.",
        "Squeezing it further makes it noticeably softer: expect blur in fine detail and blocky patches in dark or fast scenes.",
    ),
    ALREADY_DONE(
        "Already compressed", true,
        "FreeUp has already compressed this video once.",
        "Every pass loses a little more detail, and the losses add up. It's usually better to pick a lower quality the first time.",
    ),
    HDR(
        "HDR video", true,
        "This is an HDR video, with brighter highlights and richer colours than normal video.",
        "It will be converted to standard range (SDR). Highlights and colours will look flatter, and it can't be turned back into HDR.",
    ),
    HIGH_FPS(
        "High frame rate", true,
        "This video was recorded at a high frame rate, often for slow motion.",
        "All frames are kept, but some gallery apps may no longer play it back in slow motion.",
    ),
    NO_GAIN(
        "Nothing to gain", false,
        "This video is already so small that compressing it wouldn't free meaningful space.",
    ),
    UNSUPPORTED(
        "Format not supported", false,
        "FreeUp can only replace MP4 and MOV videos in place.",
    ),
    TOO_SHORT("Too short", false, "This video is too short to be worth compressing."),
    UNREADABLE("Can't read this file", false, "Android couldn't read this file. It may be damaged or still downloading."),
}

sealed interface Plan {
    data class Compress(
        val videoBitrate: Int,
        val outputMime: String,
        val estimatedBytes: Long,
        val outWidth: Int,
        val outHeight: Int,
        /** Short side to scale down to, or null to keep the resolution. */
        val maxShortSide: Int?,
        val toneMapHdr: Boolean,
        /** Set when you chose "Compress anyway"; its caveat applies. */
        val overridden: SkipReason? = null,
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

    /** "Compress anyway" never keeps more than this share of the source bitrate, so it always shrinks. */
    const val FORCED_MAX_SHARE = 0.7

    private val SUPPORTED_CONTAINERS = setOf("video/mp4", "video/quicktime")

    fun targetBitrate(width: Int, height: Int, fps: Float, quality: Quality, hevc: Boolean): Int {
        val pixelsPerSecond = width.toDouble() * height * fps.coerceIn(1f, 60f)
        val base = K * pixelsPerSecond.pow(0.75) * quality.factor * (if (hevc) 1.0 else AVC_PENALTY)
        return base.toInt().coerceAtLeast(MIN_BITRATE)
    }

    /** Output size after scaling the short side down to [maxShortSide], keeping the aspect ratio and even sizes. */
    fun outputSize(width: Int, height: Int, maxShortSide: Int?): Pair<Int, Int> {
        val short = min(width, height)
        if (maxShortSide == null || short <= maxShortSide) return width to height
        val scale = maxShortSide.toDouble() / short
        fun even(v: Double) = ((v / 2).roundToInt() * 2).coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /**
     * @param force true when you chose "Compress anyway": soft reasons (already small, already
     *   compressed, HDR, high frame rate) no longer block it, but the result must still shrink.
     */
    fun plan(input: PlanInput, quality: Quality, hevcAvailable: Boolean, force: Boolean = false): Plan {
        if (!input.readable) return Plan.Skip(SkipReason.UNREADABLE)
        if (input.containerMime !in SUPPORTED_CONTAINERS) return Plan.Skip(SkipReason.UNSUPPORTED)
        if (input.durationMs < 1_000 || input.width <= 0 || input.height <= 0) {
            return Plan.Skip(SkipReason.TOO_SHORT)
        }
        val fps = input.fps ?: DEFAULT_FPS
        val audio = input.audioBitrate ?: DEFAULT_AUDIO
        val totalBitrate = input.sizeBytes * 8.0 * 1000 / input.durationMs
        val sourceVideoBitrate = (totalBitrate - audio).coerceAtLeast(1.0)
        val (outW, outH) = outputSize(input.width, input.height, quality.maxShortSide)
        var target = targetBitrate(outW, outH, fps, quality, hevcAvailable)
        val minSaving = if (input.sourceCodec == HEVC) MIN_SAVING_HEVC_SOURCE else MIN_SAVING

        val softReason = when {
            input.alreadyDone -> SkipReason.ALREADY_DONE
            input.isHdr -> SkipReason.HDR
            fps > 61f || (input.captureFps ?: 0f) > 61f -> SkipReason.HIGH_FPS
            target > sourceVideoBitrate * (1 - minSaving) -> SkipReason.ALREADY_SMALL
            else -> null
        }
        if (softReason != null && !force) return Plan.Skip(softReason)
        if (force) target = min(target.toDouble(), sourceVideoBitrate * FORCED_MAX_SHARE).toInt()

        val estimated = ((target + audio) * input.durationMs / 1000.0 / 8 * CONTAINER_OVERHEAD).toLong()
        if (estimated > input.sizeBytes * (1 - MIN_SAVING) || target < MIN_BITRATE) {
            return Plan.Skip(if (force) SkipReason.NO_GAIN else SkipReason.ALREADY_SMALL)
        }
        return Plan.Compress(
            videoBitrate = target,
            outputMime = if (hevcAvailable) HEVC else AVC,
            estimatedBytes = estimated,
            outWidth = outW,
            outHeight = outH,
            maxShortSide = quality.maxShortSide?.takeIf { outW != input.width || outH != input.height },
            toneMapHdr = input.isHdr,
            overridden = softReason,
        )
    }
}
