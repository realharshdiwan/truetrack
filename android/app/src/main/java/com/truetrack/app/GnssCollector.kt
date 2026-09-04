package com.truetrack.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

class GnssCollector(private val locationManager: LocationManager) {

    companion object {
        private const val TAG = "GnssCollector"
    }

    data class GnssSample(
        val timestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val horizontalAccuracy: Float,
        val verticalAccuracy: Float,
        val speedAccuracy: Float,
        val bearingAccuracy: Float,
        val satellitesUsed: Int,
        val satellitesTotal: Int,
        val gnssAvailable: Boolean,
        val rawMeasurements: List<RawGnssMeasurement> = emptyList()
    )

    data class RawGnssMeasurement(
        val svId: Int,
        val constellationType: Int,
        val state: Int,
        val receivedSvTimeNanos: Long,
        val receivedSvTimeUncertaintyNanos: Long,
        val cn0DbHz: Double,
        val pseudorangeRateMetersPerSecond: Double,
        val pseudorangeRateUncertaintyMetersPerSecond: Double,
        val accumulatedDeltaRangeMeters: Double,
        val accumulatedDeltaRangeState: Int,
        val codeType: String?
    )

    private var listener: ((GnssSample) -> Unit)? = null
    private var satelliteCount = 0
    private var totalSatellites = 0
    private var lastLocation: Location? = null
    private var rawMeasurementCache = java.util.concurrent.CopyOnWriteArrayList<RawGnssMeasurement>()
    private val handler = Handler(Looper.getMainLooper())

    var isActive = false
        private set

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "FIX: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m, speed=${location.speed}m/s")
            lastLocation = location
            emitSample(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            Log.d(TAG, "onStatusChanged: provider=$provider status=$status")
        }
        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Provider enabled: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Provider DISABLED: $provider")
        }
    }

    private val gnssMeasurementsListener = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            rawMeasurementCache.clear()
            for (measurement in event.measurements) {
                rawMeasurementCache.add(measurement.toRaw())
            }
            totalSatellites = event.measurements.size
            Log.d(TAG, "GNSS raw measurements received: ${event.measurements.size} SVs")
        }

        override fun onStatusChanged(status: Int) {
            Log.d(TAG, "GNSS measurements status: $status")
        }
    }

    private val gnssStatusListener = object : GnssStatus.Callback() {
        override fun onStarted() {
            Log.d(TAG, "GNSS engine started")
        }
        override fun onStopped() {
            Log.w(TAG, "GNSS engine stopped")
        }
        override fun onFirstFix(timeToFirstFixMillis: Int) {
            Log.d(TAG, "FIRST FIX in ${timeToFirstFixMillis}ms")
        }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satelliteCount = used
            totalSatellites = status.satelliteCount
            if (totalSatellites > 0) {
                Log.d(TAG, "Satellites visible: $totalSatellites, used in fix: $used")
            }
        }
    }

    fun setListener(listener: (GnssSample) -> Unit) {
        this.listener = listener
    }

    @SuppressLint("MissingPermission")
    fun start() {
        Log.d(TAG, "Starting GNSS collector")

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "GPS provider enabled: $gpsEnabled")
        Log.d(TAG, "Network provider enabled: $networkEnabled")

        if (!gpsEnabled && !networkEnabled) {
            Log.e(TAG, "NO LOCATION PROVIDERS AVAILABLE!")
            return
        }

        try {
            // Register GPS location updates (minTime=0, minDistance=0 for maximum rate)
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,    // minTimeMs - 0 = as fast as possible
                    0f,    // minDistanceM - 0 = any movement
                    locationListener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Registered GPS_PROVIDER updates")
            }

            // Also register Network as fallback
            if (networkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Registered NETWORK_PROVIDER updates")
            }

            // Register GNSS measurements (raw pseudoranges)
            try {
                locationManager.registerGnssMeasurementsCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssMeasurementsListener
                )
                Log.d(TAG, "Registered GNSS measurements callback")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register GNSS measurements: ${e.message}")
            }

            // Register GNSS status (satellite visibility)
            try {
                locationManager.registerGnssStatusCallback(
                    Executors.newSingleThreadExecutor(),
                    gnssStatusListener
                )
                Log.d(TAG, "Registered GNSS status callback")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register GNSS status: ${e.message}")
            }

            // Try to get last known location immediately from any available provider
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                var lastKnown: Location? = null
                for (provider in providers) {
                    try {
                        lastKnown = locationManager.getLastKnownLocation(provider)
                        if (lastKnown != null) {
                            Log.d(TAG, "Last known from $provider: ${lastKnown.latitude}, ${lastKnown.longitude}, age=${System.currentTimeMillis() - lastKnown.time}ms")
                            break
                        }
                    } catch (_: SecurityException) {}
                }
                if (lastKnown != null) {
                    emitSample(lastKnown)
                } else {
                    Log.d(TAG, "No last known location from any provider")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot get last known location: ${e.message}")
            }

            isActive = true
            Log.d(TAG, "GNSS collector started successfully")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting GNSS: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting GNSS: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping GNSS collector")
        locationManager.removeUpdates(locationListener)
        try {
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsListener)
        } catch (_: Exception) {}
        try {
            locationManager.unregisterGnssStatusCallback(gnssStatusListener)
        } catch (_: Exception) {}
        isActive = false
    }

    private fun emitSample(location: Location) {
        val measurements = rawMeasurementCache.toList()
        rawMeasurementCache.clear()

        listener?.invoke(
            GnssSample(
                timestamp = System.currentTimeMillis(),
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                horizontalAccuracy = location.accuracy,
                verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else -1f,
                speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else -1f,
                bearingAccuracy = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else -1f,
                satellitesUsed = satelliteCount,
                satellitesTotal = totalSatellites,
                gnssAvailable = true,
                rawMeasurements = measurements
            )
        )
    }

    private fun GnssMeasurement.toRaw(): RawGnssMeasurement {
        return RawGnssMeasurement(
            svId = svid,
            constellationType = constellationType,
            state = state,
            receivedSvTimeNanos = receivedSvTimeNanos,
            receivedSvTimeUncertaintyNanos = receivedSvTimeUncertaintyNanos,
            cn0DbHz = cn0DbHz,
            pseudorangeRateMetersPerSecond = pseudorangeRateMetersPerSecond,
            pseudorangeRateUncertaintyMetersPerSecond = pseudorangeRateUncertaintyMetersPerSecond,
            accumulatedDeltaRangeMeters = accumulatedDeltaRangeMeters,
            accumulatedDeltaRangeState = accumulatedDeltaRangeState,
            codeType = codeType
        )
    }
}
