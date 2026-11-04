package app.freeup.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProberTest {
    @Test fun parsesLocation() {
        assertEquals(37.4219f to -122.084f, Prober.parseIso6709("+37.4219-122.0840/"))
        assertEquals(-33.8688f to 151.2093f, Prober.parseIso6709("-33.8688+151.2093+010.000/"))
        assertNull(Prober.parseIso6709("+00.0000+000.0000/"))
    }

    @Test fun findsMarkerBytes() {
        val hay = "....keys....app.freeup.compressed....".toByteArray()
        assertEquals(12, Prober.indexOf(hay, hay.size, "app.freeup.compressed".toByteArray()))
        assertEquals(-1, Prober.indexOf(hay, 20, "app.freeup.compressed".toByteArray()))
    }

    @Test fun parsesDate() {
        assertEquals(1706696130000L, Prober.parseDate("20240131T101530.000Z"))
        assertNull(Prober.parseDate("19040101T000000.000Z"))
    }
}
