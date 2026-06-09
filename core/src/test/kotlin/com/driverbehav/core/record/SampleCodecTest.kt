package com.driverbehav.core.record

import com.driverbehav.core.model.RawSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleCodecTest {

    @Test
    fun accel_roundTrips() {
        val s = RawSample.Accel(tMonoNanos = 123_456_789L, x = 0.1f, y = -9.81f, z = 3.5f)
        assertEquals(s, SampleCodec.decodeLine(SampleCodec.encode(s)))
    }

    @Test
    fun gyro_roundTrips() {
        val s = RawSample.Gyro(tMonoNanos = 42L, x = 0.001f, y = 0f, z = -1.25f)
        assertEquals(s, SampleCodec.decodeLine(SampleCodec.encode(s)))
    }

    @Test
    fun gps_roundTrips_withFullFix() {
        val s = RawSample.Gps(
            tMonoNanos = 1_000_000_000L,
            lat = -36.848461,
            lon = 174.763336,
            speedMps = 12.5f,
            accuracyM = 4.0f,
            bearingDeg = 271.3f,
        )
        assertEquals(s, SampleCodec.decodeLine(SampleCodec.encode(s)))
    }

    @Test
    fun gps_roundTrips_withNaNFields() {
        // Platform may not provide speed/bearing; NaN must survive the round trip.
        val s = RawSample.Gps(
            tMonoNanos = 7L,
            lat = 0.0,
            lon = 0.0,
            speedMps = Float.NaN,
            accuracyM = 8.0f,
            bearingDeg = Float.NaN,
        )
        val decoded = SampleCodec.decodeLine(SampleCodec.encode(s)) as RawSample.Gps
        assertEquals(s.lat, decoded.lat, 0.0)
        assertTrue(decoded.speedMps.isNaN())
        assertTrue(decoded.bearingDeg.isNaN())
        assertEquals(8.0f, decoded.accuracyM)
    }

    @Test
    fun headerAndBlankLines_decodeToNull() {
        assertNull(SampleCodec.decodeLine(SampleCodec.HEADER))
        assertNull(SampleCodec.decodeLine(""))
        assertNull(SampleCodec.decodeLine("   "))
        assertNull(SampleCodec.decodeLine("# any comment"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownRowType_throws() {
        SampleCodec.decodeLine("Z,1,2,3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedAccelRow_throws() {
        SampleCodec.decodeLine("A,1,2")
    }
}
