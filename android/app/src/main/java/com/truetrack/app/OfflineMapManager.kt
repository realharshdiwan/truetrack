package com.truetrack.app

import android.content.Context
import android.util.Log
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class OfflineMapManager(private val context: Context) {

    companion object {
        private const val TAG = "OfflineMap"
        private const val CACHE_DIR = "truetrack_offline_tiles"
        private const val MIN_ZOOM = 10
        private const val MAX_ZOOM = 18
    }

    private val tileCacheDir = File(context.filesDir, CACHE_DIR)
    private val isDownloading = AtomicBoolean(false)
    private var downloadProgress = 0
    private var totalTiles = 0

    data class DownloadProgress(
        val downloaded: Int,
        val total: Int,
        val isComplete: Boolean
    )

    fun initialize() {
        tileCacheDir.mkdirs()
        Log.d(TAG, "Cache dir: ${tileCacheDir.absolutePath}")
        Log.d(TAG, "Cached tiles: ${countCachedTiles()}")
    }

    fun getCachedTileCount(): Int = countCachedTiles()

    fun getCacheSizeMB(): Double {
        var size = 0L
        tileCacheDir.walkTopDown().forEach {
            if (it.isFile) size += it.length()
        }
        return size / (1024.0 * 1024.0)
    }

    fun downloadArea(
        boundingBox: BoundingBox,
        zoomMin: Int = MIN_ZOOM,
        zoomMax: Int = MAX_ZOOM,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ) {
        if (isDownloading.get()) {
            Log.w(TAG, "Already downloading")
            return
        }

        Thread {
            isDownloading.set(true)
            downloadProgress = 0
            totalTiles = 0

            try {
                val tileSource = XYTileSource(
                    "Mapnik",
                    0, MAX_ZOOM, 256, ".png",
                    arrayOf("https://tile.openstreetmap.org/")
                )

                for (zoom in zoomMin..zoomMax) {
                    val xStart = lonToTileX(boundingBox.lonWest, zoom)
                    val xEnd = lonToTileX(boundingBox.lonEast, zoom)
                    val yStart = latToTileY(boundingBox.latNorth, zoom)
                    val yEnd = latToTileY(boundingBox.latSouth, zoom)
                    totalTiles += (xEnd - xStart + 1) * (yEnd - yStart + 1)
                }

                Log.d(TAG, "Downloading $totalTiles tiles for zoom $zoomMin-$zoomMax")

                for (zoom in zoomMin..zoomMax) {
                    val xStart = lonToTileX(boundingBox.lonWest, zoom)
                    val xEnd = lonToTileX(boundingBox.lonEast, zoom)
                    val yStart = latToTileY(boundingBox.latNorth, zoom)
                    val yEnd = latToTileY(boundingBox.latSouth, zoom)

                    for (x in xStart..xEnd) {
                        for (y in yStart..yEnd) {
                            try {
                                downloadTile(tileSource, x, y, zoom)
                                downloadProgress++
                                if (downloadProgress % 10 == 0) {
                                    onProgress?.invoke(DownloadProgress(downloadProgress, totalTiles, false))
                                }
                                Thread.sleep(100)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to download tile: ${e.message}")
                            }
                        }
                    }
                }

                onProgress?.invoke(DownloadProgress(downloadProgress, totalTiles, true))
                Log.d(TAG, "Download complete: $downloadProgress tiles")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                onProgress?.invoke(DownloadProgress(downloadProgress, totalTiles, true))
            } finally {
                isDownloading.set(false)
            }
        }.start()
    }

    private fun downloadTile(source: XYTileSource, x: Int, y: Int, zoom: Int) {
        val tileDir = File(tileCacheDir, "$zoom/$x")
        tileDir.mkdirs()
        val tileFile = File(tileDir, "$y.png")

        if (tileFile.exists()) return

        val url = source.getTileURLString((zoom.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL) or (y.toLong() shl 16))

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        conn.inputStream.use { input ->
            tileFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun countCachedTiles(): Int {
        var count = 0
        tileCacheDir.walkTopDown().forEach {
            if (it.isFile && it.extension == "png") count++
        }
        return count
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)).toInt()
    }

    fun isDownloading(): Boolean = isDownloading.get()

    fun clearCache() {
        tileCacheDir.deleteRecursively()
        tileCacheDir.mkdirs()
        Log.d(TAG, "Cache cleared")
    }
}
