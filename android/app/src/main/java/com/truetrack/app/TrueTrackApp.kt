package com.truetrack.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TrueTrackApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sensor Logger",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "TrueTrack sensor recording service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "truetrack_sensor_logger"
    }
}
