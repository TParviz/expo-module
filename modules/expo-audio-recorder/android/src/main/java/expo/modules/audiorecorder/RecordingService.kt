package expo.modules.audiorecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {

    companion object {
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "ACTION_RESUME_RECORDING"

        const val EXTRA_CONFIG = "EXTRA_CONFIG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recording_channel"
        private const val CHANNEL_NAME = "Audio Recording"

        var isServiceRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                startForegroundWithNotification("Запись началась", "Идет запись аудио...")
                isServiceRunning = true
            }
            ACTION_STOP_RECORDING -> {
                stopForeground(true)
                stopSelf()
                isServiceRunning = false
            }
            ACTION_PAUSE_RECORDING -> {
                updateNotification("Запись на паузе", "Нажмите для продолжения")
            }
            ACTION_RESUME_RECORDING -> {
                updateNotification("Запись продолжается", "Идет запись аудио...")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает уведомление во время записи аудио"
                setSound(null, null) // Без звука
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(title: String, text: String) {
        val notification = buildNotification(title, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        // Intent для открытия приложения при клике
        val pendingIntent = packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.let { intent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Иконка микрофона
            .setOngoing(true) // Нельзя свайпнуть
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}