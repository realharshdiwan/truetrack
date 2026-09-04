package com.truetrack.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var gnssFix: TextView
    private lateinit var gnssAccuracy: TextView
    private lateinit var gnssSats: TextView
    private lateinit var gnssHealth: TextView
    private lateinit var accel: TextView
    private lateinit var gyro: TextView
    private lateinit var mag: TextView
    private lateinit var linAccel: TextView
    private lateinit var fusionMode: TextView
    private lateinit var heading: TextView
    private lateinit var speed: TextView
    private lateinit var position: TextView
    private lateinit var drift: TextView
    private lateinit var confidence: TextView
    private lateinit var bias: TextView
    private lateinit var scale: TextView
    private lateinit var imuRate: TextView
    private lateinit var gnssRate: TextView
    private lateinit var recording: TextView
    private lateinit var exportButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastImuTimestamp = 0L
    private var lastGnssTimestamp = 0L
    private var imuSampleCount = 0
    private var gnssSampleCount = 0
    private var lastRateCheckMs = 0L
    private var imuRateHz = 0.0
    private var gnssRateHz = 0.0

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        gnssFix = findViewById(R.id.diagGnssFix)
        gnssAccuracy = findViewById(R.id.diagGnssAccuracy)
        gnssSats = findViewById(R.id.diagGnssSats)
        gnssHealth = findViewById(R.id.diagGnssHealth)
        accel = findViewById(R.id.diagAccel)
        gyro = findViewById(R.id.diagGyro)
        mag = findViewById(R.id.diagMag)
        linAccel = findViewById(R.id.diagLinAccel)
        fusionMode = findViewById(R.id.diagFusionMode)
        heading = findViewById(R.id.diagHeading)
        speed = findViewById(R.id.diagSpeed)
        position = findViewById(R.id.diagPosition)
        drift = findViewById(R.id.diagDrift)
        confidence = findViewById(R.id.diagConfidence)
        bias = findViewById(R.id.diagBias)
        scale = findViewById(R.id.diagScale)
        imuRate = findViewById(R.id.diagImuRate)
        gnssRate = findViewById(R.id.diagGnssRate)
        recording = findViewById(R.id.diagRecording)
        exportButton = findViewById(R.id.exportButton)

        exportButton.setOnClickListener {
            // TODO: implement export
        }

        setupCallbacks()
        handler.postDelayed(updateRunnable, 500)
    }

    private fun setupCallbacks() {
        SensorCallback.onSensorUpdate = { accelerometer, gyroscope, magnetometer ->
            val a = Math.sqrt(
                (accelerometer[0] * accelerometer[0] +
                 accelerometer[1] * accelerometer[1] +
                 accelerometer[2] * accelerometer[2]).toDouble()
            )
            val g = Math.sqrt(
                (gyroscope[0] * gyroscope[0] +
                 gyroscope[1] * gyroscope[1] +
                 gyroscope[2] * gyroscope[2]).toDouble()
            )
            val m = Math.sqrt(
                (magnetometer[0] * magnetometer[0] +
                 magnetometer[1] * magnetometer[1] +
                 magnetometer[2] * magnetometer[2]).toDouble()
            )

            handler.post {
                this.accel.text = "Accelerometer: ${String.format("%.3f", a)} g"
                this.gyro.text = "Gyroscope: ${String.format("%.2f", Math.toDegrees(g))} dps"
                this.mag.text = "Magnetometer: ${String.format("%.1f", m)} μT"
            }
        }

        SensorCallback.onImuData = { ax, ay, az, gx, gy, gz, ts, linearAccel, _, _ ->
            lastImuTimestamp = ts
            imuSampleCount++
            val la = linearAccel
            if (la != null && la.size >= 3) {
                val laMag = Math.sqrt((la[0] * la[0] + la[1] * la[1] + la[2] * la[2]).toDouble())
                handler.post {
                    this.linAccel.text = "Linear Accel: ${String.format("%.3f", laMag)} g"
                }
            }
        }

        GnssCallback.onGnssMeasurement = { sample ->
            lastGnssTimestamp = sample.timestamp
            gnssSampleCount++
            handler.post {
                gnssFix.text = "Fix: ✓"
                gnssAccuracy.text = "Accuracy: ${String.format("%.1f", sample.horizontalAccuracy)}m"
                gnssSats.text = "Satellites: ${sample.satellitesUsed} / ${sample.satellitesTotal}"
            }
        }

        GnssCallback.onLocationUpdate = { location ->
            handler.post {
                gnssFix.text = "Fix: ✓ (lat=${String.format("%.6f", location.latitude)}, lon=${String.format("%.6f", location.longitude)})"
            }
        }
    }

    private fun updateDisplay() {
        val now = System.currentTimeMillis()

        // Compute rates
        if (lastRateCheckMs > 0) {
            val elapsed = (now - lastRateCheckMs) / 1000.0
            if (elapsed > 0) {
                imuRateHz = imuSampleCount / elapsed
                gnssRateHz = gnssSampleCount / elapsed
            }
        }
        lastRateCheckMs = now
        imuSampleCount = 0
        gnssSampleCount = 0

        imuRate.text = "IMU rate: ${String.format("%.0f", imuRateHz)} Hz"
        gnssRate.text = "GNSS rate: ${String.format("%.0f", gnssRateHz)} Hz"
        recording.text = "Recording: active"

        // Fusion state (from global callbacks — would need access to RealtimeFusion)
        // These are placeholder until we wire up the actual fusion state
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
