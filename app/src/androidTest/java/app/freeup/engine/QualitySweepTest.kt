package app.freeup.engine

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a pass/fail test: encodes files/sweep_src.mp4 in the app's own folder with the phone's encoder at several
 * bitrates into files/sweep/, so the outputs can be pulled and scored (VMAF) on a computer.
 * Bitrates in kbps via -e bitrates 4000,6000,8000. Put the source there with:
 *   adb shell "run-as app.freeup sh -c 'cat > files/sweep_src.mp4'" < video.mp4
 */
@RunWith(AndroidJUnit4::class)
class QualitySweepTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun sweep(): Unit = runBlocking {
        val src = File(context.filesDir, "sweep_src.mp4")
        assumeTrue("sweep source not pushed", src.length() > 0)

        val bitrates = (InstrumentationRegistry.getArguments().getString("bitrates") ?: "4000,6000,8000")
            .split(",").map { it.trim().toInt() * 1000 }
        val outDir = File(context.filesDir, "sweep").apply { mkdirs() }
        for (bitrate in bitrates) {
            val out = File(outDir, "hevc_${bitrate / 1000}k.mp4")
            val started = System.nanoTime()
            Transcoder.transcode(context, Uri.fromFile(src), out, BitratePlanner.HEVC, bitrate, SourceMetadata(null, null))
            File(outDir, "hevc_${bitrate / 1000}k.time").writeText("%.2f".format((System.nanoTime() - started) / 1e9))
        }
    }
}
