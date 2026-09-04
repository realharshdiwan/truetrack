package com.truetrack.app

import kotlin.math.*

class VehicleStateEstimator {

    companion object {
        private const val TAG = "VehicleState"
        private const val LAT_TO_M = 111320.0
        private const val GYRO_DEAD_ZONE = 0.0025  // rad/s — below this, treat as zero
        private const val GPS_CAL_WINDOW = 20       // sliding window for GPS calibration
        private const val MIN_CAL_POINTS = 3        // minimum GPS fixes before calibrating
    }

    enum class PositioningMode {
        GPS_ONLY,
        GPS_IMU_FUSED,
        INERTIAL_ONLY,
        IDLE
    }

    data class State(
        val lat: Double,
        val lon: Double,
        val heading: Double,
        val speed: Double,
        val vx: Double,
        val vy: Double,
        val headingBiasRad: Double,
        val scaleFactor: Double,
        val mode: PositioningMode,
        val confidenceM: Double,
        val timestampMs: Long
    )

    // === EKF state ===
    // State: [lat, lon, heading, vx, vy, headingBias, scaleFactor]
    private val N = 7
    private val state = DoubleArray(N)
    private val P = Array(N) { DoubleArray(N) }
    private val Q = Array(N) { DoubleArray(N) }

    // State indices
    private val LAT = 0
    private val LON = 1
    private val HDG = 2
    private val VX = 3
    private val VY = 4
    private val H_BIAS = 5
    private val SCALE = 6

    var currentState: State? = null
        private set

    var mode = PositioningMode.IDLE
        private set

    var confidenceM = 0.0
        private set

    private var initialized = false
    private var lastTimestampMs = 0L
    private var gpsFixCount = 0
    private var outageStartMs = 0L
    private var isSimulatedOutage = false

    // === Heading fusion (complementary filter + 1D Kalman) ===
    private var gyroHeading = 0.0          // integrated gyro heading
    private var magHeading = 0.0           // magnetometer heading
    private var fusedHeading = 0.0         // complementary filter output
    private var headingKalmanX = 0.0       // 1D Kalman state
    private var headingKalmanP = 1.0       // 1D Kalman variance
    private val headingKalmanQ = 0.001     // process noise
    private val headingKalmanR = 0.05      // measurement noise
    private val COMP_ALPHA = 0.96          // complementary filter: 96% gyro, 4% mag

    // === GPS smoothing (EMA with outlier rejection) ===
    private var smoothedLat = 0.0
    private var smoothedLon = 0.0
    private var gpsSmoothCount = 0
    private val gpsSmoothAlpha = 0.15

    // === GPS calibration (sliding window) ===
    private val calWindow = ArrayDeque<GpsCalPoint>(GPS_CAL_WINDOW + 1)
    private var headingBiasFromGps = 0.0   // heading correction from GPS
    private var scaleFactorFromGps = 1.0   // velocity scale correction

    // === Stationary detection ===
    private var isStationary = false
    private var stationaryAccelMag = 0.0

    // === Gyro bias (online running average) ===
    private var gyroBiasZ = 0.0
    private var gyroBiasSampleCount = 0
    private val GYRO_BIAS_MAX_SAMPLES = 300

    // === Last known position for bootstrap ===
    private var lastKnownLat: Double? = null
    private var lastKnownLon: Double? = null

    // === Track histories ===
    val gpsTrack = mutableListOf<Pair<Double, Double>>()
    val inertialTrack = mutableListOf<Pair<Double, Double>>()
    val fusedTrack = mutableListOf<Pair<Double, Double>>()

    private data class GpsCalPoint(
        val lat: Double, val lon: Double,
        val bearing: Double, val speed: Double,
        val timestampMs: Long
    )

    fun start() {
        initialized = false
        lastTimestampMs = 0L
        gpsFixCount = 0
        mode = PositioningMode.IDLE
        confidenceM = 0.0
        gpsTrack.clear()
        inertialTrack.clear()
        fusedTrack.clear()
        outageStartMs = 0L

        smoothedLat = 0.0
        smoothedLon = 0.0
        gpsSmoothCount = 0

        gyroHeading = 0.0
        magHeading = 0.0
        fusedHeading = 0.0
        headingKalmanX = 0.0
        headingKalmanP = 1.0

        calWindow.clear()
        headingBiasFromGps = 0.0
        scaleFactorFromGps = 1.0

        isStationary = false
        gyroBiasZ = 0.0
        gyroBiasSampleCount = 0

        for (i in 0 until N) {
            state[i] = 0.0
            P[i][i] = 0.0
            Q[i][i] = 0.0
        }
    }

    // ========================================
    // GPS PROCESSING
    // ========================================

    fun processGps(
        lat: Double, lon: Double, speed: Double, bearing: Double,
        accuracyM: Float, timestampMs: Long
    ): State {
        // 1. GPS smoothing with outlier rejection
        applyGpsSmoothing(lat, lon)

        gpsTrack.add(Pair(smoothedLat, smoothedLon))
        gpsFixCount++

        // 2. First fix: initialize or snap from IMU-only
        if (!initialized) {
            initializeState(smoothedLat, smoothedLon, speed, bearing, timestampMs)
            mode = PositioningMode.GPS_ONLY
            buildState(timestampMs)
            return currentState!!
        }

        // If we were in IMU-only mode (started without GPS), snap position
        if (state[LAT] == 0.0 && state[LON] == 0.0) {
            state[LAT] = smoothedLat
            state[LON] = smoothedLon
            gpsTrack.clear()
            inertialTrack.clear()
            fusedTrack.clear()
            gpsTrack.add(Pair(smoothedLat, smoothedLon))
        }

        val dt = getDt(timestampMs)
        if (dt <= 0 || dt > 5.0) {
            lastTimestampMs = timestampMs
            return currentState!!
        }

        // 3. EKF predict
        ekfPredict(dt)

        // 4. GPS position correction
        ekfGpsPositionCorrection(smoothedLat, smoothedLon, accuracyM)

        // 5. GPS velocity/bearing correction (if moving)
        if (speed > 0.5) {
            ekfGpsVelocityCorrection(speed, bearing)
        }

        // 6. GPS calibration: update heading bias and scale factor
        updateGpsCalibration(smoothedLat, smoothedLon, speed, bearing, timestampMs)

        // 7. Apply GPS calibration corrections
        state[H_BIAS] = headingBiasFromGps
        state[SCALE] = scaleFactorFromGps

        lastTimestampMs = timestampMs

        // 8. Mode selection based on accuracy and state
        mode = when {
            isSimulatedOutage -> PositioningMode.INERTIAL_ONLY
            accuracyM > 100 -> PositioningMode.INERTIAL_ONLY
            gpsFixCount <= 3 -> PositioningMode.GPS_ONLY
            else -> PositioningMode.GPS_IMU_FUSED
        }

        confidenceM = computeConfidence(accuracyM.toDouble())
        fusedTrack.add(Pair(state[LAT], state[LON]))
        buildState(timestampMs)
        return currentState!!
    }

    // ========================================
    // IMU PROCESSING
    // ========================================

    fun processImu(
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
        timestampMs: Long,
        linearAccel: FloatArray? = null,
        gameRotation: FloatArray? = null,
        magnetometer: FloatArray? = null,
        rotationVector: FloatArray? = null
    ): State {
        // Initialize from IMU if no GPS fix yet — heading from magnetometer, position stays at 0,0
        if (!initialized) {
            initializeFromImu(timestampMs, magnetometer)
            mode = PositioningMode.INERTIAL_ONLY
            outageStartMs = timestampMs
        }

        val dt = getDt(timestampMs)
        if (dt <= 0 || dt > 0.5) return currentState!!

        // 1. Gyro bias estimation (while stationary or first N samples)
        updateGyroBias(gx, gy, gz)

        // 2. Apply gyro dead-zone filter and bias correction
        val correctedGyroZ = applyGyroDeadZone(gz - gyroBiasZ)

        // 3. Get gravity-free acceleration
        val (aNorth, aEast) = getGravityFreeAccel(ax, ay, az, linearAccel, magnetometer)

        // 4. Stationary detection
        updateStationaryDetection(aNorth, aEast, correctedGyroZ)

        // 5. Heading fusion: prefer rotation vector (tilt-compensated), fallback to mag
        if (gameRotation != null) {
            updateHeadingFromRotationVector(gameRotation)
        } else if (rotationVector != null) {
            updateHeadingFromRotationVector(rotationVector)
        } else if (magnetometer != null) {
            updateMagHeading(magnetometer)
        }
        updateGyroHeading(correctedGyroZ, dt)
        updateComplementaryHeading()
        updateHeadingKalman()

        // 6. EKF predict
        ekfPredict(dt)

        // 7. IMU velocity update (from acceleration, only if not stationary)
        if (!isStationary) {
            state[VX] += aNorth * dt * state[SCALE]
            state[VY] += aEast * dt * state[SCALE]
        }

        // 8. Velocity damping (prevent drift)
        val damping = if (isStationary) 0.90 else 0.998
        state[VX] *= damping
        state[VY] *= damping

        // 9. Position update from velocity
        state[LAT] += state[VX] * dt / LAT_TO_M
        state[LON] += state[VY] * dt / (LAT_TO_M * cos(state[LAT] * PI / 180.0))

        // 10. Heading update from complementary filter (with bias correction)
        val correctedHeading = wrapAngle(fusedHeading - state[H_BIAS])
        // Blend gyro-integrated heading with complementary filter
        state[HDG] = 0.7 * state[HDG] + 0.3 * correctedHeading

        // 11. Covariance growth
        val dt2 = dt * dt
        P[LAT][LAT] += (state[VX] * dt / LAT_TO_M).pow(2) * 0.01 + Q[LAT][LAT] * dt2
        P[LON][LON] += (state[VY] * dt / LAT_TO_M).pow(2) * 0.01 + Q[LON][LON] * dt2
        P[HDG][HDG] += Q[HDG][HDG] * dt2
        P[VX][VX] += Q[VX][VX] * dt2
        P[VY][VY] += Q[VY][VY] * dt2

        // 12. During outage, grow uncertainty
        if (mode == PositioningMode.INERTIAL_ONLY) {
            val elapsed = (timestampMs - outageStartMs) / 1000.0
            val growthFactor = 1.0 + elapsed * 0.1
            for (i in 0..4) P[i][i] *= growthFactor
        }

        lastTimestampMs = timestampMs
        fusedTrack.add(Pair(state[LAT], state[LON]))
        inertialTrack.add(Pair(state[LAT], state[LON]))

        // Mode: INERTIAL_ONLY if no GPS fix yet or simulated outage
        if (mode != PositioningMode.GPS_ONLY && mode != PositioningMode.GPS_IMU_FUSED) {
            mode = if (isSimulatedOutage || gpsFixCount == 0) PositioningMode.INERTIAL_ONLY else PositioningMode.GPS_IMU_FUSED
        }

        confidenceM = computeConfidence(0.0)
        buildState(timestampMs)
        return currentState!!
    }

    fun startOutage() {
        outageStartMs = System.currentTimeMillis()
        isSimulatedOutage = true
        mode = PositioningMode.INERTIAL_ONLY
    }

    fun endOutage() {
        outageStartMs = 0L
        isSimulatedOutage = false
        if (initialized) {
            mode = if (gpsFixCount > 0) PositioningMode.GPS_IMU_FUSED else PositioningMode.INERTIAL_ONLY
        }
    }

    fun setInitialPosition(lat: Double, lon: Double) {
        lastKnownLat = lat
        lastKnownLon = lon
        if (!initialized) {
            state[LAT] = lat
            state[LON] = lon
        }
    }

    // ========================================
    // GPS SMOOTHING
    // ========================================

    private fun applyGpsSmoothing(lat: Double, lon: Double) {
        if (gpsSmoothCount == 0) {
            smoothedLat = lat
            smoothedLon = lon
        } else {
            val jumpM = haversine(smoothedLat, smoothedLon, lat, lon)
            if (jumpM > 50.0) {
                smoothedLat = lat
                smoothedLon = lon
            } else {
                smoothedLat = gpsSmoothAlpha * lat + (1 - gpsSmoothAlpha) * smoothedLat
                smoothedLon = gpsSmoothAlpha * lon + (1 - gpsSmoothAlpha) * smoothedLon
            }
        }
        gpsSmoothCount++
    }

    // ========================================
    // GPS CALIBRATION (sliding window + EMA)
    // ========================================

    private fun updateGpsCalibration(
        lat: Double, lon: Double, speed: Double, bearing: Double,
        timestampMs: Long
    ) {
        calWindow.addLast(GpsCalPoint(lat, lon, bearing, speed, timestampMs))
        if (calWindow.size > GPS_CAL_WINDOW) {
            calWindow.removeFirst()
        }

        if (calWindow.size < MIN_CAL_POINTS || speed < 1.0) return

        // Compute heading bias: difference between GPS bearing and EKF heading
        val gpsBearingRad = Math.toRadians(bearing.toDouble())
        val ekfHeading = state[HDG]
        val diff = wrapAngle(gpsBearingRad - ekfHeading)
        headingBiasFromGps = 0.9 * headingBiasFromGps + 0.1 * diff

        // Compute scale factor: GPS distance / IMU-estimated distance
        if (calWindow.size >= 2) {
            val prev = calWindow[calWindow.size - 2]
            val curr = calWindow[calWindow.size - 1]
            val gpsDist = haversine(prev.lat, prev.lon, curr.lat, curr.lon)
            val dt = (curr.timestampMs - prev.timestampMs) / 1000.0
            if (dt > 0.1 && speed > 1.0) {
                val imuDist = speed * dt * scaleFactorFromGps
                if (imuDist > 0.5) {
                    val currentScale = gpsDist / imuDist
                    scaleFactorFromGps = 0.8 * scaleFactorFromGps + 0.2 * currentScale
                    scaleFactorFromGps = scaleFactorFromGps.coerceIn(0.5, 2.0)
                }
            }
        }
    }

    // ========================================
    // HEADING FUSION
    // ========================================

    private fun updateGyroHeading(gyroZ: Double, dt: Double) {
        gyroHeading = wrapAngle(gyroHeading + gyroZ * dt)
    }

    private fun updateMagHeading(magnetometer: FloatArray) {
        if (magnetometer.size >= 2) {
            val mx = magnetometer[0].toDouble()
            val my = magnetometer[1].toDouble()
            if (abs(mx) > 0.1 || abs(my) > 0.1) {
                magHeading = atan2(-my, mx)
            }
        }
    }

    private fun updateHeadingFromRotationVector(rotationVector: FloatArray?) {
        if (rotationVector == null || rotationVector.size < 4) return
        // rotation vector: [x, y, z, w] (or [x, y, z, accuracy, w])
        val x = rotationVector[0].toDouble()
        val y = rotationVector[1].toDouble()
        val z = rotationVector[2].toDouble()
        val w = if (rotationVector.size >= 5) rotationVector[4].toDouble() else rotationVector[3].toDouble()

        // Extract yaw (heading) from quaternion
        val siny_cosp = 2.0 * (w * z + x * y)
        val cosy_cosp = 1.0 - 2.0 * (y * y + z * z)
        val yaw = atan2(siny_cosp, cosy_cosp) // This is tilt-compensated heading

        magHeading = yaw
    }

    private fun updateComplementaryHeading() {
        if (magHeading == 0.0 && gyroHeading == 0.0) return
        fusedHeading = COMP_ALPHA * gyroHeading + (1 - COMP_ALPHA) * magHeading
        fusedHeading = wrapAngle(fusedHeading)
    }

    private fun updateHeadingKalman() {
        // 1D Kalman filter on fused heading
        val predicted = headingKalmanX
        val pPred = headingKalmanP + headingKalmanQ

        val innovation = wrapAngle(fusedHeading - predicted)
        val S = pPred + headingKalmanR
        if (S > 0) {
            val K = pPred / S
            headingKalmanX = wrapAngle(predicted + K * innovation)
            headingKalmanP = (1 - K) * pPred
        }
    }

    // ========================================
    // GYRO PROCESSING
    // ========================================

    private fun updateGyroBias(gx: Double, gy: Double, gz: Double) {
        if (gyroBiasSampleCount < GYRO_BIAS_MAX_SAMPLES) {
            val n = gyroBiasSampleCount.toDouble()
            gyroBiasZ = gyroBiasZ * (n / (n + 1)) + gz * (1 / (n + 1))
            gyroBiasSampleCount++
        }
    }

    private fun applyGyroDeadZone(value: Double): Double {
        return if (abs(value) < GYRO_DEAD_ZONE) 0.0 else value
    }

    // ========================================
    // ACCELERATION PROCESSING
    // ========================================

    private fun getGravityFreeAccel(
        ax: Double, ay: Double, az: Double,
        linearAccel: FloatArray?,
        magnetometer: FloatArray?
    ): Pair<Double, Double> {
        val rawNorth: Double
        val rawEast: Double

        if (linearAccel != null && linearAccel.size >= 2) {
            // TYPE_LINEAR_ACCELERATION: gravity already removed by Android
            // Transform from phone frame to NED using heading
            val h = state[HDG]
            val cosH = cos(h)
            val sinH = sin(h)
            rawNorth = -linearAccel[0] * sinH + linearAccel[1] * cosH
            rawEast = linearAccel[0] * cosH + linearAccel[1] * sinH
        } else {
            // Fallback: raw accel minus gravity estimate
            val gravZ = 9.81
            val aBodyX = ax
            val aBodyY = ay
            val aBodyZ = az - gravZ

            // Simple phone→NED (assumes phone is roughly flat)
            val h = state[HDG]
            val cosH = cos(h)
            val sinH = sin(h)
            rawNorth = -aBodyX * sinH + aBodyY * cosH
            rawEast = aBodyX * cosH + aBodyY * sinH
        }

        return Pair(rawNorth, rawEast)
    }

    private fun updateStationaryDetection(aNorth: Double, aEast: Double, gyroZ: Double) {
        val accelMag = sqrt(aNorth * aNorth + aEast * aEast)
        stationaryAccelMag = 0.9 * stationaryAccelMag + 0.1 * accelMag
        isStationary = stationaryAccelMag < 0.3 && abs(gyroZ) < 0.02
    }

    // ========================================
    // EKF CORE
    // ========================================

    private fun initializeState(lat: Double, lon: Double, speed: Double, bearing: Double, timestampMs: Long) {
        val headingRad = Math.toRadians(bearing.toDouble())

        state[LAT] = lat
        state[LON] = lon
        state[HDG] = headingRad
        state[VX] = speed * sin(headingRad)
        state[VY] = speed * cos(headingRad)
        state[H_BIAS] = 0.0
        state[SCALE] = 1.0

        // Initial covariance
        P[LAT][LAT] = 1e-6
        P[LON][LON] = 1e-6
        P[HDG][HDG] = 0.01
        P[VX][VX] = 1.0
        P[VY][VY] = 1.0
        P[H_BIAS][H_BIAS] = 0.001
        P[SCALE][SCALE] = 0.01

        // Process noise
        Q[LAT][LAT] = 0.25
        Q[LON][LON] = 0.25
        Q[HDG][HDG] = 0.01
        Q[VX][VX] = 4.0
        Q[VY][VY] = 4.0
        Q[H_BIAS][H_BIAS] = 1e-6
        Q[SCALE][SCALE] = 1e-6

        lastTimestampMs = timestampMs
        headingKalmanX = headingRad
        gyroHeading = headingRad
        fusedHeading = headingRad
        initialized = true
    }

    private fun initializeFromImu(timestampMs: Long, magnetometer: FloatArray?) {
        // Initialize heading from magnetometer
        var headingRad = 0.0
        if (magnetometer != null && magnetometer.size >= 2) {
            val mx = magnetometer[0].toDouble()
            val my = magnetometer[1].toDouble()
            if (abs(mx) > 0.1 || abs(my) > 0.1) {
                headingRad = atan2(-my, mx)
            }
        }

        // Use last known position if available, otherwise (0,0)
        state[LAT] = lastKnownLat ?: 0.0
        state[LON] = lastKnownLon ?: 0.0
        state[HDG] = headingRad
        state[VX] = 0.0
        state[VY] = 0.0
        state[H_BIAS] = 0.0
        state[SCALE] = 1.0

        P[LAT][LAT] = 1e-6
        P[LON][LON] = 1e-6
        P[HDG][HDG] = 0.1
        P[VX][VX] = 1.0
        P[VY][VY] = 1.0
        P[H_BIAS][H_BIAS] = 0.001
        P[SCALE][SCALE] = 0.01

        Q[LAT][LAT] = 0.25
        Q[LON][LON] = 0.25
        Q[HDG][HDG] = 0.01
        Q[VX][VX] = 4.0
        Q[VY][VY] = 4.0
        Q[H_BIAS][H_BIAS] = 1e-6
        Q[SCALE][SCALE] = 1e-6

        lastTimestampMs = timestampMs
        headingKalmanX = headingRad
        gyroHeading = headingRad
        fusedHeading = headingRad
        magHeading = headingRad
        initialized = true
    }

    private fun ekfPredict(dt: Double) {
        val vx = state[VX]
        val vy = state[VY]

        state[LAT] += vx * dt / LAT_TO_M
        state[LON] += vy * dt / (LAT_TO_M * cos(state[LAT] * PI / 180.0))

        // Jacobian F
        val F = Array(N) { DoubleArray(N) }
        for (i in 0 until N) F[i][i] = 1.0
        F[LAT][VX] = dt / LAT_TO_M
        F[LON][VY] = dt / (LAT_TO_M * cos(state[LAT] * PI / 180.0))

        // P = F * P * F^T + Q
        val FP = multiplyMatrices(F, P)
        val FPFT = multiplyTransposed(FP, F)
        for (i in 0 until N) {
            for (j in 0 until N) {
                P[i][j] = FPFT[i][j] + Q[i][j]
            }
        }
    }

    private fun ekfGpsPositionCorrection(lat: Double, lon: Double, accuracyM: Float) {
        val R = maxOf(accuracyM.toDouble(), 1.0)
        val R2 = R * R

        val innovLat = lat - state[LAT]
        val innovLon = lon - state[LON]
        val Slat = P[LAT][LAT] + R2 / (LAT_TO_M * LAT_TO_M)
        val Slon = P[LON][LON] + R2 / (LAT_TO_M * LAT_TO_M)

        if (Slat > 0) {
            val K = P[LAT][LAT] / Slat
            state[LAT] += K * innovLat
            P[LAT][LAT] *= (1 - K)
        }
        if (Slon > 0) {
            val K = P[LON][LON] / Slon
            state[LON] += K * innovLon
            P[LON][LON] *= (1 - K)
        }
    }

    private fun ekfGpsVelocityCorrection(speed: Double, bearing: Double) {
        val headingRad = Math.toRadians(bearing.toDouble())
        val newVx = speed * sin(headingRad)
        val newVy = speed * cos(headingRad)
        val speedNoise = 1.0

        val Svx = P[VX][VX] + speedNoise * speedNoise
        val Svy = P[VY][VY] + speedNoise * speedNoise

        if (Svx > 0) {
            val K = P[VX][VX] / Svx
            state[VX] += K * (newVx - state[VX])
            P[VX][VX] *= (1 - K)
        }
        if (Svy > 0) {
            val K = P[VY][VY] / Svy
            state[VY] += K * (newVy - state[VY])
            P[VY][VY] *= (1 - K)
        }

        // Heading correction from GPS bearing
        val innovHdg = wrapAngle(headingRad - state[HDG])
        val Shdg = P[HDG][HDG] + (10.0 * PI / 180.0).pow(2)
        if (Shdg > 0) {
            val K = P[HDG][HDG] / Shdg
            state[HDG] += K * innovHdg
            P[HDG][HDG] *= (1 - K)
        }
    }

    private fun computeConfidence(gpsAccuracyM: Double): Double {
        val filterUncertainty = sqrt(P[LAT][LAT] * LAT_TO_M * LAT_TO_M + P[LON][LON] * LAT_TO_M * LAT_TO_M)
        return if (gpsAccuracyM > 0) {
            maxOf(filterUncertainty, gpsAccuracyM * 0.5)
        } else {
            filterUncertainty
        }
    }

    // ========================================
    // STATE BUILDER
    // ========================================

    private fun buildState(timestampMs: Long): State {
        currentState = State(
            lat = state[LAT],
            lon = state[LON],
            heading = Math.toDegrees(state[HDG]),
            speed = sqrt(state[VX] * state[VX] + state[VY] * state[VY]),
            vx = state[VX],
            vy = state[VY],
            headingBiasRad = state[H_BIAS],
            scaleFactor = state[SCALE],
            mode = mode,
            confidenceM = confidenceM,
            timestampMs = timestampMs
        )
        return currentState!!
    }

    // ========================================
    // UTILITIES
    // ========================================

    private fun getDt(timestampMs: Long): Double {
        if (lastTimestampMs == 0L) return 0.0
        return (timestampMs - lastTimestampMs) / 1000.0
    }

    private fun wrapAngle(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    fun getDriftFromGps(): Double {
        if (fusedTrack.isEmpty() || gpsTrack.isEmpty()) return 0.0
        // Skip if position is near (0,0) — IMU init artifact
        val lastGps = gpsTrack.last()
        val lastFused = fusedTrack.last()
        if (abs(lastFused.first) < 0.001 && abs(lastFused.second) < 0.001) return 0.0
        if (abs(lastGps.first) < 0.001 && abs(lastGps.second) < 0.001) return 0.0
        return haversine(lastGps.first, lastGps.second, lastFused.first, lastFused.second)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun multiplyMatrices(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size
        val m = B[0].size
        val p = B.size
        val C = Array(n) { DoubleArray(m) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                var sum = 0.0
                for (k in 0 until p) {
                    sum += A[i][k] * B[k][j]
                }
                C[i][j] = sum
            }
        }
        return C
    }

    private fun multiplyTransposed(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val n = A.size
        val m = B.size
        val p = B[0].size
        val C = Array(n) { DoubleArray(p) }
        for (i in 0 until n) {
            for (j in 0 until p) {
                var sum = 0.0
                for (k in 0 until m) {
                    sum += A[i][k] * B[j][k]
                }
                C[i][j] = sum
            }
        }
        return C
    }
}
