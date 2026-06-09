package com.driverbehav.core.record

import com.driverbehav.core.model.RawSample

/**
 * Line-oriented CSV codec for [RawSample] recordings.
 *
 * One sample per line, with a leading type tag so accel/gyro/GPS rows — which
 * have different columns — can share a file and be demultiplexed on replay:
 *
 * ```
 * # driver-behav recording v1
 * A,<tMonoNanos>,<x>,<y>,<z>
 * G,<tMonoNanos>,<x>,<y>,<z>
 * L,<tMonoNanos>,<lat>,<lon>,<speedMps>,<accuracyM>,<bearingDeg>
 * ```
 *
 * CSV is deliberate: the file is `adb pull`-able and drops straight into a
 * notebook for the M2 eyeball-plot. Kotlin's [Float]/[Double] `toString`
 * emits the shortest round-tripping decimal, so encode→decode is exact; `NaN`
 * survives the round trip for absent GPS fields.
 *
 * Pure Kotlin — shared by the `:sensors` writer and the `:core` replay reader.
 */
object SampleCodec {

    const val FORMAT_VERSION = 1

    /** Header line written once at the top of every recording. */
    const val HEADER = "# driver-behav recording v$FORMAT_VERSION"

    private const val SEP = ','

    fun encode(sample: RawSample): String = when (sample) {
        is RawSample.Accel ->
            "A,${sample.tMonoNanos},${sample.x},${sample.y},${sample.z}"
        is RawSample.Gyro ->
            "G,${sample.tMonoNanos},${sample.x},${sample.y},${sample.z}"
        is RawSample.Gps ->
            "L,${sample.tMonoNanos},${sample.lat},${sample.lon}," +
                "${sample.speedMps},${sample.accuracyM},${sample.bearingDeg}"
    }

    /**
     * Decode one line. Returns null for blank lines and comment/header lines
     * (those starting with `#`), so a whole file can be mapped through this.
     * Throws [IllegalArgumentException] on a malformed data row.
     */
    fun decodeLine(line: String): RawSample? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

        val f = trimmed.split(SEP)
        return when (f[0]) {
            "A" -> {
                require(f.size == 5) { "malformed accel row: $line" }
                RawSample.Accel(f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat())
            }
            "G" -> {
                require(f.size == 5) { "malformed gyro row: $line" }
                RawSample.Gyro(f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat())
            }
            "L" -> {
                require(f.size == 7) { "malformed gps row: $line" }
                RawSample.Gps(
                    tMonoNanos = f[1].toLong(),
                    lat = f[2].toDouble(),
                    lon = f[3].toDouble(),
                    speedMps = f[4].toFloat(),
                    accuracyM = f[5].toFloat(),
                    bearingDeg = f[6].toFloat(),
                )
            }
            else -> throw IllegalArgumentException("unknown row type '${f[0]}' in: $line")
        }
    }
}
