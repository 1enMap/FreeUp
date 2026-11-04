package app.freeup.engine

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real hardware encoder on a camera-style clip and checks what galleries rely on.
 *
 * Needs the clip on the device first (with a rotation tag, ©xyz location, creation time
 * 2024-01-31T10:15:30Z), e.g.: adb push camera_style.mp4 /data/local/tmp/ or, for big files,
 *   adb shell "run-as app.freeup sh -c 'cat > files/camera_style.mp4'" < camera_style.mp4
 */
@RunWith(AndroidJUnit4::class)
class TranscodeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun keepsDateLocationAndOrientation(): Unit = runBlocking {
        val src = File(context.filesDir, "camera_style.mp4")
        if (!src.exists()) copyFromShell("/data/local/tmp/camera_style.mp4", src)
        assumeTrue("test clip not pushed", src.length() > 0)

        val probe = Prober.probe(context, Uri.fromFile(src))
        assertEquals(1706696130000L, probe.creationTimeMs)
        assertNotNull("location not read from source", probe.location)

        val out = File(context.filesDir, "camera_style_out.mp4")
        val started = System.nanoTime()
        Transcoder.transcode(
            context, Uri.fromFile(src), out, BitratePlanner.HEVC, 8_000_000,
            SourceMetadata(probe.creationTimeMs, probe.location),
        )
        val seconds = (System.nanoTime() - started) / 1e9
        assertNull(Verifier.check(out, 6_000, src.length()))
        assertTrue("FreeUp marker missing", Prober.probe(context, Uri.fromFile(out)).compressedByFreeUp)
        assertFalse("source wrongly marked", probe.compressedByFreeUp)

        MediaMetadataRetriever().use { r ->
            r.setDataSource(out.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
            val date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val srcShown = displayedSize(src)
            val outShown = if (rot % 180 == 0) w to h else h to w
            val info = "src shown ${srcShown.first}x${srcShown.second} -> out ${w}x$h rot=$rot date=$date loc=$loc " +
                "size=${out.length()}/${src.length()} took=${"%.1f".format(seconds)}s"
            Log.w(TAG, info)

            assertEquals("orientation changed: $info", srcShown, outShown)
            assertEquals(info, 1706696130000L, date?.let(Prober::parseDate))
            assertEquals(info, probe.location, loc?.let(Prober::parseIso6709))
        }
        out.delete()
        src.delete()
    }

    /** Width and height as a player shows it, after applying the rotation tag. */
    private fun displayedSize(file: File): Pair<Int, Int> = MediaMetadataRetriever().use { r ->
        r.setDataSource(file.absolutePath)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
        if (rot % 180 == 0) w to h else h to w
    }

    private fun copyFromShell(path: String, dest: File) {
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private companion object {
        const val TAG = "FreeUpDeviceTest"
    }
}
