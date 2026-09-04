package com.truetrack.app

class RealtimeFusion {

    private val estimator = VehicleStateEstimator()
    private val gpsHealth = GpsHealthMonitor()
    private var simulatedOutage = false

    val gpsTrack get() = estimator.gpsTrack
    val fusedTrack get() = estimator.fusedTrack
    val inertialTrack get() = estimator.inertialTrack

    var lastFusedPosition: VehicleStateEstimator.State? = null
        private set

    var isOutageActive = false
        private set

    fun getPositioningMode(): VehicleStateEstimator.PositioningMode = estimator.mode
    fun getGpsHealthState(): GpsHealthMonitor.GpsState = gpsHealth.getCurrentState()

    fun start() {
        estimator.start()
        gpsHealth.reset()
        simulatedOutage = false
        isOutageActive = false
        lastFusedPosition = null
    }

    fun setInitialPosition(lat: Double, lon: Double) {
        estimator.setInitialPosition(lat, lon)
    }

    fun startOutage() {
        simulatedOutage = true
        isOutageActive = true
        estimator.startOutage()
    }

    fun endOutage() {
        simulatedOutage = false
        isOutageActive = false
        estimator.endOutage()
    }

    fun processGps(
        lat: Double, lon: Double, speed: Double, bearing: Double,
        accuracyM: Float = 10f, satellitesUsed: Int = 0,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (simulatedOutage) {
            isOutageActive = true
            return
        }

        val health = gpsHealth.update(lat, lon, speed.toFloat(), bearing.toFloat(), accuracyM, satellitesUsed, timestampMs)
        GpsHealthCallback.onGpsHealthChange?.invoke(health)

        if (health.state == GpsHealthMonitor.GpsState.UNAVAILABLE) {
            isOutageActive = true
            estimator.startOutage()
        } else {
            if (isOutageActive) {
                isOutageActive = false
                estimator.endOutage()
            }
        }

        lastFusedPosition = estimator.processGps(lat, lon, speed, bearing, accuracyM, timestampMs)
    }

    fun processImu(
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
        timestampMs: Long,
        linearAccel: FloatArray? = null,
        gameRotation: FloatArray? = null,
        magnetometer: FloatArray? = null,
        rotationVector: FloatArray? = null
    ) {
        lastFusedPosition = estimator.processImu(
            ax, ay, az, gx, gy, gz, timestampMs,
            linearAccel, gameRotation, magnetometer, rotationVector
        )
    }

    fun getDriftFromGps(): Double = estimator.getDriftFromGps()

    fun getGpsStateString(): String = gpsHealth.getStateString()
    fun getGpsStateColor(): Int = gpsHealth.getStateColor()

    fun getPositioningModeString(): String = when (estimator.mode) {
        VehicleStateEstimator.PositioningMode.GPS_ONLY -> "GPS Only"
        VehicleStateEstimator.PositioningMode.GPS_IMU_FUSED -> "GPS + IMU"
        VehicleStateEstimator.PositioningMode.INERTIAL_ONLY -> "Inertial Nav"
        VehicleStateEstimator.PositioningMode.IDLE -> "Idle"
    }

    fun getConfidenceM(): Double {
        val c = estimator.confidenceM
        return if (c > 9999 || !c.isFinite()) 0.0 else c
    }
}
