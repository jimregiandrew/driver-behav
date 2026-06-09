package com.driverbehav.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns one capture session: it holds a partial wake
 * lock, runs a [SensorSampler], and streams every reading to a [SessionRecorder]
 * file in app-private external storage (`adb pull`-able for offline analysis).
 *
 * Controlled by [ACTION_START] / [ACTION_STOP] intents. Current state is exposed
 * via [state] so the Compose UI can reflect recording status and live sample
 * count without binding.
 */
class TripService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var sampler: SensorSampler? = null
    private var recorder: SessionRecorder? = null
    private var tickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrip()
            ACTION_STOP -> stopTrip()
        }
        return START_NOT_STICKY
    }

    private fun startTrip() {
        if (recorder != null) return // already recording

        startForegroundNotification()
        acquireWakeLock()

        val file = newRecordingFile()
        val rec = SessionRecorder(file).also { it.start(scope) }
        val smp = SensorSampler(this) { sample -> rec.offer(sample) }.also { it.start() }
        recorder = rec
        sampler = smp

        _state.value = TripState.Recording(file.absolutePath, 0)
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                _state.value = TripState.Recording(file.absolutePath, rec.written)
            }
        }
    }

    private fun stopTrip() {
        tickJob?.cancel()
        tickJob = null
        sampler?.stop()
        sampler = null

        val rec = recorder
        recorder = null
        // Flush the buffer to disk before tearing the service down.
        runBlocking { rec?.stop() }
        _state.value = TripState.Idle

        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Defensive: if the process is torn down without an explicit stop.
        sampler?.stop()
        runBlocking { recorder?.stop() }
        releaseWakeLock()
        scope.cancel()
        _state.value = TripState.Idle
        super.onDestroy()
    }

    private fun newRecordingFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "recordings")
        return File(dir, "trip-$stamp.csv")
    }

    private fun startForegroundNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trip recording",
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording trip")
            .setContentText("Capturing sensor + GPS data")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "driverbehav:trip").apply {
            setReferenceCounted(false)
            acquire(MAX_TRIP_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.driverbehav.sensors.action.START"
        const val ACTION_STOP = "com.driverbehav.sensors.action.STOP"

        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TRIP_MS = 4 * 60 * 60 * 1000L // 4 h safety cap

        private val _state = MutableStateFlow<TripState>(TripState.Idle)
        val state: StateFlow<TripState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, TripService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
