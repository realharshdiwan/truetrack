package com.truetrack.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class SensorCollector(private val sensorManager: SensorManager) : SensorEventListener {

    companion object {
        private const val TAG = "SensorCollector"
        private const val WINDOW_SIZE = 200  // 1 second at 200Hz
        private const val EMIT_INTERVAL_MS = 50L  // emit at ~20Hz
    }

    data class ImuSample(
        val timestamp: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val mx: Float, val my: Float, val mz: Float,
        val linearAccel: FloatArray? = null,
        val gameRotation: FloatArray? = null,
        val rotationVector: FloatArray? = null,
        val gravity: FloatArray? = null,
        val pressure: Float = 0f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImuSample) return false
            return timestamp == other.timestamp
        }

        override fun hashCode(): Int = timestamp.hashCode()
    }

    // Sensors
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var rotationVector: Sensor? = null
    private var linearAccelSensor: Sensor? = null
    private var gameRotationSensor: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var barometer: Sensor? = null

    // Latest sensor values
    private val latestAccel = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private val latestMag = FloatArray(3)
    private var latestLinearAccel: FloatArray? = null
    private var latestGameRotation: FloatArray? = null
    private var latestRotation: FloatArray? = null
    private var latestGravity: FloatArray? = null
    private var latestPressure = 0f

    // Freshness flags
    private var accelFresh = false
    private var gyroFresh = false
    private var magFresh = false

    // Fixed-window buffer
    private val windowBuffer = ArrayDeque<ImuSample>(WINDOW_SIZE + 1)
    private var lastEmitMs = 0L

    private var listener: ((ImuSample) -> Unit)? = null
    var isActive = false
        private set

    val availableSensors = mutableMapOf<String, String>()

    fun setListener(listener: (ImuSample) -> Unit) {
        this.listener = listener
    }

    fun start() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        registerSensor(accelerometer, "Accelerometer", Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(gyroscope, "Gyroscope", Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(magnetometer, "Magnetometer", Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(rotationVector, "Rotation Vector", Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(linearAccelSensor, "Linear Acceleration", Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(gameRotationSensor, "Game Rotation Vector", Sensor.TYPE_GAME_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(gravitySensor, "Gravity", Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_GAME)
        registerSensor(barometer, "Barometer", Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL)

        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        Log.d(TAG, "Total sensors on device: ${allSensors.size}")
        for (sensor in allSensors) {
            if (!availableSensors.containsKey(sensor.name)) {
                Log.d(TAG, "  Other: ${sensor.name} (type=${sensor.type}, vendor=${sensor.vendor})")
            }
        }

        isActive = true
        lastEmitMs = System.currentTimeMillis()
    }

    private fun registerSensor(sensor: Sensor?, name: String, type: Int, rate: Int) {
        sensor?.also {
            sensorManager.registerListener(this, it, rate)
            availableSensors[name] = "${it.name} (${it.vendor}) res=${it.resolution} minDelay=${it.minDelay}μs"
            Log.d(TAG, "Registered: $name - ${it.name} (${it.vendor}) minDelay=${it.minDelay / 1000}Hz")
        } ?: run {
            Log.d(TAG, "Not available: $name")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isActive = false
    }

    fun getSensorInfo(): Map<String, String> = availableSensors.toMap()

    fun getAllSensors(): List<Pair<String, Boolean>> {
        return listOf(
            "Accelerometer" to (accelerometer != null),
            "Gyroscope" to (gyroscope != null),
            "Magnetometer" to (magnetometer != null),
            "Rotation Vector" to (rotationVector != null),
            "Linear Acceleration" to (linearAccelSensor != null),
            "Game Rotation Vector" to (gameRotationSensor != null),
            "Gravity" to (gravitySensor != null),
            "Barometer" to (barometer != null)
        )
    }

    fun getWindowSamples(): List<ImuSample> = windowBuffer.toList()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, latestAccel, 0, 3)
                accelFresh = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, latestGyro, 0, 3)
                gyroFresh = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, latestMag, 0, 3)
                magFresh = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                latestLinearAccel = event.values.copyOf()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                latestRotation = event.values.copyOf()
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                latestGameRotation = event.values.copyOf()
            }
            Sensor.TYPE_GRAVITY -> {
                latestGravity = event.values.copyOf()
            }
            Sensor.TYPE_PRESSURE -> {
                latestPressure = event.values[0]
            }
        }

        // Emit at fixed interval when accel + gyro + mag all fresh
        val now = System.currentTimeMillis()
        if (accelFresh && gyroFresh && magFresh && (now - lastEmitMs) >= EMIT_INTERVAL_MS) {
            accelFresh = false
            gyroFresh = false
            magFresh = false
            lastEmitMs = now

            val sample = ImuSample(
                timestamp = now,
                ax = latestAccel[0], ay = latestAccel[1], az = latestAccel[2],
                gx = latestGyro[0], gy = latestGyro[1], gz = latestGyro[2],
                mx = latestMag[0], my = latestMag[1], mz = latestMag[2],
                linearAccel = latestLinearAccel,
                gameRotation = latestGameRotation,
                rotationVector = latestRotation,
                gravity = latestGravity,
                pressure = latestPressure
            )

            windowBuffer.addLast(sample)
            if (windowBuffer.size > WINDOW_SIZE) {
                windowBuffer.removeFirst()
            }

            listener?.invoke(sample)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Accuracy changed: ${sensor?.name} accuracy=$accuracy")
    }
}
