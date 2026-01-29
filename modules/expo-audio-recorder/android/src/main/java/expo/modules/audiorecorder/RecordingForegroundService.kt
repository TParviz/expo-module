package expo.modules.audiorecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service для записи в фоне
 *
 * Показывает уведомление во время записи, позволяя продолжать запись
 * при свёрнутом приложении.
 *
 * Поддерживает разные уведомления для разных типов прерываний.
 */
class RecordingForegroundService : Service() {
    companion object {
        const val ACTION_START = "expo.audiorecorder.START"
        const val ACTION_STOP = "expo.audiorecorder.STOP"
        const val ACTION_UPDATE = "expo.audiorecorder.UPDATE"

        private const val CHANNEL_ID = "audio_recorder_channel"
        private const val NOTIFICATION_ID = 1001

        // === Interruption Sources (должны совпадать с InterruptionManager) ===
        const val INTERRUPTION_NONE = "none"
        const val INTERRUPTION_PHONE_CALL = "PHONE_CALL"
        const val INTERRUPTION_VOIP_CALL = "VOIP_CALL"
        const val INTERRUPTION_VOICE_ASSISTANT = "VOICE_ASSISTANT"
        const val INTERRUPTION_VOICE_RECORDER = "VOICE_RECORDER"
        const val INTERRUPTION_MUSIC_PLAYER = "MUSIC_PLAYER"
        const val INTERRUPTION_VIDEO_PLAYER = "VIDEO_PLAYER"
        const val INTERRUPTION_GAME = "GAME"
        const val INTERRUPTION_NAVIGATION = "NAVIGATION"
        const val INTERRUPTION_NOTIFICATION = "NOTIFICATION"
        const val INTERRUPTION_UNKNOWN = "UNKNOWN"

        // === Extra keys ===
        const val EXTRA_STATE = "state"
        const val EXTRA_INTERRUPTION_SOURCE = "interruption_source"
        const val EXTRA_CUSTOM_MESSAGE = "custom_message"
        const val EXTRA_IS_BLUETOOTH_HEADSET = "is_bluetooth_headset"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification(
                    state = "recording",
                    interruptionSource = INTERRUPTION_NONE
                ))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                val state = intent.getStringExtra(EXTRA_STATE) ?: "recording"
                val interruptionSource = intent.getStringExtra(EXTRA_INTERRUPTION_SOURCE) ?: INTERRUPTION_NONE
                val customMessage = intent.getStringExtra(EXTRA_CUSTOM_MESSAGE)
                val isBluetoothHeadset = intent.getBooleanExtra(EXTRA_IS_BLUETOOTH_HEADSET, false)

                val notification = createNotification(
                    state = state,
                    interruptionSource = interruptionSource,
                    customMessage = customMessage,
                    isBluetoothHeadset = isBluetoothHeadset
                )

                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when audio recording is in progress"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        state: String,
        interruptionSource: String = INTERRUPTION_NONE,
        customMessage: String? = null,
        isBluetoothHeadset: Boolean = false
    ): Notification {
        val (title, text, icon) = getNotificationContent(
            state = state,
            interruptionSource = interruptionSource,
            customMessage = customMessage,
            isBluetoothHeadset = isBluetoothHeadset
        )

        // Intent для открытия приложения
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Получить контент уведомления в зависимости от состояния и типа прерывания
     *
     * @return Triple(title, text, icon)
     */
    private fun getNotificationContent(
        state: String,
        interruptionSource: String,
        customMessage: String?,
        isBluetoothHeadset: Boolean
    ): Triple<String, String, Int> {

        // Если есть кастомное сообщение — используем его
        if (!customMessage.isNullOrEmpty()) {
            val icon = if (state == "paused") {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_btn_speak_now
            }
            return Triple(
                if (state == "paused") "Recording paused" else "Recording...",
                customMessage,
                icon
            )
        }

        // Состояние "paused" с прерыванием
        if (state == "paused") {
            return when (interruptionSource) {
                INTERRUPTION_PHONE_CALL -> Triple(
                    "Recording paused",
                    "Phone call in progress",
                    android.R.drawable.ic_menu_call
                )
                INTERRUPTION_VOIP_CALL -> Triple(
                    "Recording paused",
                    "VoIP call in progress (WhatsApp, Telegram...)",
                    android.R.drawable.ic_menu_call
                )
                INTERRUPTION_VOICE_ASSISTANT -> Triple(
                    "Recording paused",
                    "Voice assistant active",
                    android.R.drawable.ic_btn_speak_now
                )
                INTERRUPTION_VOICE_RECORDER -> Triple(
                    "Recording paused",
                    "Another voice recorder is active",
                    android.R.drawable.ic_media_pause
                )
                else -> Triple(
                    "Recording paused",
                    "Tap to return to app",
                    android.R.drawable.ic_media_pause
                )
            }
        }

        // Состояние "recording" с прерыванием (CONTINUE_NOTIFY)
        if (state == "recording" && interruptionSource != INTERRUPTION_NONE) {
            return when (interruptionSource) {
                INTERRUPTION_MUSIC_PLAYER -> {
                    if (isBluetoothHeadset) {
                        Triple(
                            "Recording...",
                            "Music playing (Bluetooth headset connected)",
                            android.R.drawable.ic_btn_speak_now
                        )
                    } else {
                        Triple(
                            "⚠️ Recording continues",
                            "Music may be recorded",
                            android.R.drawable.ic_dialog_alert
                        )
                    }
                }
                INTERRUPTION_VIDEO_PLAYER -> {
                    if (isBluetoothHeadset) {
                        Triple(
                            "Recording...",
                            "Video playing (Bluetooth headset connected)",
                            android.R.drawable.ic_btn_speak_now
                        )
                    } else {
                        Triple(
                            "⚠️ Recording continues",
                            "Video sound may be recorded",
                            android.R.drawable.ic_dialog_alert
                        )
                    }
                }
                INTERRUPTION_GAME -> Triple(
                    "Recording...",
                    "Game audio detected",
                    android.R.drawable.ic_btn_speak_now
                )
                INTERRUPTION_NAVIGATION -> Triple(
                    "Recording...",
                    "Navigation active",
                    android.R.drawable.ic_btn_speak_now
                )
                INTERRUPTION_NOTIFICATION -> Triple(
                    "Recording...",
                    "Notification sound played",
                    android.R.drawable.ic_btn_speak_now
                )
                else -> Triple(
                    "Recording...",
                    "Tap to return to app",
                    android.R.drawable.ic_btn_speak_now
                )
            }
        }

        // Обычное состояние без прерываний
        return when (state) {
            else -> Triple(
                "Recording...",
                "Tap to return to app",
                android.R.drawable.ic_btn_speak_now
            )
        }
    }
}