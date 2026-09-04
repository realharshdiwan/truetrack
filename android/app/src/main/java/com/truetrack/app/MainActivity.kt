package com.truetrack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.MapEventsOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var gpsStatusText: TextView
    private lateinit var sensorStatusText: TextView
    private lateinit var fusionStatusText: TextView
    private lateinit var navigationText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var simulateOutageButton: Button
    private lateinit var debugToggle: Button
    private lateinit var debugPanel: LinearLayout
    private lateinit var searchInput: AutoCompleteTextView
    private lateinit var waypointText: TextView
    private lateinit var historyButton: Button
    private lateinit var clearRouteButton: Button
    private lateinit var recenterButton: Button

    private val fusion = RealtimeFusion()
    private val navManager = NavigationManager()
    private lateinit var tripRecorder: TripRecorder

    private var isRecording = false
    private var isDebugMode = false
    private var isOutageActive = false
    private var hasGpsFix = false

    private val gpsTrack = mutableListOf<GeoPoint>()
    private val fusedTrack = mutableListOf<GeoPoint>()
    private val inertialTrack = mutableListOf<GeoPoint>()

    private var vehicleMarker: Marker? = null
    private var sourceMarker: Marker? = null
    private var destMarker: Marker? = null
    private var gpsPolyline: Polyline? = null
    private var fusedPolyline: Polyline? = null
    private var inertialPolyline: Polyline? = null
    private var routePolyline: Polyline? = null

    private var lastSearchQuery = ""
    private var currentSource: GeoPoint? = null
    private var currentDestination: GeoPoint? = null
    private var searchResults = listOf<NavigationManager.RoutePoint>()

    private val handler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { performSearch(lastSearchQuery) }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateTrackDisplay()
                updateVehicleMarker()
                updateStatusDisplay()
                handler.postDelayed(this, 500)
            }
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        tripRecorder = TripRecorder(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
        rootLayout.addView(map, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#F0F0F0"))
        }

        searchInput = AutoCompleteTextView(this).apply {
            hint = "Search destination..."
            setPadding(16, 12, 16, 12)
            textSize = 14f
            threshold = 2
            setDropDownBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        controlPanel.addView(searchInput)

        val searchWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 3 && query != lastSearchQuery) {
                    lastSearchQuery = query
                    handler.removeCallbacks(searchDebounceRunnable)
                    handler.postDelayed(searchDebounceRunnable, 300)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        searchInput.addTextChangedListener(searchWatcher)

        searchInput.setOnItemClickListener { _, _, position, _ ->
            if (position < searchResults.size) {
                val result = searchResults[position]
                setDestination(GeoPoint(result.latitude, result.longitude), result.name)
                searchInput.setText(result.name)
                searchInput.dismissDropDown()
            }
        }

        waypointText = TextView(this).apply {
            textSize = 12f
            text = "Source: Waiting for GPS... | Dest: None"
            setPadding(16, 6, 16, 6)
            setTextColor(Color.DKGRAY)
        }
        controlPanel.addView(waypointText)

        gpsStatusText = TextView(this).apply {
            textSize = 12f
            text = "GPS: Waiting..."
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 2)
        }
        controlPanel.addView(gpsStatusText)

        sensorStatusText = TextView(this).apply {
            textSize = 12f
            text = "IMU: Waiting..."
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 2)
        }
        controlPanel.addView(sensorStatusText)

        fusionStatusText = TextView(this).apply {
            textSize = 12f
            text = "Fusion: Idle"
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 2)
        }
        controlPanel.addView(fusionStatusText)

        navigationText = TextView(this).apply {
            textSize = 12f
            text = "Nav: No route"
            setTextColor(Color.GRAY)
            setPadding(0, 2, 0, 2)
        }
        controlPanel.addView(navigationText)

        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            gravity = Gravity.CENTER
        }

        startButton = Button(this).apply {
            text = "Start"
            setOnClickListener { startRecording() }
        }
        buttonRow1.addView(startButton)

        stopButton = Button(this).apply {
            text = "Stop"
            setTextColor(Color.RED)
            setOnClickListener { stopRecording() }
            isEnabled = false
        }
        buttonRow1.addView(stopButton)

        simulateOutageButton = Button(this).apply {
            text = "Outage"
            setOnClickListener { toggleOutage() }
            isEnabled = false
        }
        buttonRow1.addView(simulateOutageButton)

        controlPanel.addView(buttonRow1)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            gravity = Gravity.CENTER
        }

        clearRouteButton = Button(this).apply {
            text = "Clear Route"
            setTextColor(Color.parseColor("#FF5722"))
            setOnClickListener { clearRoute() }
            visibility = android.view.View.GONE
        }
        buttonRow2.addView(clearRouteButton)

        recenterButton = Button(this).apply {
            text = "Re-center"
            setOnClickListener { recenterOnCurrentPosition() }
        }
        buttonRow2.addView(recenterButton)

        historyButton = Button(this).apply {
            text = "History"
            setOnClickListener { openTripHistory() }
        }
        buttonRow2.addView(historyButton)

        debugToggle = Button(this).apply {
            text = "Debug"
            setTextColor(Color.MAGENTA)
            setOnClickListener { toggleDebugMode() }
        }
        buttonRow2.addView(debugToggle)

        controlPanel.addView(buttonRow2)

        rootLayout.addView(controlPanel)

        debugPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#F5F0FF"))
            visibility = android.view.View.GONE
        }
        rootLayout.addView(debugPanel)

        setContentView(rootLayout)

        requestPermissions()
        setupMap()
        setupGpsCallbacks()
        setupSensorCallbacks()
        setupImuCallbacks()
    }

    private fun setupMap() {
        try {
            val compassOverlay = CompassOverlay(this, map)
            compassOverlay.enableCompass()
            map.overlays.add(compassOverlay)

            val rotationOverlay = RotationGestureOverlay(map)
            rotationOverlay.isEnabled = true
            map.overlays.add(rotationOverlay)

            val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    return false
                }

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null && isRecording) {
                        setDestination(p, "Dropped pin")
                        return true
                    }
                    return false
                }
            })
            map.overlays.add(0, mapEventsOverlay)
        } catch (e: Exception) {
            android.util.Log.e("TrueTrack", "Error setting up map overlays", e)
        }
    }

    private fun setDestination(point: GeoPoint, name: String) {
        currentDestination = point
        navManager.clearRoute()
        removeRoutePolyline()

        if (destMarker != null) {
            map.overlays.remove(destMarker)
        }
        destMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
            snippet = "Destination"
        }
        map.overlays.add(destMarker)
        map.invalidate()

        updateWaypointText()

        if (currentSource != null) {
            fetchRoute()
        }
    }

    private fun fetchRoute() {
        val source = currentSource ?: return
        val dest = currentDestination ?: return

        navigationText.text = "Nav: Fetching route..."
        navigationText.setTextColor(Color.parseColor("#FF9800"))

        navManager.fetchRoute(source, dest) { route ->
            handler.post {
                if (route != null) {
                    navManager.setRoute(route)
                    drawRoute(route)
                    navigationText.text = "Nav: ${formatDistance(route.distanceM)} | ${formatDuration(route.durationS)}"
                    navigationText.setTextColor(Color.parseColor("#4285F4"))
                    clearRouteButton.visibility = android.view.View.VISIBLE
                } else {
                    navigationText.text = "Nav: Route failed"
                    navigationText.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun drawRoute(route: NavigationManager.Route) {
        if (routePolyline != null) {
            map.overlays.remove(routePolyline)
        }
        routePolyline = Polyline().apply {
            setPoints(route.points)
            outlinePaint.color = Color.parseColor("#4285F4")
            outlinePaint.strokeWidth = 12f
        }
        map.overlays.add(routePolyline)
        map.invalidate()

        if (route.points.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(route.points)
            map.zoomToBoundingBox(boundingBox, true, 100)
        }
    }

    private fun removeRoutePolyline() {
        if (routePolyline != null) {
            map.overlays.remove(routePolyline)
            routePolyline = null
        }
        map.invalidate()
    }

    private fun clearRoute() {
        navManager.clearRoute()
        removeRoutePolyline()
        currentDestination = null
        if (destMarker != null) {
            map.overlays.remove(destMarker)
            destMarker = null
        }
        clearRouteButton.visibility = android.view.View.GONE
        navigationText.text = "Nav: No route"
        navigationText.setTextColor(Color.GRAY)
        updateWaypointText()
    }

    private fun recenterOnCurrentPosition() {
        val pos = fusion.lastFusedPosition
        if (pos != null) {
            map.controller.animateTo(GeoPoint(pos.lat, pos.lon))
        } else if (gpsTrack.isNotEmpty()) {
            map.controller.animateTo(gpsTrack.last())
        }
    }

    private fun performSearch(query: String) {
        if (query.length < 3) return

        navManager.searchDestination(query) { results ->
            searchResults = results
            handler.post {
                val names = results.map { it.name }
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
                searchInput.setAdapter(adapter)
                if (results.isNotEmpty()) {
                    searchInput.showDropDown()
                }
            }
        }
    }

    private fun setupGpsCallbacks() {
        GnssCallback.onGnssMeasurement = { measurement: GnssCollector.GnssSample ->
            val stateString = fusion.getGpsStateString()
            val color = fusion.getGpsStateColor()
            val acc = measurement.horizontalAccuracy
            val sats = measurement.satellitesUsed

            handler.post {
                gpsStatusText.text = "GPS: $stateString | $sats sats | ${acc}m"
                gpsStatusText.setTextColor(color)
            }
        }

        GnssCallback.onLocationUpdate = { location ->
            if (!hasGpsFix) {
                hasGpsFix = true
                map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }

            val geoPoint = GeoPoint(location.latitude, location.longitude)
            gpsTrack.add(geoPoint)

            if (currentSource == null) {
                currentSource = geoPoint
                if (sourceMarker != null) {
                    map.overlays.remove(sourceMarker)
                }
                sourceMarker = Marker(map).apply {
                    position = geoPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Source"
                    snippet = "Current location"
                }
                map.overlays.add(sourceMarker)
                map.invalidate()
                updateWaypointText()
            }

            if (isRecording) {
                fusion.processGps(
                    location.latitude, location.longitude,
                    location.speed.toDouble(), location.bearing.toDouble(),
                    location.accuracy, 0, location.time
                )
            }
        }
    }

    private fun setupSensorCallbacks() {
        SensorCallback.onSensorUpdate = { accelerometer, gyroscope, _ ->
            val accelRms = Math.sqrt(
                (accelerometer[0] * accelerometer[0] +
                 accelerometer[1] * accelerometer[1] +
                 accelerometer[2] * accelerometer[2]).toDouble()
            )
            val gyroRms = Math.sqrt(
                (gyroscope[0] * gyroscope[0] +
                 gyroscope[1] * gyroscope[1] +
                 gyroscope[2] * gyroscope[2]).toDouble()
            )

            handler.post {
                sensorStatusText.text = "IMU: a=${String.format("%.2f", accelRms)} g | g=${String.format("%.2f", gyroRms)} dps"
            }
        }
    }

    private fun setupImuCallbacks() {
        SensorCallback.onImuData = { ax, ay, az, gx, gy, gz, ts, linearAccel, gameRotation, magnetometer ->
            if (isRecording) {
                fusion.processImu(ax, ay, az, gx, gy, gz, ts, linearAccel, gameRotation, magnetometer)

                if (isDebugMode) {
                    handler.post {
                        updateDebugPanel(ax, ay, az, gx, gy, gz, ts, linearAccel, gameRotation, magnetometer)
                    }
                }
            }
        }
    }

    private fun updateDebugPanel(
        ax: Double, ay: Double, az: Double,
        gx: Double, gy: Double, gz: Double,
        ts: Long, linearAccel: FloatArray?, gameRotation: FloatArray?, magnetometer: FloatArray?
    ) {
        debugPanel.removeAllViews()

        val title = TextView(this).apply {
            textSize = 12f
            text = "=== ENGINE DEBUG ==="
            setTextColor(Color.MAGENTA)
        }
        debugPanel.addView(title)

        if (linearAccel != null && linearAccel.size >= 3) {
            debugPanel.addView(TextView(this).apply {
                textSize = 11f
                text = "LinAccel: ${String.format("%.3f", linearAccel[0])}, ${String.format("%.3f", linearAccel[1])}, ${String.format("%.3f", linearAccel[2])}"
                setTextColor(Color.parseColor("#2196F3"))
            })
        }

        debugPanel.addView(TextView(this).apply {
            textSize = 11f
            text = "Gyro Z: ${String.format("%.4f", gz)} rad/s"
        })

        if (magnetometer != null && magnetometer.size >= 3) {
            debugPanel.addView(TextView(this).apply {
                textSize = 11f
                text = "Mag: ${String.format("%.1f", magnetometer[0])}, ${String.format("%.1f", magnetometer[1])}, ${String.format("%.1f", magnetometer[2])} μT"
            })
        }

        debugPanel.addView(TextView(this).apply {
            textSize = 11f
            text = "Mode: ${fusion.getPositioningModeString()}"
        })

        val state = fusion.lastFusedPosition
        if (state != null) {
            debugPanel.addView(TextView(this).apply {
                textSize = 11f
                text = "Hdg: ${String.format("%.1f", state.heading)}° | Bias: ${String.format("%.4f", state.headingBiasRad)} rad"
            })

            debugPanel.addView(TextView(this).apply {
                textSize = 11f
                text = "Scale: ${String.format("%.3f", state.scaleFactor)} | Speed: ${String.format("%.1f", state.speed * 3.6)} km/h"
            })

            debugPanel.addView(TextView(this).apply {
                textSize = 11f
                text = "Pos: ${String.format("%.6f", state.lat)}, ${String.format("%.6f", state.lon)}"
            })
        }

        debugPanel.addView(TextView(this).apply {
            textSize = 11f
            val conf = fusion.getConfidenceM()
            val confStr = if (conf > 9999 || !conf.isFinite()) "--" else String.format("%.1f", conf)
            text = "Drift: ${String.format("%.1f", fusion.getDriftFromGps())}m | Conf: ${confStr}m"
        })

        debugPanel.addView(TextView(this).apply {
            textSize = 11f
            text = "GPS: ${fusion.getGpsStateString()}"
        })
    }

    private fun startRecording() {
        isRecording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        simulateOutageButton.isEnabled = true

        fusion.start()
        tripRecorder.startTrip()

        val serviceIntent = Intent(this, SensorLoggerService::class.java).apply {
            action = SensorLoggerService.ACTION_START
        }
        startForegroundService(serviceIntent)

        updateVehicleMarker()
        handler.postDelayed(updateRunnable, 500)
    }

    private fun stopRecording() {
        isRecording = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        simulateOutageButton.isEnabled = false

        val stopIntent = Intent(this, SensorLoggerService::class.java).apply {
            action = SensorLoggerService.ACTION_STOP
        }
        startService(stopIntent)
        handler.removeCallbacks(updateRunnable)

        tripRecorder.stopTrip()
        tripRecorder.exportSession(
            gpsTrack = gpsTrack.map { it.latitude to it.longitude },
            fusedTrack = fusedTrack.map { it.latitude to it.longitude },
            inertialTrack = inertialTrack.map { it.latitude to it.longitude },
            outages = emptyList()
        )
    }

    private fun toggleOutage() {
        if (isOutageActive) {
            isOutageActive = false
            fusion.endOutage()
            simulateOutageButton.text = "Outage"
        } else {
            fusion.startOutage()
            isOutageActive = true
            simulateOutageButton.text = "Resume"
        }
    }

    private fun toggleDebugMode() {
        isDebugMode = !isDebugMode
        debugToggle.text = if (isDebugMode) "Debug ON" else "Debug"
        debugPanel.visibility = if (isDebugMode) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateTrackDisplay() {
        map.overlays.removeAll { it is Polyline && it != routePolyline }

        if (gpsTrack.size > 1) {
            gpsPolyline = Polyline().apply {
                setPoints(gpsTrack)
                outlinePaint.color = Color.BLUE
                outlinePaint.strokeWidth = 8f
            }
            map.overlays.add(gpsPolyline)
        }

        if (fusedTrack.size > 1) {
            fusedPolyline = Polyline().apply {
                setPoints(fusedTrack)
                outlinePaint.color = Color.GREEN
                outlinePaint.strokeWidth = 6f
            }
            map.overlays.add(fusedPolyline)
        }

        if (inertialTrack.size > 1) {
            inertialPolyline = Polyline().apply {
                setPoints(inertialTrack)
                outlinePaint.color = Color.RED
                outlinePaint.strokeWidth = 6f
            }
            map.overlays.add(inertialPolyline)
        }

        map.invalidate()
    }

    private fun updateVehicleMarker() {
        val pos = fusion.lastFusedPosition
        val posMode = fusion.getPositioningMode()

        if (pos != null && posMode != VehicleStateEstimator.PositioningMode.IDLE) {
            val geoPoint = GeoPoint(pos.lat, pos.lon)

            if (vehicleMarker == null) {
                vehicleMarker = Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
                }
                map.overlays.add(vehicleMarker)
                map.controller.animateTo(geoPoint)
            }

            vehicleMarker?.position = geoPoint
            vehicleMarker?.title = "Vehicle"
            vehicleMarker?.snippet = fusion.getPositioningModeString()
        }
    }

    private fun updateStatusDisplay() {
        val mode = fusion.getPositioningMode()
        val drift = fusion.getDriftFromGps()
        val confidence = fusion.getConfidenceM()

        val modeColor = when (mode) {
            VehicleStateEstimator.PositioningMode.GPS_ONLY -> Color.BLUE
            VehicleStateEstimator.PositioningMode.GPS_IMU_FUSED -> Color.GREEN
            VehicleStateEstimator.PositioningMode.INERTIAL_ONLY -> Color.RED
            VehicleStateEstimator.PositioningMode.IDLE -> Color.GRAY
        }

        fusionStatusText.text = "Fusion: ${fusion.getPositioningModeString()} | ${String.format("%.1f", drift)}m drift"
        fusionStatusText.setTextColor(modeColor)

        if (isOutageActive) {
            fusionStatusText.setTextColor(Color.RED)
            fusionStatusText.text = "OUTAGE ACTIVE - Inertial Nav"
        }

        val timeMs = fusion.lastFusedPosition?.timestampMs ?: 0L
        val timeAgo = if (timeMs > 0) (System.currentTimeMillis() - timeMs) / 1000 else 0
        val confidenceStr = if (confidence > 9999 || !confidence.isFinite()) "--" else String.format("%.1f", confidence)
        navigationText.text = "Last fix: ${timeAgo}s ago | Confidence: ${confidenceStr}m"
        navigationText.setTextColor(if (isOutageActive) Color.RED else Color.DKGRAY)

        updateWaypointText()
    }

    private fun updateWaypointText() {
        val srcName = if (currentSource != null) "GPS location" else "Waiting for GPS..."
        val destName = currentDestination?.let {
            navManager.destinationName ?: "Dropped pin"
        } ?: "None"
        waypointText.text = "Source: $srcName | Dest: $destName"
    }

    private fun openTripHistory() {
        val intent = Intent(this, TripHistoryActivity::class.java)
        startActivity(intent)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val filtered = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (filtered.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, filtered, REQUEST_PERMISSIONS)
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format("%.1f km", meters / 1000)
        } else {
            String.format("%.0f m", meters)
        }
    }

    private fun formatDuration(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return if (mins >= 60) {
            val hrs = mins / 60
            val remainMins = mins % 60
            "${hrs}h ${remainMins}m"
        } else {
            "${mins}m ${secs}s"
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        SensorCallback.onSensorUpdate = null
        SensorCallback.onImuData = null
        GnssCallback.onGnssMeasurement = null
        GnssCallback.onLocationUpdate = null
    }
}
