package com.truetrack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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

    // Map
    private lateinit var map: MapView

    // Search
    private lateinit var searchInput: AutoCompleteTextView

    // Positioning status
    private lateinit var positioningPill: LinearLayout
    private lateinit var positioningDot: View
    private lateinit var positioningText: TextView

    // GPS outage warning
    private lateinit var outageWarning: LinearLayout

    // Navigation info card
    private lateinit var navInfoCard: LinearLayout
    private lateinit var navDistance: TextView
    private lateinit var navEta: TextView
    private lateinit var navArrival: TextView
    private lateinit var maneuverRow: LinearLayout
    private lateinit var maneuverIcon: TextView
    private lateinit var maneuverText: TextView
    private lateinit var navStatusBar: LinearLayout
    private lateinit var navStatusDot: View
    private lateinit var navStatusText: TextView
    private lateinit var navConfidence: TextView

    // Bottom controls
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var outageButton: Button
    private lateinit var diagnosticsButton: Button

    // Engine
    private val fusion = RealtimeFusion()
    private val navManager = NavigationManager()
    private lateinit var tripRecorder: TripRecorder

    // State
    private var isRecording = false
    private var isOutageActive = false
    private var hasGpsFix = false
    private var hasGpsEverWorked = false

    // Track points
    private val gpsTrack = mutableListOf<GeoPoint>()
    private val fusedTrack = mutableListOf<GeoPoint>()
    private val inertialTrack = mutableListOf<GeoPoint>()

    // Map overlays
    private var vehicleMarker: Marker? = null
    private var sourceMarker: Marker? = null
    private var destMarker: Marker? = null
    private var gpsPolyline: Polyline? = null
    private var fusedPolyline: Polyline? = null
    private var inertialPolyline: Polyline? = null
    private var routePolyline: Polyline? = null

    // Search
    private var lastSearchQuery = ""
    private var currentSource: GeoPoint? = null
    private var currentDestination: GeoPoint? = null
    private var searchResults = listOf<NavigationManager.RoutePoint>()

    // Handler
    private val handler = Handler(Looper.getMainLooper())
    private val searchDebounceRunnable = Runnable { performSearch(lastSearchQuery) }
    private var lastMarkerUpdateMs = 0L
    private val MARKER_UPDATE_INTERVAL_MS = 100L  // 10Hz for smooth marker movement

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateTrackDisplay()
                handler.postDelayed(this, 1000)
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

        setContentView(R.layout.activity_main)

        // Bind views
        map = findViewById(R.id.map)
        searchInput = findViewById(R.id.searchInput)
        positioningPill = findViewById(R.id.positioningPill)
        positioningDot = findViewById(R.id.positioningDot)
        positioningText = findViewById(R.id.positioningText)
        outageWarning = findViewById(R.id.outageWarning)
        navInfoCard = findViewById(R.id.navInfoCard)
        navDistance = findViewById(R.id.navDistance)
        navEta = findViewById(R.id.navEta)
        navArrival = findViewById(R.id.navArrival)
        maneuverRow = findViewById(R.id.maneuverRow)
        maneuverIcon = findViewById(R.id.maneuverIcon)
        maneuverText = findViewById(R.id.maneuverText)
        navStatusBar = findViewById(R.id.navStatusBar)
        navStatusDot = findViewById(R.id.navStatusDot)
        navStatusText = findViewById(R.id.navStatusText)
        navConfidence = findViewById(R.id.navConfidence)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        outageButton = findViewById(R.id.outageButton)
        diagnosticsButton = findViewById(R.id.diagnosticsButton)

        // Wire up
        setupMap()
        setupSearch()
        setupButtons()
        setupGpsCallbacks()
        setupSensorCallbacks()
        setupImuCallbacks()
        requestPermissions()
    }

    // ========================================
    // MAP
    // ========================================

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)

        val compassOverlay = CompassOverlay(this, map)
        compassOverlay.enableCompass()
        map.overlays.add(compassOverlay)

        val rotationOverlay = RotationGestureOverlay(map)
        rotationOverlay.isEnabled = true
        map.overlays.add(rotationOverlay)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null && isRecording) {
                    setDestination(p, "Dropped pin")
                    return true
                }
                return false
            }
        })
        map.overlays.add(0, mapEventsOverlay)
    }

    // ========================================
    // SEARCH
    // ========================================

    private fun setupSearch() {
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
    }

    private fun performSearch(query: String) {
        if (query.length < 3) return
        navManager.searchDestination(query) { results ->
            searchResults = results
            handler.post {
                val names = results.map { it.name }
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
                searchInput.setAdapter(adapter)
                if (results.isNotEmpty()) searchInput.showDropDown()
            }
        }
    }

    // ========================================
    // DESTINATION & ROUTING
    // ========================================

    private fun setDestination(point: GeoPoint, name: String) {
        currentDestination = point
        navManager.clearRoute()
        removeRoutePolyline()

        if (destMarker != null) map.overlays.remove(destMarker)
        destMarker = Marker(map).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
        }
        map.overlays.add(destMarker)
        map.invalidate()

        if (currentSource != null) fetchRoute()
    }

    private fun fetchRoute() {
        val source = currentSource ?: return
        val dest = currentDestination ?: return

        navInfoCard.visibility = View.VISIBLE
        navDistance.text = "Loading..."
        navEta.text = ""

        navManager.fetchRoute(source, dest) { route ->
            handler.post {
                if (route != null) {
                    navManager.setRoute(route)
                    drawRoute(route)
                    navDistance.text = formatDistance(route.distanceM)
                    navEta.text = formatDuration(route.durationS)
                    navArrival.text = "Arrive ${getArrivalTime(route.durationS)}"
                } else {
                    navDistance.text = "Route failed"
                }
            }
        }
    }

    private fun drawRoute(route: NavigationManager.Route) {
        removeRoutePolyline()
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
    }

    // ========================================
    // BUTTONS
    // ========================================

    private fun setupButtons() {
        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopRecording() }
        outageButton.setOnClickListener { toggleOutage() }
        diagnosticsButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
    }

    private fun startRecording() {
        isRecording = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        outageButton.isEnabled = true
        hasGpsEverWorked = false
        hasGpsFix = false

        fusion.start()
        tripRecorder.startTrip()

        val serviceIntent = Intent(this, SensorLoggerService::class.java).apply {
            action = SensorLoggerService.ACTION_START
        }
        startForegroundService(serviceIntent)

        positioningPill.visibility = View.VISIBLE
        navInfoCard.visibility = View.VISIBLE
        updateVehicleMarker()
        handler.postDelayed(updateRunnable, 500)
    }

    private fun stopRecording() {
        isRecording = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        outageButton.isEnabled = false
        isOutageActive = false

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
            outageButton.text = getString(R.string.sim_outage)
            outageButton.setTextColor(Color.DKGRAY)
            outageWarning.visibility = View.GONE
        } else {
            fusion.startOutage()
            isOutageActive = true
            outageButton.text = getString(R.string.resume_gps)
            outageButton.setTextColor(Color.RED)
            outageWarning.visibility = View.VISIBLE
        }
    }

    // ========================================
    // GPS & SENSOR CALLBACKS
    // ========================================

    private fun setupGpsCallbacks() {
        GnssCallback.onGnssMeasurement = { measurement ->
            val sats = measurement.satellitesUsed
            val acc = measurement.horizontalAccuracy
            handler.post {
                if (!hasGpsEverWorked) {
                    positioningText.text = "GPS: Searching..."
                    positioningDot.background = makeDot(Color.GRAY)
                }
            }
        }

        GnssCallback.onLocationUpdate = { location ->
            if (!hasGpsFix) {
                hasGpsFix = true
                hasGpsEverWorked = true
                map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                if (isRecording) {
                    fusion.setInitialPosition(location.latitude, location.longitude)
                }
            }

            val geoPoint = GeoPoint(location.latitude, location.longitude)
            gpsTrack.add(geoPoint)

            if (currentSource == null) {
                currentSource = geoPoint
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
        SensorCallback.onSensorUpdate = { _, _, _ -> }
    }

    private fun setupImuCallbacks() {
        SensorCallback.onImuData = { ax, ay, az, gx, gy, gz, ts, linearAccel, gameRotation, magnetometer, rotationVector ->
            if (isRecording) {
                fusion.processImu(ax, ay, az, gx, gy, gz, ts, linearAccel, gameRotation, magnetometer, rotationVector)

                // Update marker directly from IMU for responsiveness (throttled)
                val now = System.currentTimeMillis()
                if (now - lastMarkerUpdateMs > MARKER_UPDATE_INTERVAL_MS) {
                    lastMarkerUpdateMs = now
                    handler.post {
                        updateVehicleMarker()
                        updatePositioningStatus()
                    }
                }
            }
        }
    }

    // ========================================
    // UI UPDATES
    // ========================================

    private fun updatePositioningStatus() {
        val mode = fusion.getPositioningMode()
        val state = fusion.lastFusedPosition
        val confidence = fusion.getConfidenceM()

        // Positioning pill
        when (mode) {
            VehicleStateEstimator.PositioningMode.GPS_ONLY -> {
                positioningText.text = "GPS · high confidence"
                positioningDot.background = makeDot(Color.parseColor("#34A853"))
                positioningText.setTextColor(Color.parseColor("#34A853"))
            }
            VehicleStateEstimator.PositioningMode.GPS_IMU_FUSED -> {
                positioningText.text = "GPS + IMU · active"
                positioningDot.background = makeDot(Color.parseColor("#FBBC04"))
                positioningText.setTextColor(Color.parseColor("#FBBC04"))
            }
            VehicleStateEstimator.PositioningMode.INERTIAL_ONLY -> {
                positioningText.text = "Inertial · active"
                positioningDot.background = makeDot(Color.parseColor("#EA4335"))
                positioningText.setTextColor(Color.parseColor("#EA4335"))
            }
            VehicleStateEstimator.PositioningMode.IDLE -> {
                positioningText.text = "Waiting for GPS…"
                positioningDot.background = makeDot(Color.GRAY)
                positioningText.setTextColor(Color.GRAY)
            }
        }

        // Outage warning
        if (isOutageActive) {
            outageWarning.visibility = View.VISIBLE
        } else {
            outageWarning.visibility = View.GONE
        }

        // Navigation info card
        if (state != null) {
            val speedKmh = state.speed * 3.6
            navStatusText.text = when (mode) {
                VehicleStateEstimator.PositioningMode.GPS_ONLY -> "GPS · high confidence"
                VehicleStateEstimator.PositioningMode.GPS_IMU_FUSED -> "GPS + IMU · active"
                VehicleStateEstimator.PositioningMode.INERTIAL_ONLY -> "Inertial · active"
                VehicleStateEstimator.PositioningMode.IDLE -> "Waiting…"
            }
            navStatusDot.background = makeDot(when (mode) {
                VehicleStateEstimator.PositioningMode.GPS_ONLY -> Color.parseColor("#34A853")
                VehicleStateEstimator.PositioningMode.GPS_IMU_FUSED -> Color.parseColor("#FBBC04")
                VehicleStateEstimator.PositioningMode.INERTIAL_ONLY -> Color.parseColor("#EA4335")
                VehicleStateEstimator.PositioningMode.IDLE -> Color.GRAY
            })

            val confStr = if (confidence > 9999 || !confidence.isFinite()) "—" else "${String.format("%.0f", confidence)}m"
            navConfidence.text = confStr
        }
    }

    private fun updateTrackDisplay() {
        map.overlays.removeAll { it is Polyline && it != routePolyline }

        if (gpsTrack.size > 1) {
            gpsPolyline = Polyline().apply {
                setPoints(gpsTrack)
                outlinePaint.color = Color.parseColor("#4285F4")
                outlinePaint.strokeWidth = 6f
            }
            map.overlays.add(gpsPolyline)
        }

        if (fusedTrack.size > 1) {
            fusedPolyline = Polyline().apply {
                setPoints(fusedTrack)
                outlinePaint.color = Color.parseColor("#34A853")
                outlinePaint.strokeWidth = 6f
            }
            map.overlays.add(fusedPolyline)
        }

        if (inertialTrack.size > 1) {
            inertialPolyline = Polyline().apply {
                setPoints(inertialTrack)
                outlinePaint.color = Color.parseColor("#EA4335")
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
                }
                map.overlays.add(vehicleMarker)
                if (!hasGpsFix) map.controller.setZoom(17.0)
                map.controller.animateTo(geoPoint)
                hasGpsFix = true
            }

            val headingDeg = pos.heading.toFloat()
            vehicleMarker?.icon = BitmapDrawable(resources, createArrowBitmap(headingDeg))
            vehicleMarker?.position = geoPoint
            vehicleMarker?.title = ""
            map.invalidate()
        }
    }

    // ========================================
    // DRAWING
    // ========================================

    private fun createArrowBitmap(headingDeg: Float): Bitmap {
        val size = 80
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.save()
        canvas.rotate(headingDeg, size / 2f, size / 2f)

        // Blue arrow
        paint.color = Color.parseColor("#4285F4")
        paint.style = Paint.Style.FILL
        val cx = size / 2f
        val cy = size / 2f
        val path = android.graphics.Path()
        path.moveTo(cx, cy - 30f)
        path.lineTo(cx - 12f, cy + 10f)
        path.lineTo(cx, cy)
        path.lineTo(cx + 12f, cy + 10f)
        path.close()
        canvas.drawPath(path, paint)

        // White outline
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawPath(path, paint)

        canvas.restore()
        return bitmap
    }

    private fun makeDot(color: Int): android.graphics.drawable.Drawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        drawable.setSize(12, 12)
        return drawable
    }

    // ========================================
    // UTILITIES
    // ========================================

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) String.format("%.1f km", meters / 1000)
        else String.format("%.0f m", meters)
    }

    private fun formatDuration(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m"
        else "${mins}m ${secs}s"
    }

    private fun getArrivalTime(durationS: Double): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.SECOND, durationS.toInt())
        val hour = cal.get(java.util.Calendar.HOUR)
        val min = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (cal.get(java.util.Calendar.AM_PM) == 0) "AM" else "PM"
        return String.format("%d:%02d %s", hour, min, ampm)
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
