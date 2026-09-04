package com.truetrack.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

class NavigationManager {

    companion object {
        private const val TAG = "Navigation"
        private const val ROUTING_URL = "https://router.project-osrm.org/route/v1/driving"
    }

    data class RoutePoint(
        val latitude: Double,
        val longitude: Double,
        val name: String = ""
    )

    data class Route(
        val points: List<GeoPoint>,
        val distanceM: Double,
        val durationS: Double,
        val source: RoutePoint,
        val destination: RoutePoint
    )

    data class NavigationState(
        val isActive: Boolean,
        val route: Route?,
        val currentStepIndex: Int,
        val distanceToNextM: Double,
        val distanceRemainingM: Double,
        val durationRemainingS: Double,
        val instruction: String
    )

    private var currentRoute: Route? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var nearestPointIndex = 0
    private var _isActive = false
    var sourceName: String? = null
        private set
    var destinationName: String? = null
        private set

    val isActive: Boolean get() = _isActive
    val currentRoutePoints: List<GeoPoint> get() = routePoints
    fun getRoute(): Route? = currentRoute

    fun setRoute(route: Route) {
        currentRoute = route
        routePoints = route.points
        sourceName = route.source.name.ifEmpty { "Current location" }
        destinationName = route.destination.name.ifEmpty { "Destination" }
        nearestPointIndex = 0
        _isActive = true
        Log.d(TAG, "Route set: ${route.distanceM.toInt()}m, ${route.durationS.toInt()}s, ${routePoints.size} points")
    }

    fun clearRoute() {
        currentRoute = null
        routePoints = emptyList()
        sourceName = null
        destinationName = null
        nearestPointIndex = 0
        _isActive = false
        Log.d(TAG, "Route cleared")
    }

    fun getRoutePoints(): List<GeoPoint> = routePoints

    fun updatePosition(lat: Double, lon: Double): NavigationState {
        if (!_isActive || routePoints.isEmpty()) {
            return NavigationState(false, null, 0, 0.0, 0.0, 0.0, "")
        }

        // Find nearest point on route
        var minDist = Double.MAX_VALUE
        var nearestIdx = nearestPointIndex

        // Search within a window of the current position
        val searchStart = maxOf(0, nearestPointIndex - 5)
        val searchEnd = minOf(routePoints.size - 1, nearestPointIndex + 50)

        for (i in searchStart..searchEnd) {
            val dist = haversine(lat, lon, routePoints[i].latitude, routePoints[i].longitude)
            if (dist < minDist) {
                minDist = dist
                nearestIdx = i
            }
        }

        nearestPointIndex = nearestIdx

        // Calculate remaining distance
        var remainingM = minDist
        for (i in nearestPointIndex until routePoints.size - 1) {
            remainingM += haversine(
                routePoints[i].latitude, routePoints[i].longitude,
                routePoints[i + 1].latitude, routePoints[i + 1].longitude
            )
        }

        // Distance to next waypoint
        val distToNext = if (nearestPointIndex < routePoints.size - 1) {
            haversine(lat, lon, routePoints[nearestPointIndex + 1].latitude, routePoints[nearestPointIndex + 1].longitude)
        } else {
            0.0
        }

        // Duration remaining
        val route = currentRoute!!
        val speedRatio = if (route.durationS > 0) remainingM / route.distanceM else 1.0
        val durationRemaining = route.durationS * speedRatio

        // Check if arrived (within 30m of destination)
        val destPoint = routePoints.last()
        val distToDest = haversine(lat, lon, destPoint.latitude, destPoint.longitude)
        if (distToDest < 30) {
            _isActive = false
            return NavigationState(
                isActive = false,
                route = currentRoute,
                currentStepIndex = routePoints.size - 1,
                distanceToNextM = 0.0,
                distanceRemainingM = 0.0,
                durationRemainingS = 0.0,
                instruction = "You have arrived"
            )
        }

        // Simple instruction based on heading change
        val instruction = generateInstruction(lat, lon, nearestPointIndex)

        return NavigationState(
            isActive = true,
            route = currentRoute,
            currentStepIndex = nearestPointIndex,
            distanceToNextM = distToNext,
            distanceRemainingM = remainingM,
            durationRemainingS = durationRemaining,
            instruction = instruction
        )
    }

    private fun generateInstruction(lat: Double, lon: Double, currentIdx: Int): String {
        if (currentIdx >= routePoints.size - 2) return "Continue to destination"

        val current = routePoints[currentIdx]
        val next = routePoints[minOf(currentIdx + 5, routePoints.size - 1)]

        val bearing = bearingTo(current.latitude, current.longitude, next.latitude, next.longitude)

        return when {
            bearing < 22.5 || bearing >= 337.5 -> "Head north"
            bearing < 67.5 -> "Head northeast"
            bearing < 112.5 -> "Head east"
            bearing < 157.5 -> "Head southeast"
            bearing < 202.5 -> "Head south"
            bearing < 247.5 -> "Head southwest"
            bearing < 292.5 -> "Head west"
            else -> "Head northwest"
        }
    }

    fun getBoundingBox(): BoundingBox? {
        if (routePoints.isEmpty()) return null
        return BoundingBox.fromGeoPoints(routePoints)
    }

    fun searchDestination(query: String, callback: (List<RoutePoint>) -> Unit) {
        Thread {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=${encodedQuery}&format=json&limit=5"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "TrueTrack/0.1")

                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val arr = JSONArray(response)
                val results = mutableListOf<RoutePoint>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    results.add(RoutePoint(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        name = obj.getString("display_name").take(100)
                    ))
                }

                callback(results)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
                callback(emptyList())
            }
        }.start()
    }

    fun fetchRoute(
        source: GeoPoint, destination: GeoPoint,
        callback: (Route?) -> Unit
    ) {
        Thread {
            try {
                val urlStr = "$ROUTING_URL/${source.longitude},${source.latitude};${destination.longitude},${destination.latitude}?geometries=geojson&overview=full"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "TrueTrack/0.1")

                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)

                if (json.getString("code") == "Ok") {
                    val route = json.getJSONArray("routes").getJSONObject(0)
                    val distance = route.getDouble("distance")
                    val duration = route.getDouble("duration")

                    // Decode geometry
                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    val points = mutableListOf<GeoPoint>()
                    for (i in 0 until coords.length()) {
                        val coord = coords.getJSONArray(i)
                        points.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                    }

                    val result = Route(
                        points = points,
                        distanceM = distance,
                        durationS = duration,
                        source = RoutePoint(source.latitude, source.longitude, "Current location"),
                        destination = RoutePoint(destination.latitude, destination.longitude)
                    )

                    callback(result)
                } else {
                    Log.e(TAG, "Routing failed: ${json.getString("code")}")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route fetch failed: ${e.message}")
                callback(null)
            }
        }.start()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1R = Math.toRadians(lat1)
        val lat2R = Math.toRadians(lat2)
        val y = sin(dLon) * cos(lat2R)
        val x = cos(lat1R) * sin(lat2R) - sin(lat1R) * cos(lat2R) * cos(dLon)
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360
        return bearing
    }
}
