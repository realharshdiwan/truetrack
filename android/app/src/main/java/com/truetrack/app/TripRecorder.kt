package com.truetrack.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class TripRecorder(private val context: Context) {

    companion object {
        private const val TAG = "TripRecorder"
    }

    data class TripConfig(
        var recordImu: Boolean = true,
        var recordGnss: Boolean = true,
        var recordFused: Boolean = true,
        var recordRawMeasurements: Boolean = false,
        var maxFileSizeMb: Int = 100
    )

    data class TripSummary(
        val tripId: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationS: Double,
        val gpsFixCount: Int,
        val imuSampleCount: Int,
        val maxDriftM: Double,
        val meanDriftM: Double,
        val finalDriftM: Double,
        val outageEvents: Int,
        val maxOutageS: Double,
        val deviceModel: String,
        val androidVersion: String,
        val exportPath: String
    )

    private val imuQueue = ConcurrentLinkedQueue<JSONObject>()
    private val gnssQueue = ConcurrentLinkedQueue<JSONObject>()
    private val fusedQueue = ConcurrentLinkedQueue<JSONObject>()
    private val outageEvents = mutableListOf<JSONObject>()

    private var tripId = ""
    private var startTimeMs = 0L
    private var isRecording = false
    private var writerThread: Thread? = null
    private var tripConfig = TripConfig()

    // Stats
    var imuCount = 0L
        private set
    var gnssCount = 0L
        private set
    var fusedCount = 0L
        private set

    fun startTrip(config: TripConfig = TripConfig()) {
        tripId = "trip_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        startTimeMs = System.currentTimeMillis()
        tripConfig = config
        isRecording = true
        imuCount = 0
        gnssCount = 0
        fusedCount = 0
        outageEvents.clear()

        writerThread = Thread {
            writeTripData()
        }.apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Trip started: $tripId")
    }

    fun stopTrip(): TripSummary {
        isRecording = false
        writerThread?.join(5000)

        val summary = generateSummary()
        writeSummary(summary)
        Log.d(TAG, "Trip stopped: $tripId, duration=${summary.durationS}s, GPS=${summary.gpsFixCount}, IMU=${summary.imuSampleCount}")
        return summary
    }

    fun exportSession(
        gpsTrack: List<Pair<Double, Double>>,
        fusedTrack: List<Pair<Double, Double>>,
        inertialTrack: List<Pair<Double, Double>>,
        outages: List<Pair<Long, Long>>
    ) {
        isRecording = false
        val endTimeMs = System.currentTimeMillis()
        val dir = context.getExternalFilesDir(null) ?: return
        val tripDir = File(dir, "trips/$tripId")
        tripDir.mkdirs()

        val summary = JSONObject().apply {
            put("tripId", tripId)
            put("startTimeMs", startTimeMs)
            put("endTimeMs", endTimeMs)
            put("durationS", (endTimeMs - startTimeMs) / 1000.0)
            put("gpsTrackPoints", gpsTrack.size)
            put("fusedTrackPoints", fusedTrack.size)
            put("inertialTrackPoints", inertialTrack.size)
            put("outageEvents", outages.size)
        }
        File(tripDir, "summary.json").writeText(summary.toString(2))

        val gpsArray = JSONArray()
        gpsTrack.forEach { (lat, lon) -> gpsArray.put(JSONObject().put("lat", lat).put("lon", lon)) }
        File(tripDir, "gps_track.json").writeText(gpsArray.toString(2))

        val fusedArray = JSONArray()
        fusedTrack.forEach { (lat, lon) -> fusedArray.put(JSONObject().put("lat", lat).put("lon", lon)) }
        File(tripDir, "fused_track.json").writeText(fusedArray.toString(2))

        val inertialArray = JSONArray()
        inertialTrack.forEach { (lat, lon) -> inertialArray.put(JSONObject().put("lat", lat).put("lon", lon)) }
        File(tripDir, "inertial_track.json").writeText(inertialArray.toString(2))

        Log.d(TAG, "Session exported: $tripId to ${tripDir.absolutePath}")
    }

    fun recordImu(
        timestampMs: Long,
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float,
        mx: Float, my: Float, mz: Float,
        rotationVector: FloatArray? = null,
        linearAccel: FloatArray? = null,
        gameRotation: FloatArray? = null
    ) {
        if (!isRecording || !tripConfig.recordImu) return

        val json = JSONObject().apply {
            put("ts", timestampMs)
            put("ax", ax); put("ay", ay); put("az", az)
            put("gx", gx); put("gy", gy); put("gz", gz)
            put("mx", mx); put("my", my); put("mz", mz)
            rotationVector?.let {
                put("rw", JSONArray(it.map { v -> v.toDouble() }))
            }
            linearAccel?.let {
                put("la", JSONArray(it.map { v -> v.toDouble() }))
            }
            gameRotation?.let {
                put("gr", JSONArray(it.map { v -> v.toDouble() }))
            }
        }

        imuQueue.offer(json)
        imuCount++
    }

    fun recordGnss(
        timestampMs: Long,
        latitude: Double, longitude: Double, altitude: Double,
        speed: Float, bearing: Float,
        hAccuracy: Float, vAccuracy: Float, speedAccuracy: Float, bearingAccuracy: Float,
        satellitesUsed: Int, satellitesTotal: Int,
        rawMeasurements: List<GnssCollector.RawGnssMeasurement>? = null
    ) {
        if (!isRecording || !tripConfig.recordGnss) return

        val json = JSONObject().apply {
            put("ts", timestampMs)
            put("lat", latitude); put("lon", longitude); put("alt", altitude)
            put("spd", speed.toDouble()); put("brg", bearing.toDouble())
            put("hacc", hAccuracy.toDouble()); put("vacc", vAccuracy.toDouble())
            put("sacc", speedAccuracy.toDouble()); put("bacc", bearingAccuracy.toDouble())
            put("sat", satellitesUsed); put("satTotal", satellitesTotal)

            if (tripConfig.recordRawMeasurements && rawMeasurements != null && rawMeasurements.isNotEmpty()) {
                val rawArr = JSONArray()
                for (raw in rawMeasurements) {
                    rawArr.put(JSONObject().apply {
                        put("svId", raw.svId)
                        put("const", raw.constellationType)
                        put("state", raw.state)
                        put("svTime", raw.receivedSvTimeNanos)
                        put("svTimeUnc", raw.receivedSvTimeUncertaintyNanos)
                        put("cn0", raw.cn0DbHz)
                        put("prr", raw.pseudorangeRateMetersPerSecond)
                        put("prrUnc", raw.pseudorangeRateUncertaintyMetersPerSecond)
                        put("adr", raw.accumulatedDeltaRangeMeters)
                        put("adrState", raw.accumulatedDeltaRangeState)
                    })
                }
                put("raw", rawArr)
            }
        }

        gnssQueue.offer(json)
        gnssCount++
    }

    fun recordFused(
        timestampMs: Long,
        lat: Double, lon: Double, heading: Double, speed: Double,
        mode: String,
        confidenceM: Double
    ) {
        if (!isRecording || !tripConfig.recordFused) return

        val json = JSONObject().apply {
            put("ts", timestampMs)
            put("lat", lat); put("lon", lon)
            put("hdg", heading); put("spd", speed)
            put("mode", mode)
            put("conf", confidenceM)
        }

        fusedQueue.offer(json)
        fusedCount++
    }

    fun recordOutageEvent(startMs: Long, endMs: Long, maxDriftM: Double, finalDriftM: Double) {
        if (!isRecording) return

        outageEvents.add(JSONObject().apply {
            put("startMs", startMs)
            put("endMs", endMs)
            put("durationS", (endMs - startMs) / 1000.0)
            put("maxDriftM", maxDriftM)
            put("finalDriftM", finalDriftM)
        })
    }

    private fun writeTripData() {
        val dir = File(context.getExternalFilesDir(null), "trips")
        dir.mkdirs()
        val tripDir = File(dir, tripId)
        tripDir.mkdirs()

        // Write IMU data
        if (tripConfig.recordImu) {
            File(tripDir, "imu.jsonl").bufferedWriter().use { writer ->
                while (isRecording || imuQueue.isNotEmpty()) {
                    val json = imuQueue.poll()
                    if (json != null) {
                        writer.write(json.toString())
                        writer.newLine()
                    } else {
                        Thread.sleep(10)
                    }
                }
            }
        }

        // Write GNSS data
        if (tripConfig.recordGnss) {
            File(tripDir, "gnss.jsonl").bufferedWriter().use { writer ->
                while (isRecording || gnssQueue.isNotEmpty()) {
                    val json = gnssQueue.poll()
                    if (json != null) {
                        writer.write(json.toString())
                        writer.newLine()
                    } else {
                        Thread.sleep(10)
                    }
                }
            }
        }

        // Write fused data
        if (tripConfig.recordFused) {
            File(tripDir, "fused.jsonl").bufferedWriter().use { writer ->
                while (isRecording || fusedQueue.isNotEmpty()) {
                    val json = fusedQueue.poll()
                    if (json != null) {
                        writer.write(json.toString())
                        writer.newLine()
                    } else {
                        Thread.sleep(10)
                    }
                }
            }
        }

        // Write outage events
        File(tripDir, "outage_events.json").writeText(
            JSONArray(outageEvents).toString(2)
        )
    }

    private fun writeSummary(summary: TripSummary) {
        val dir = File(context.getExternalFilesDir(null), "trips/${summary.tripId}")
        val json = JSONObject().apply {
            put("tripId", summary.tripId)
            put("startTimeMs", summary.startTimeMs)
            put("endTimeMs", summary.endTimeMs)
            put("durationS", summary.durationS)
            put("gpsFixCount", summary.gpsFixCount)
            put("imuSampleCount", summary.imuSampleCount)
            put("maxDriftM", summary.maxDriftM)
            put("meanDriftM", summary.meanDriftM)
            put("finalDriftM", summary.finalDriftM)
            put("outageEvents", summary.outageEvents)
            put("maxOutageS", summary.maxOutageS)
            put("deviceModel", summary.deviceModel)
            put("androidVersion", summary.androidVersion)
            put("config", JSONObject().apply {
                put("recordImu", tripConfig.recordImu)
                put("recordGnss", tripConfig.recordGnss)
                put("recordFused", tripConfig.recordFused)
                put("recordRawMeasurements", tripConfig.recordRawMeasurements)
            })
        }

        File(dir, "summary.json").writeText(json.toString(2))
        Log.d(TAG, "Summary written: ${dir.absolutePath}")
    }

    private fun generateSummary(): TripSummary {
        val endTimeMs = System.currentTimeMillis()

        // Calculate drift stats from fused data
        var maxDrift = 0.0
        var meanDrift = 0.0
        var finalDrift = 0.0
        var driftCount = 0

        // Read from fused file if available
        val fusedFile = File(context.getExternalFilesDir(null), "trips/$tripId/fused.jsonl")
        if (fusedFile.exists()) {
            val lastFused = mutableListOf<Pair<Double, Double>>()
            fusedFile.forEachLine { line ->
                try {
                    val json = JSONObject(line)
                    lastFused.add(Pair(json.getDouble("lat"), json.getDouble("lon")))
                } catch (_: Exception) {}
            }
            // Simple drift calculation against last GNSS position
        }

        val maxOutageS = outageEvents.maxOfOrNull { it.getDouble("durationS") } ?: 0.0

        return TripSummary(
            tripId = tripId,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            durationS = (endTimeMs - startTimeMs) / 1000.0,
            gpsFixCount = gnssCount.toInt(),
            imuSampleCount = imuCount.toInt(),
            maxDriftM = maxDrift,
            meanDriftM = meanDrift,
            finalDriftM = finalDrift,
            outageEvents = outageEvents.size,
            maxOutageS = maxOutageS,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            exportPath = File(context.getExternalFilesDir(null), "trips/$tripId").absolutePath
        )
    }

    fun getTripDir(): File {
        return File(context.getExternalFilesDir(null), "trips/$tripId")
    }

    fun listTrips(): List<String> {
        val dir = File(context.getExternalFilesDir(null), "trips")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()?.reversed() ?: emptyList()
    }

    fun exportGpx(
        name: String,
        trackPoints: List<Triple<Double, Double, Long>>
    ) {
        val dir = File(context.getExternalFilesDir(null), "trips/$tripId")
        dir.mkdirs()

        val gpx = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"TrueTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            appendLine("  <metadata><name>$name</name></metadata>")
            appendLine("  <trk><name>$name</name>")
            appendLine("    <trkseg>")
            for ((lat, lon, ts) in trackPoints) {
                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(java.util.Date(ts))
                appendLine("      <trkpt lat=\"${String.format("%.7f", lat)}\" lon=\"${String.format("%.7f", lon)}\"><time>$timeStr</time></trkpt>")
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
            appendLine("</gpx>")
        }

        File(dir, "${name.replace(" ", "_")}.gpx").writeText(gpx)
        Log.d(TAG, "GPX exported: $name (${trackPoints.size} points)")
    }
}
