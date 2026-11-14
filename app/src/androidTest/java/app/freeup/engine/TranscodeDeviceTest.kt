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
    }

    @Test
    fun smallestScalesTo720p(): Unit = runBlocking {
        val src = File(context.filesDir, "camera_style.mp4")
        if (!src.exists()) copyFromShell("/data/local/tmp/camera_style.mp4", src)
        assumeTrue("test clip not pushed", src.length() > 0)
        val out = File(context.filesDir, "camera_style_720.mp4")
        Transcoder.transcode(
            context, Uri.fromFile(src), out, BitratePlanner.HEVC, 2_300_000,
            SourceMetadata(null, null), maxShortSide = 720,
        )
        val (w, h) = displayedSize(out)
        val (sw, sh) = displayedSize(src)
        assertEquals("short side", 720, minOf(w, h))
        assertEquals("orientation kept", sw > sh, w > h)
        assertNull(Verifier.check(out, 6_000, src.length()))
        out.delete()
    }

    /** MKV with 5.1 AAC, a second audio track and subtitles → MP4 with stereo AAC. Push files/film.mkv first. */
    @Test
    fun convertsMkvWithSurroundToStereoMp4(): Unit = runBlocking {
        val src = File(context.filesDir, "film.mkv")
        assumeTrue("film.mkv not pushed", src.length() > 0)
        val probe = Prober.probe(context, Uri.fromFile(src))
        assertEquals("audio/mp4a-latm", probe.audioMime)
        assertEquals(6, probe.audioChannels)
        assertEquals(2, probe.audioTracks)
        assertEquals(1, probe.subtitleTracks)

        val out = File(context.filesDir, "film_out.mp4")
        Transcoder.transcode(
            context, Uri.fromFile(src), out, BitratePlanner.HEVC, 4_000_000,
            SourceMetadata(null, null), downmixFromChannels = 6,
        )
        assertNull(Verifier.check(out, 12_000, src.length()))
        val result = Prober.probe(context, Uri.fromFile(out))
        assertEquals(BitratePlanner.HEVC, result.videoMime)
        assertEquals("audio/mp4a-latm", result.audioMime)
        assertEquals(2, result.audioChannels)
        assertTrue(result.compressedByFreeUp)
        out.delete()
    }

    /** E-AC3 in MKV: probing must say whether this phone lets apps decode it. Push files/film_eac3.mkv. */
    @Test
    fun reportsWhetherDolbyAudioIsDecodable() {
        val src = File(context.filesDir, "film_eac3.mkv")
        assumeTrue("film_eac3.mkv not pushed", src.length() > 0)
        val probe = Prober.probe(context, Uri.fromFile(src))
        assertEquals("audio/eac3", probe.audioMime)
        assertEquals(Prober.hasDecoder("audio/eac3"), probe.audioDecodable)
        android.util.Log.w("FreeUpDeviceTest", "eac3 decodable=${probe.audioDecodable}")
    }

    /** Opening the real database runs the migrations on a phone that has job history. */
    @Test
    fun databaseOpensAfterUpgrade(): Unit = runBlocking {
        app.freeup.data.AppDb.get(context).jobs().latestBatch()
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
