package com.truetrack.app

object SensorCallback {
    var onSensorUpdate: ((accelerometer: FloatArray, gyroscope: FloatArray, magnetometer: FloatArray) -> Unit)? = null
    var onImuData: ((ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double, timestampMs: Long, linearAccel: FloatArray?, gameRotation: FloatArray?, magnetometer: FloatArray?, rotationVector: FloatArray?) -> Unit)? = null
}

object GnssCallback {
    var onGnssMeasurement: ((measurement: GnssCollector.GnssSample) -> Unit)? = null
    var onLocationUpdate: ((location: android.location.Location) -> Unit)? = null
}

object SensorCollectorCallback {
    var onSensorInfo: ((sensors: List<Pair<String, Boolean>>) -> Unit)? = null
    var onImuSample: ((sample: SensorCollector.ImuSample) -> Unit)? = null
}

object GnssCollectorCallback {
    var onGnssSample: ((sample: GnssCollector.GnssSample) -> Unit)? = null
}

object NavigationCallback {
    var onRouteUpdate: ((NavigationManager.Route) -> Unit)? = null
    var onNavigationUpdate: ((NavigationManager.NavigationState) -> Unit)? = null
}

object GpsHealthCallback {
    var onGpsHealthChange: ((health: GpsHealthMonitor.GpsHealth) -> Unit)? = null
}
