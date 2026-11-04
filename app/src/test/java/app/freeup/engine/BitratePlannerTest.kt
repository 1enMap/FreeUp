package app.freeup.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitratePlannerTest {
    private fun input(
        sizeMb: Double,
        seconds: Int,
        w: Int = 1920,
        h: Int = 1080,
        fps: Float? = 30f,
        mime: String? = "video/mp4",
        hdr: Boolean = false,
        captureFps: Float? = null,
        codec: String? = "video/avc",
    ) = PlanInput(
        sizeBytes = (sizeMb * 1_000_000).toLong(),
        durationMs = seconds * 1000L,
        width = w,
        height = h,
        containerMime = mime,
        fps = fps,
        captureFps = captureFps,
        audioBitrate = 128_000,
        isHdr = hdr,
        sourceCodec = codec,
    )

    @Test fun targetMatchesCalibration() {
        val p1080 = BitratePlanner.targetBitrate(1920, 1080, 30f, Quality.BALANCED, hevc = true)
        val p4k = BitratePlanner.targetBitrate(3840, 2160, 30f, Quality.BALANCED, hevc = true)
        assertTrue("1080p30 was $p1080", p1080 in 5_500_000..6_500_000)
        assertTrue("4K30 was $p4k", p4k in 15_500_000..18_500_000)
    }

    @Test fun typical1080pPhoneVideoIsCompressed() {
        // 1 minute of 1080p30 at ~17 Mbps, a common phone default: about 128 MB.
        val plan = BitratePlanner.plan(input(128.0, 60), Quality.BALANCED, hevcAvailable = true)
        plan as Plan.Compress
        assertEquals(BitratePlanner.HEVC, plan.outputMime)
        val saving = 1 - plan.estimatedBytes / 128_000_000.0
        assertTrue("saving was $saving", saving in 0.55..0.70)
    }

    @Test fun smallerSavesMoreThanBalanced() {
        val balanced = BitratePlanner.plan(input(128.0, 60), Quality.BALANCED, true) as Plan.Compress
        val smaller = BitratePlanner.plan(input(128.0, 60), Quality.SMALLER, true) as Plan.Compress
        assertTrue(smaller.estimatedBytes < balanced.estimatedBytes)
    }

    @Test fun smallerIsAboutFourMbpsAt1080p() {
        val t = BitratePlanner.targetBitrate(1920, 1080, 30f, Quality.SMALLER, hevc = true)
        assertTrue("was $t", t in 3_700_000..4_300_000)
    }

    @Test fun alreadyLowBitrateIsSkipped() {
        // WhatsApp-style 1080p at ~4 Mbps.
        val plan = BitratePlanner.plan(input(30.0, 60), Quality.BALANCED, true)
        assertEquals(Plan.Skip(SkipReason.ALREADY_SMALL), plan)
    }

    @Test fun hevcSourceNeedsBiggerSaving() {
        // FreeUp's own earlier output: 1080p30 HEVC at ~8.2 Mbps. 6 Mbps would save only ~25%.
        val earlier = input(8.33 * 60 / 8, 60, codec = BitratePlanner.HEVC)
        assertEquals(Plan.Skip(SkipReason.ALREADY_SMALL), BitratePlanner.plan(earlier, Quality.BALANCED, true))
        // The same size as H.264 is still worth compressing.
        assertTrue(BitratePlanner.plan(earlier.copy(sourceCodec = BitratePlanner.AVC), Quality.BALANCED, true) is Plan.Compress)
        // A high-bitrate HEVC camera recording still gets compressed.
        val camera = input(20.0 * 60 / 8, 60, codec = BitratePlanner.HEVC)
        assertTrue(BitratePlanner.plan(camera, Quality.BALANCED, true) is Plan.Compress)
    }

    @Test fun hdrSlowMotionAndOtherContainersAreSkipped() {
        assertEquals(Plan.Skip(SkipReason.HDR), BitratePlanner.plan(input(400.0, 60, hdr = true), Quality.BALANCED, true))
        assertEquals(Plan.Skip(SkipReason.HIGH_FPS), BitratePlanner.plan(input(400.0, 60, fps = 120f), Quality.BALANCED, true))
        assertEquals(Plan.Skip(SkipReason.HIGH_FPS), BitratePlanner.plan(input(400.0, 60, captureFps = 240f), Quality.BALANCED, true))
        assertEquals(Plan.Skip(SkipReason.UNSUPPORTED), BitratePlanner.plan(input(400.0, 60, mime = "video/webm"), Quality.BALANCED, true))
    }

    @Test fun withoutHevcFallsBackToAvcAtHigherBitrate() {
        val plan = BitratePlanner.plan(input(400.0, 60, 3840, 2160), Quality.BALANCED, hevcAvailable = false) as Plan.Compress
        assertEquals(BitratePlanner.AVC, plan.outputMime)
        assertTrue(plan.videoBitrate > BitratePlanner.targetBitrate(3840, 2160, 30f, Quality.BALANCED, true))
    }
}
