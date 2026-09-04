package com.truetrack.app

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GpsHealthMonitor {

    companion object {
        private const val TAG = "GpsHealth"
    }

    enum class GpsState {
        GOOD,
        DEGRADED,
        UNAVAILABLE
    }

    data class GpsHealth(
        val state: GpsState,
        val accuracyM: Float,
        val satellitesUsed: Int,
        val secondsSinceLastFix: Long,
        val reason: String
    )

    data class Thresholds(
        val goodMaxAccuracyM: Float = 15f,
        val degradedMaxAccuracyM: Float = 50f,
        val maxSecondsSinceFix: Long = 3,
        val minSatellitesForGood: Int = 5,
        val minSatellitesForDegraded: Int = 3,
        val maxSpeedJumpMps: Float = 15f,
        val maxPositionJumpM: Float = 100f
    )

    var thresholds = Thresholds()
        set(value) {
            field = value
            Log.d(TAG, "Thresholds updated: goodAcc=${value.goodMaxAccuracyM}m, degradedAcc=${value.degradedMaxAccuracyM}m, maxGap=${value.maxSecondsSinceFix}s")
        }

    private var lastFixTimeMs = 0L
    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var lastFixSpeed = 0f
    private var lastFixAccuracy = 0f
    private var fixCount = 0
    private var currentState = GpsState.UNAVAILABLE
    private var stateChangeTimeMs = 0L

    val stateHistory = mutableListOf<Pair<Long, GpsState>>()

    fun reset() {
        lastFixTimeMs = 0L
        lastFixLat = 0.0
        lastFixLon = 0.0
        lastFixSpeed = 0f
        lastFixAccuracy = 0f
        fixCount = 0
        currentState = GpsState.UNAVAILABLE
        stateChangeTimeMs = System.currentTimeMillis()
        stateHistory.clear()
    }

    fun update(
        latitude: Double,
        longitude: Double,
        speed: Float,
        bearing: Float,
        accuracy: Float,
        satellitesUsed: Int,
        timestampMs: Long
    ): GpsHealth {
        val now = System.currentTimeMillis()
        val secondsSinceLastFix = if (lastFixTimeMs > 0) (now - lastFixTimeMs) / 1000 else Long.MAX_VALUE

        var reason = ""
        var newState = GpsState.GOOD

        // Check 1: Is fix available at all?
        if (latitude == 0.0 && longitude == 0.0) {
            newState = GpsState.UNAVAILABLE
            reason = "No valid fix"
        }
        // Check 2: Time since last fix (skip on first fix)
        else if (lastFixTimeMs > 0 && secondsSinceLastFix > thresholds.maxSecondsSinceFix) {
            newState = GpsState.UNAVAILABLE
            reason = "No fix for ${secondsSinceLastFix}s"
        }
        // Check 3: Accuracy
        else if (accuracy > thresholds.degradedMaxAccuracyM) {
            newState = GpsState.UNAVAILABLE
            reason = "Accuracy ${accuracy.toInt()}m exceeds max ${thresholds.degradedMaxAccuracyM.toInt()}m"
        }
        else if (accuracy > thresholds.goodMaxAccuracyM) {
            newState = GpsState.DEGRADED
            reason = "Accuracy ${accuracy.toInt()}m (degraded)"
        }
        // Check 4: Satellite count
        else if (satellitesUsed < thresholds.minSatellitesForDegraded) {
            newState = GpsState.DEGRADED
            reason = "Only $satellitesUsed satellites (need ${thresholds.minSatellitesForDegraded}+)"
        }
        else if (satellitesUsed < thresholds.minSatellitesForGood) {
            newState = GpsState.DEGRADED
            reason = "$satellitesUsed satellites (need ${thresholds.minSatellitesForGood}+ for good)"
        }

        // Check 5: Position jump detection (only if we have a previous fix)
        if (newState == GpsState.GOOD && lastFixTimeMs > 0) {
            val jumpM = haversine(lastFixLat, lastFixLon, latitude, longitude)
            if (jumpM > thresholds.maxPositionJumpM) {
                newState = GpsState.DEGRADED
                reason = "Position jump ${jumpM.toInt()}m"
            }
        }

        // Check 6: Speed jump detection
        if (newState == GpsState.GOOD && lastFixTimeMs > 0) {
            val speedJump = abs(speed - lastFixSpeed)
            if (speedJump > thresholds.maxSpeedJumpMps && secondsSinceLastFix < 5) {
                newState = GpsState.DEGRADED
                reason = "Speed jump ${speedJump.toInt()} m/s"
            }
        }

        // Update state history
        if (newState != currentState) {
            currentState = newState
            stateChangeTimeMs = now
            stateHistory.add(Pair(now, newState))
            Log.d(TAG, "State -> $newState: $reason")
        }

        // Update tracking values
        lastFixTimeMs = now
        lastFixLat = latitude
        lastFixLon = longitude
        lastFixSpeed = speed
        lastFixAccuracy = accuracy
        fixCount++

        return GpsHealth(
            state = currentState,
            accuracyM = accuracy,
            satellitesUsed = satellitesUsed,
            secondsSinceLastFix = secondsSinceLastFix,
            reason = reason.ifEmpty { "OK" }
        )
    }

    fun getCurrentState(): GpsState = currentState

    fun getStateString(): String = when (currentState) {
        GpsState.GOOD -> "Good"
        GpsState.DEGRADED -> "Degraded"
        GpsState.UNAVAILABLE -> "Unavailable"
    }

    fun getStateColor(): Int = when (currentState) {
        GpsState.GOOD -> 0xFF4CAF50.toInt()
        GpsState.DEGRADED -> 0xFFFFC107.toInt()
        GpsState.UNAVAILABLE -> 0xFFE53935.toInt()
    }

    fun getOutageDurationMs(): Long {
        if (currentState != GpsState.UNAVAILABLE) return 0
        return System.currentTimeMillis() - stateChangeTimeMs
    }

    fun isInertialNavigationActive(): Boolean = currentState == GpsState.UNAVAILABLE

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
