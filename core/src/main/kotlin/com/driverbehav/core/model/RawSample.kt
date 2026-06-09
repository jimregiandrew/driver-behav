package com.driverbehav.core.model

/**
 * A single raw sensor reading, as produced by the sensor-collection layer and
 * consumed by the `:core` pipeline (live or replayed from a recording).
 *
 * Every sample carries [tMonoNanos], a monotonic timestamp from the same clock
 * source across all sensor types (on Android: `SystemClock.elapsedRealtimeNanos()`).
 * Using one monotonic clock for accel, gyro and GPS lets the pipeline merge the
 * streams by time without worrying about wall-clock jumps or per-sensor epochs.
 *
 * Pure Kotlin — no Android imports. This is the contract the record-and-replay
 * harness is built on, so it must stay JVM-constructible.
 */
sealed interface RawSample {
    val tMonoNanos: Long

    /** Accelerometer reading in m/s², device frame (not yet vehicle-frame). */
    data class Accel(
        override val tMonoNanos: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    ) : RawSample

    /** Gyroscope reading in rad/s, device frame. */
    data class Gyro(
        override val tMonoNanos: Long,
        val x: Float,
        val y: Float,
        val z: Float,
    ) : RawSample

    /**
     * GPS fix. [speedMps] is ground speed in m/s; [accuracyM] is horizontal
     * accuracy in metres; [bearingDeg] is course over ground in degrees.
     * Fields that the platform did not provide are encoded as [Float.NaN].
     */
    data class Gps(
        override val tMonoNanos: Long,
        val lat: Double,
        val lon: Double,
        val speedMps: Float,
        val accuracyM: Float,
        val bearingDeg: Float,
    ) : RawSample
}
