package com.driverbehav.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.driverbehav.core.model.RawSample

/**
 * Registers accelerometer + gyroscope at [SensorManager.SENSOR_DELAY_FASTEST]
 * and GPS at 1 Hz, normalising every reading into a [RawSample] stamped with a
 * single monotonic clock ([SystemClock.elapsedRealtimeNanos]) and forwarding it
 * to [onSample].
 *
 * All callbacks are delivered on one dedicated [HandlerThread], so [onSample]
 * sees a serialised stream and never runs on the main thread. The consumer must
 * not block in [onSample] — at FASTEST this fires hundreds of times per second,
 * so hand off to a queue (see [SessionRecorder]) rather than doing I/O here.
 *
 * Sensor `SensorEvent.timestamp` is also `elapsedRealtimeNanos`-based, so it is
 * used directly. Location exposes `elapsedRealtimeNanos` on API 17+, keeping all
 * three streams on the same timebase for clean time-merging downstream.
 */
class SensorSampler(
    context: Context,
    private val onSample: (RawSample) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val t = event.timestamp // elapsedRealtimeNanos timebase
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    onSample(RawSample.Accel(t, event.values[0], event.values[1], event.values[2]))
                Sensor.TYPE_GYROSCOPE ->
                    onSample(RawSample.Gyro(t, event.values[0], event.values[1], event.values[2]))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val locationListener = LocationListener { loc -> onSample(loc.toRawSample()) }

    /**
     * Begin sampling. Requires `ACCESS_FINE_LOCATION` to already be granted;
     * the caller (UI) is responsible for the runtime grant before starting a trip.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (thread != null) return
        val t = HandlerThread("SensorSampler").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h

        accel?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        gyro?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L, // 1 Hz
                0f,
                locationListener,
                t.looper,
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        locationManager.removeUpdates(locationListener)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** True if the device actually has both inertial sensors we score on. */
    fun hasRequiredSensors(): Boolean = accel != null && gyro != null

    private fun Location.toRawSample(): RawSample.Gps = RawSample.Gps(
        tMonoNanos = elapsedRealtimeNanos,
        lat = latitude,
        lon = longitude,
        speedMps = if (hasSpeed()) speed else Float.NaN,
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        bearingDeg = if (hasBearing()) bearing else Float.NaN,
    )
}
