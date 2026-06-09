package com.driverbehav.core.record

import com.driverbehav.core.model.RawSample

/**
 * Summary statistics over a recording, used to validate a capture session.
 *
 * This is the machine-checkable half of the M2 acceptance test ("monotonically
 * increasing timestamps and sample rate ≥ 100 Hz"): compute it over a `:core`
 * replay of a pulled recording and assert, rather than eyeballing. It runs on
 * the JVM, so it doubles as a unit-test fixture and a dev CLI helper.
 */
data class RecordingStats(
    val accel: TypeStats,
    val gyro: TypeStats,
    val gps: TypeStats,
    /** True if [RawSample.tMonoNanos] is non-decreasing across the whole stream. */
    val overallMonotonic: Boolean,
) {
    val totalCount: Int get() = accel.count + gyro.count + gps.count

    /** Per-sensor-type stats. Rates are derived, not stored. */
    data class TypeStats(
        val count: Int,
        val firstNanos: Long,
        val lastNanos: Long,
        /** True if this type's timestamps are non-decreasing. */
        val monotonic: Boolean,
    ) {
        val durationSec: Double
            get() = if (count > 1) (lastNanos - firstNanos) / 1_000_000_000.0 else 0.0

        /**
         * Mean sample rate in Hz: gaps between samples, so (count − 1) / duration.
         * Zero for fewer than two samples or a zero-length span.
         */
        val rateHz: Double
            get() = if (count > 1 && durationSec > 0.0) (count - 1) / durationSec else 0.0

        companion object {
            val EMPTY = TypeStats(count = 0, firstNanos = 0, lastNanos = 0, monotonic = true)
        }
    }

    companion object {
        fun compute(samples: Sequence<RawSample>): RecordingStats {
            val accel = Accumulator()
            val gyro = Accumulator()
            val gps = Accumulator()
            var overallMonotonic = true
            var prevNanos = Long.MIN_VALUE

            for (s in samples) {
                if (s.tMonoNanos < prevNanos) overallMonotonic = false
                prevNanos = s.tMonoNanos
                when (s) {
                    is RawSample.Accel -> accel.add(s.tMonoNanos)
                    is RawSample.Gyro -> gyro.add(s.tMonoNanos)
                    is RawSample.Gps -> gps.add(s.tMonoNanos)
                }
            }
            return RecordingStats(accel.toStats(), gyro.toStats(), gps.toStats(), overallMonotonic)
        }
    }

    private class Accumulator {
        private var count = 0
        private var first = 0L
        private var last = 0L
        private var monotonic = true

        fun add(nanos: Long) {
            if (count == 0) first = nanos else if (nanos < last) monotonic = false
            last = nanos
            count++
        }

        fun toStats(): TypeStats =
            if (count == 0) TypeStats.EMPTY else TypeStats(count, first, last, monotonic)
    }
}
