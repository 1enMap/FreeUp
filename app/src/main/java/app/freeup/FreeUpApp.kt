package app.freeup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class FreeUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_PROGRESS,
            "Compression progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows progress while videos are being compressed" }
        val done = NotificationChannel(
            CHANNEL_DONE,
            "Compression finished",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Tells you how much space was freed" }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, done))
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
    }
}
