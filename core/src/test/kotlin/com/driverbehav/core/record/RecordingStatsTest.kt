package com.driverbehav.core.record

import com.driverbehav.core.model.RawSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStatsTest {

    /** 1 s of accel at 100 Hz interleaved with 1 Hz GPS — the M2 target shape. */
    private fun syntheticSession(): List<RawSample> {
        val out = ArrayList<RawSample>()
        val accelPeriodNs = 10_000_000L // 100 Hz
        for (i in 0..100) {
            val t = i * accelPeriodNs
            out += RawSample.Accel(t, 0f, 0f, 9.81f)
            if (i % 100 == 0) out += RawSample.Gps(t, 0.0, 0.0, 1.0f, 5f, Float.NaN)
        }
        return out.sortedBy { it.tMonoNanos }
    }

    @Test
    fun computesAccelRateAtHundredHz() {
        val stats = RecordingStats.compute(syntheticSession().asSequence())
        assertEquals(101, stats.accel.count)
        assertEquals(100.0, stats.accel.rateHz, 0.5)
        assertTrue("accel rate should clear the 100 Hz M2 bar", stats.accel.rateHz >= 100.0)
    }

    @Test
    fun countsEachTypeSeparately() {
        val stats = RecordingStats.compute(syntheticSession().asSequence())
        assertEquals(101, stats.accel.count)
        assertEquals(2, stats.gps.count)
        assertEquals(0, stats.gyro.count)
    }

    @Test
    fun monotonicStreamIsFlaggedMonotonic() {
        val stats = RecordingStats.compute(syntheticSession().asSequence())
        assertTrue(stats.overallMonotonic)
        assertTrue(stats.accel.monotonic)
    }

    @Test
    fun outOfOrderTimestampBreaksMonotonicity() {
        val samples = listOf(
            RawSample.Accel(100, 0f, 0f, 0f),
            RawSample.Accel(90, 0f, 0f, 0f), // goes backwards
            RawSample.Accel(110, 0f, 0f, 0f),
        )
        val stats = RecordingStats.compute(samples.asSequence())
        assertFalse(stats.overallMonotonic)
        assertFalse(stats.accel.monotonic)
    }

    @Test
    fun emptyStreamHasZeroRatesAndIsMonotonic() {
        val stats = RecordingStats.compute(emptySequence())
        assertEquals(0, stats.totalCount)
        assertEquals(0.0, stats.accel.rateHz, 0.0)
        assertTrue(stats.overallMonotonic)
    }
}
