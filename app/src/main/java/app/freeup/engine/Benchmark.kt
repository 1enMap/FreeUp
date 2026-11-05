package app.freeup.engine

import android.content.Context
import android.os.SystemClock
import app.freeup.data.Video
import java.io.File

/** Encodes the first 3 seconds of a video to learn how fast this phone is. */
object Benchmark {
    private const val CLIP_MS = 3_000L

    suspend fun run(context: Context, video: Video, fps: Float, plan: Plan.Compress): Boolean {
        val out = File(context.cacheDir, "benchmark.mp4")
        val clip = minOf(CLIP_MS, video.durationMs)
        val started = SystemClock.elapsedRealtime()
        val ok = runCatching {
            Transcoder.transcode(
                context, video.uri, out, plan.outputMime, plan.videoBitrate,
                SourceMetadata(null, null), clipMs = clip,
                maxShortSide = plan.maxShortSide, toneMapHdr = plan.toneMapHdr,
            )
        }.isSuccess
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        out.delete()
        if (ok && seconds > 0) {
            SpeedModel(context).record(SpeedModel.workOf(video.width, video.height, fps, clip) / seconds)
        }
        return ok
    }
}
