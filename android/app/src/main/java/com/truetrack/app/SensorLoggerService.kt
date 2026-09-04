package com.truetrack.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class SensorLoggerService : Service() {

    private lateinit var sensorCollector: SensorCollector
    private lateinit var gnssCollector: GnssCollector
    private val imuQueue = ConcurrentLinkedQueue<SensorCollector.ImuSample>()
    private val gnssQueue = ConcurrentLinkedQueue<GnssCollector.GnssSample>()
    private var writerThread: Thread? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var imuSampleCount = 0L
    private var gnssSampleCount = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        sensorCollector = SensorCollector(sensorManager)
        gnssCollector = GnssCollector(locationManager)

        sensorCollector.setListener { sample ->
            if (isRecording) {
                imuQueue.offer(sample)
                imuSampleCount++
                SensorCollectorCallback.onImuSample?.invoke(sample)
                SensorCallback.onSensorUpdate?.invoke(
                    floatArrayOf(sample.ax, sample.ay, sample.az),
                    floatArrayOf(sample.gx, sample.gy, sample.gz),
                    floatArrayOf(sample.mx, sample.my, sample.mz)
                )
                SensorCallback.onImuData?.invoke(
                    sample.ax.toDouble(), sample.ay.toDouble(), sample.az.toDouble(),
                    sample.gx.toDouble(), sample.gy.toDouble(), sample.gz.toDouble(),
                    sample.timestamp,
                    sample.linearAccel, sample.gameRotation,
                    floatArrayOf(sample.mx, sample.my, sample.mz),
                    sample.rotationVector
                )
            }
        }

        gnssCollector.setListener { sample ->
            if (isRecording) {
                gnssQueue.offer(sample)
                gnssSampleCount++
                GnssCollectorCallback.onGnssSample?.invoke(sample)
                GnssCallback.onGnssMeasurement?.invoke(sample)

                val loc = android.location.Location("gnss").apply {
                    latitude = sample.latitude
                    longitude = sample.longitude
                    altitude = sample.altitude
                    speed = sample.speed
                    bearing = sample.bearing
                    accuracy = sample.horizontalAccuracy
                    time = sample.timestamp
                }
                GnssCallback.onLocationUpdate?.invoke(loc)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        imuSampleCount = 0
        gnssSampleCount = 0

        acquireWakeLock()
        sensorCollector.start()
        gnssCollector.start()

        SensorCollectorCallback.onSensorInfo?.invoke(sensorCollector.getAllSensors())

        writerThread = Thread {
            writeSensorData()
        }.apply {
            isDaemon = true
            start()
        }

        startForeground(NOTIFICATION_ID, createNotification("Recording..."))
    }

    private fun stopRecording() {
        isRecording = false
        sensorCollector.stop()
        gnssCollector.stop()
        releaseWakeLock()

        writerThread?.join(3000)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TrueTrack::SensorWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L)  // 10 minutes max
            }
            android.util.Log.d("SensorLoggerService", "Wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        android.util.Log.d("SensorLoggerService", "Wake lock released")
    }

    private fun writeSensorData() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recordingStartTime))
        val dir = getExternalFilesDir(null)?.let { java.io.File(it, "recordings") } ?: return
        dir.mkdirs()

        val imuFile = java.io.File(dir, "imu_$timestamp.csv")
        val gnssFile = java.io.File(dir, "gnss_$timestamp.csv")
        val metaFile = java.io.File(dir, "meta_$timestamp.txt")

        FileWriter(imuFile).use { writer ->
            writer.appendLine("timestamp_ms,ax,ay,az,gx,gy,gz,mx,my,mz,lin_accel_x,lin_accel_y,lin_accel_z,rw0,rw1,rw2,rw3")
            while (isRecording || imuQueue.isNotEmpty()) {
                val sample = imuQueue.poll()
                if (sample != null) {
                    val la = sample.linearAccel
                    val rw = sample.rotationVector
                    writer.appendLine(
                        "${sample.timestamp}," +
                        "${sample.ax},${sample.ay},${sample.az}," +
                        "${sample.gx},${sample.gy},${sample.gz}," +
                        "${sample.mx},${sample.my},${sample.mz}," +
                        "${la?.getOrNull(0) ?: ""},${la?.getOrNull(1) ?: ""},${la?.getOrNull(2) ?: ""}," +
                        "${rw?.getOrNull(0) ?: ""},${rw?.getOrNull(1) ?: ""}," +
                        "${rw?.getOrNull(2) ?: ""},${rw?.getOrNull(3) ?: ""}"
                    )
                } else {
                    Thread.sleep(10)
                }
            }
        }

        FileWriter(gnssFile).use { writer ->
            writer.appendLine(
                "timestamp_ms,latitude,longitude,altitude,speed,bearing," +
                "h_accuracy,v_accuracy,speed_accuracy,bearing_accuracy," +
                "sat_used,sat_total,gnss_available," +
                "raw_sv_id,raw_constellation,raw_state,raw_sv_time,raw_sv_time_unc," +
                "raw_cn0,raw_prr,raw_prr_unc,raw_adr,raw_adr_state,raw_code_type"
            )
            while (isRecording || gnssQueue.isNotEmpty()) {
                val sample = gnssQueue.poll()
                if (sample != null) {
                    if (sample.rawMeasurements.isEmpty()) {
                        writer.appendLine(
                            "${sample.timestamp}," +
                            "${sample.latitude},${sample.longitude},${sample.altitude}," +
                            "${sample.speed},${sample.bearing}," +
                            "${sample.horizontalAccuracy},${sample.verticalAccuracy}," +
                            "${sample.speedAccuracy},${sample.bearingAccuracy}," +
                            "${sample.satellitesUsed},${sample.satellitesTotal}," +
                            "${sample.gnssAvailable},,,,,,,,,,,"
                        )
                    } else {
                        for (raw in sample.rawMeasurements) {
                            writer.appendLine(
                                "${sample.timestamp}," +
                                "${sample.latitude},${sample.longitude},${sample.altitude}," +
                                "${sample.speed},${sample.bearing}," +
                                "${sample.horizontalAccuracy},${sample.verticalAccuracy}," +
                                "${sample.speedAccuracy},${sample.bearingAccuracy}," +
                                "${sample.satellitesUsed},${sample.satellitesTotal}," +
                                "${sample.gnssAvailable}," +
                                "${raw.svId},${raw.constellationType},${raw.state}," +
                                "${raw.receivedSvTimeNanos},${raw.receivedSvTimeUncertaintyNanos}," +
                                "${raw.cn0DbHz},${raw.pseudorangeRateMetersPerSecond}," +
                                "${raw.pseudorangeRateUncertaintyMetersPerSecond}," +
                                "${raw.accumulatedDeltaRangeMeters},${raw.accumulatedDeltaRangeState}," +
                                "${raw.codeType ?: ""}"
                            )
                        }
                    }
                } else {
                    Thread.sleep(10)
                }
            }
        }

        FileWriter(metaFile).use { writer ->
            writer.appendLine("recording_start_ms=$recordingStartTime")
            writer.appendLine("recording_end_ms=${System.currentTimeMillis()}")
            writer.appendLine("imu_sample_count=$imuSampleCount")
            writer.appendLine("gnss_sample_count=$gnssSampleCount")
            writer.appendLine("--- sensor info ---")
            sensorCollector.getSensorInfo().forEach { (key, value) ->
                writer.appendLine("$key=$value")
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TrueTrackApp.CHANNEL_ID)
            .setContentTitle("TrueTrack")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorCollector.stop()
        gnssCollector.stop()
        releaseWakeLock()
    }

    companion object {
        const val ACTION_START = "com.truetrack.app.START_LOGGING"
        const val ACTION_STOP = "com.truetrack.app.STOP_LOGGING"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SensorLoggerService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SensorLoggerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
