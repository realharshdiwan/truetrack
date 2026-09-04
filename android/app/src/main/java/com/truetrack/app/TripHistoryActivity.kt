package com.truetrack.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripHistoryActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tripListContainer: LinearLayout
    private lateinit var tripDetailPanel: LinearLayout
    private var selectedTripDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 8)
            setBackgroundColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = TextView(this).apply {
            text = "< Back"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 16, 0)
            setOnClickListener { finish() }
        }
        header.addView(backBtn)

        val title = TextView(this).apply {
            text = "Trip History"
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        rootLayout.addView(header)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
        rootLayout.addView(map, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
        ))

        tripDetailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#F8F8F8"))
            visibility = View.GONE
        }
        rootLayout.addView(tripDetailPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val scrollView = ScrollView(this)
        tripListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        scrollView.addView(tripListContainer)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(rootLayout)
        loadTripList()
    }

    private fun loadTripList() {
        tripListContainer.removeAllViews()

        val tripsDir = File(getExternalFilesDir(null), "trips")
        if (!tripsDir.exists() || !tripsDir.isDirectory) {
            tripListContainer.addView(createEmptyView("No trips recorded yet"))
            return
        }

        val trips = tripsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        if (trips.isEmpty()) {
            tripListContainer.addView(createEmptyView("No trips recorded yet"))
            return
        }

        val header = TextView(this).apply {
            text = "${trips.size} trip(s) recorded"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
        }
        tripListContainer.addView(header)

        for (tripDir in trips) {
            val summaryFile = File(tripDir, "summary.json")
            val summary = if (summaryFile.exists()) {
                try {
                    JSONObject(summaryFile.readText())
                } catch (_: Exception) { null }
            } else null

            val tripId = tripDir.name
            val date = parseTripDate(tripId)
            val duration = summary?.optDouble("durationS", 0.0) ?: 0.0
            val gpsPoints = summary?.optInt("gpsTrackPoints", 0) ?: 0
            val fusedPoints = summary?.optInt("fusedTrackPoints", 0) ?: 0
            val outageCount = summary?.optInt("outageEvents", 0) ?: 0

            val tripCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(Color.WHITE)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(16, 4, 16, 4)
                layoutParams = params
                elevation = 2f
            }

            val dateText = TextView(this).apply {
                text = date
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, 4)
            }
            tripCard.addView(dateText)

            val detailsText = TextView(this).apply {
                text = buildString {
                    append("Duration: ${formatDuration(duration)}")
                    append(" | GPS: $gpsPoints pts")
                    if (outageCount > 0) append(" | Outages: $outageCount")
                }
                textSize = 12f
                setTextColor(Color.DKGRAY)
            }
            tripCard.addView(detailsText)

            tripCard.setOnClickListener { showTripDetail(tripDir) }

            tripListContainer.addView(tripCard)
        }
    }

    private fun showTripDetail(tripDir: File) {
        selectedTripDir = tripDir
        tripListContainer.visibility = View.GONE
        tripDetailPanel.visibility = View.VISIBLE
        tripDetailPanel.removeAllViews()

        val backBtn = TextView(this).apply {
            text = "< Back to list"
            setTextColor(Color.parseColor("#2196F3"))
            textSize = 14f
            setPadding(0, 0, 0, 8)
            setOnClickListener {
                tripDetailPanel.visibility = View.GONE
                tripListContainer.visibility = View.VISIBLE
                map.overlays.clear()
                map.invalidate()
            }
        }
        tripDetailPanel.addView(backBtn)

        val titleText = TextView(this).apply {
            text = tripDir.name
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 8)
        }
        tripDetailPanel.addView(titleText)

        map.overlays.clear()
        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0

        val gpsFile = File(tripDir, "gps_track.json")
        if (gpsFile.exists()) {
            try {
                val arr = org.json.JSONArray(gpsFile.readText())
                val points = mutableListOf<GeoPoint>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    points.add(GeoPoint(lat, lon))
                    minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                    minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
                }
                if (points.size > 1) {
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = Color.BLUE
                        outlinePaint.strokeWidth = 8f
                    }
                    map.overlays.add(polyline)
                }
                tripDetailPanel.addView(TextView(this).apply {
                    text = "GPS: ${points.size} points (blue)"
                    textSize = 12f
                    setTextColor(Color.BLUE)
                })
            } catch (_: Exception) {}
        }

        val fusedFile = File(tripDir, "fused_track.json")
        if (fusedFile.exists()) {
            try {
                val arr = org.json.JSONArray(fusedFile.readText())
                val points = mutableListOf<GeoPoint>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    points.add(GeoPoint(lat, lon))
                    minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                    minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
                }
                if (points.size > 1) {
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = Color.GREEN
                        outlinePaint.strokeWidth = 6f
                    }
                    map.overlays.add(polyline)
                }
                tripDetailPanel.addView(TextView(this).apply {
                    text = "Fused: ${points.size} points (green)"
                    textSize = 12f
                    setTextColor(Color.GREEN)
                })
            } catch (_: Exception) {}
        }

        val inertialFile = File(tripDir, "inertial_track.json")
        if (inertialFile.exists()) {
            try {
                val arr = org.json.JSONArray(inertialFile.readText())
                val points = mutableListOf<GeoPoint>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    points.add(GeoPoint(lat, lon))
                    minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                    minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
                }
                if (points.size > 1) {
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = Color.RED
                        outlinePaint.strokeWidth = 6f
                    }
                    map.overlays.add(polyline)
                }
                tripDetailPanel.addView(TextView(this).apply {
                    text = "Inertial: ${points.size} points (red)"
                    textSize = 12f
                    setTextColor(Color.RED)
                })
            } catch (_: Exception) {}
        }

        if (minLat <= maxLat && minLon <= maxLon) {
            val latSpan = maxLat - minLat
            val lonSpan = maxLon - minLon
            val padding = maxOf(latSpan, lonSpan) * 0.15
            val bbox = BoundingBox(
                maxLat + padding,
                maxLon + padding,
                minLat - padding,
                minLon - padding
            )
            map.zoomToBoundingBox(bbox, true, 100)
        }
        map.invalidate()
    }

    private fun createEmptyView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(32, 48, 32, 48)
            gravity = Gravity.CENTER
        }
    }

    private fun parseTripDate(tripId: String): String {
        return try {
            val datePart = tripId.removePrefix("trip_")
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val date = sdf.parse(datePart)
            val display = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
            display.format(date ?: Date())
        } catch (_: Exception) {
            tripId
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
}
