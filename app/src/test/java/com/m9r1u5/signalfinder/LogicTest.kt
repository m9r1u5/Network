package com.m9r1u5.signalfinder

import com.m9r1u5.signalfinder.signal.Level
import com.m9r1u5.signalfinder.signal.SignalMath
import com.m9r1u5.signalfinder.signal.SignalReading
import com.m9r1u5.signalfinder.signal.SpotSummary
import com.m9r1u5.signalfinder.signal.Tech
import com.m9r1u5.signalfinder.util.Csv
import com.m9r1u5.signalfinder.util.TileMath
import com.m9r1u5.signalfinder.util.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalMathTest {
    @Test fun lteBars() {
        assertEquals(5, SignalMath.bars(Tech.LTE, -80))
        assertEquals(5, SignalMath.bars(Tech.LTE, -85))
        assertEquals(4, SignalMath.bars(Tech.LTE, -90))
        assertEquals(3, SignalMath.bars(Tech.LTE, -100))
        assertEquals(2, SignalMath.bars(Tech.LTE, -110))
        assertEquals(1, SignalMath.bars(Tech.LTE, -118))
        assertEquals(0, SignalMath.bars(Tech.LTE, -125))
        assertEquals(0, SignalMath.bars(Tech.LTE, null))
        assertEquals(0, SignalMath.bars(Tech.NONE, -80))
    }

    @Test fun gsmAnd3gBars() {
        assertEquals(5, SignalMath.bars(Tech.GSM, -65))
        assertEquals(3, SignalMath.bars(Tech.GSM, -88))
        assertEquals(4, SignalMath.bars(Tech.WCDMA, -80))
        assertEquals(1, SignalMath.bars(Tech.WCDMA, -108))
    }

    @Test fun scoreIsClampedAndMonotonic() {
        assertEquals(0, SignalMath.score(Tech.LTE, -140))
        assertEquals(100, SignalMath.score(Tech.LTE, -60))
        assertTrue(SignalMath.score(Tech.LTE, -90) > SignalMath.score(Tech.LTE, -100))
        assertEquals(0, SignalMath.score(Tech.LTE, null))
    }

    @Test fun levels() {
        assertEquals(Level.GOOD, SignalMath.level(5))
        assertEquals(Level.GOOD, SignalMath.level(4))
        assertEquals(Level.FAIR, SignalMath.level(3))
        assertEquals(Level.POOR, SignalMath.level(2))
        assertEquals(Level.BAD, SignalMath.level(0))
    }

    @Test fun geigerGetsFasterWithStrongerSignal() {
        assertTrue(SignalMath.geigerIntervalMs(90) < SignalMath.geigerIntervalMs(30))
        assertEquals(2000L, SignalMath.geigerIntervalMs(0))
        assertEquals(150L, SignalMath.geigerIntervalMs(100))
        assertEquals(3000L, SignalMath.geigerIntervalMs(null))
    }

    @Test fun newBestNeedsMargin() {
        assertTrue(SignalMath.isNewBest(50, null))
        assertFalse(SignalMath.isNewBest(52, 50))
        assertTrue(SignalMath.isNewBest(53, 50))
    }

    @Test fun wifiBars() {
        assertEquals(5, SignalMath.wifiBars(-50))
        assertEquals(2, SignalMath.wifiBars(-78))
        assertEquals(0, SignalMath.wifiBars(null))
    }
}

class SpotSummaryTest {
    private fun r(dbm: Int?, tech: Tech = Tech.LTE) = SignalReading(
        timestamp = 0, tech = if (dbm == null) Tech.NONE else tech,
        networkLabel = if (dbm == null) "No service" else "4G LTE", dbm = dbm,
        bars = SignalMath.bars(tech, dbm), score = SignalMath.score(tech, dbm), rsrp = dbm,
    )

    @Test fun averagesReadings() {
        val s = SpotSummary.from(listOf(r(-90), r(-92), r(-94)))
        assertEquals(-92, s.avgDbm)
        assertEquals(-94, s.minDbm)
        assertEquals(-90, s.maxDbm)
        assertEquals(Tech.LTE, s.tech)
        assertEquals("4G LTE", s.networkLabel)
        assertEquals(3, s.samples)
        assertEquals(4, s.bars)
    }

    @Test fun dropoutsLowerTheScore() {
        val steady = SpotSummary.from(listOf(r(-95), r(-95), r(-95), r(-95)))
        val patchy = SpotSummary.from(listOf(r(-95), r(null), r(-95), r(null)))
        assertTrue(patchy.score < steady.score)
        assertEquals(-95, patchy.avgDbm)
    }

    @Test fun empty() {
        val s = SpotSummary.from(emptyList())
        assertNull(s.avgDbm)
        assertEquals(0, s.score)
    }

    @Test fun noServiceOnly() {
        val s = SpotSummary.from(listOf(r(null), r(null)))
        assertNull(s.avgDbm)
        assertEquals(0, s.score)
        assertEquals(Tech.NONE, s.tech)
    }
}

class UtilTest {
    @Test fun csvEscaping() {
        assertEquals("a,b,", Csv.row(listOf("a", "b", null)))
        assertEquals("\"Office, east\",\"say \"\"hi\"\"\"", Csv.row(listOf("Office, east", "say \"hi\"")))
        assertEquals("-85,1.5", Csv.row(listOf(-85, 1.5)))
    }

    @Test fun versionsFromTags() {
        assertEquals(17, Versions.codeFromTag("v1.0.17"))
        assertEquals(3, Versions.codeFromTag("1.0.3"))
        assertNull(Versions.codeFromTag(""))
        assertNull(Versions.codeFromTag("latest"))
        assertTrue(Versions.isNewer("v1.0.5", 4))
        assertFalse(Versions.isNewer("v1.0.5", 5))
        assertFalse(Versions.isNewer(null, 1))
    }

    @Test fun tileMathJohannesburg() {
        // Johannesburg CBD at zoom 15
        assertEquals(18936, TileMath.x(28.0473, 15))
        assertEquals(18856, TileMath.y(-26.2041, 15))
        assertEquals(0, TileMath.x(-180.0, 3))
        assertEquals(7, TileMath.x(179.99, 3))
    }

    @Test fun tilePlanRespectsLimit() {
        val plan = TileMath.plan(-26.20, -26.21, 28.05, 28.04, 14, 19, 300)
        assertTrue(plan.size <= 300)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.z in 14..19 })
        val small = TileMath.tiles(-26.20, -26.21, 28.05, 28.04, 14)
        assertTrue(small.size in 1..4)
    }
}
