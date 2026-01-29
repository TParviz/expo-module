package expo.modules.audiorecorder

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helper для управления уведомлениями записи
 *
 * Использование:
 * ```kotlin
 * // При прерывании (пауза)
 * NotificationHelper.updateForInterruption(
 *     context,
 *     isPaused = true,
 *     source = "PHONE_CALL"
 * )
 *
 * // При возобновлении
 * NotificationHelper.updateRecording(context)
 *
 * // С кастомным сообщением
 * NotificationHelper.updateWithMessage(
 *     context,
 *     isPaused = false,
 *     message = "Music may be recorded"
 * )
 * ```
 */
object NotificationHelper {

    /**
     * Обновить уведомление при прерывании
     *
     * @param context Context
     * @param isPaused true если запись на паузе
     * @param source Источник прерывания (PHONE_CALL, VOIP_CALL, etc.)
     * @param isBluetoothHeadset true если подключены Bluetooth наушники
     */
    fun updateForInterruption(
        context: Context,
        isPaused: Boolean,
        source: String,
        isBluetoothHeadset: Boolean = false
    ) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_UPDATE
            putExtra(RecordingForegroundService.EXTRA_STATE, if (isPaused) "paused" else "recording")
            putExtra(RecordingForegroundService.EXTRA_INTERRUPTION_SOURCE, source)
            putExtra(RecordingForegroundService.EXTRA_IS_BLUETOOTH_HEADSET, isBluetoothHeadset)
        }
        startServiceSafely(context, intent)
    }

    /**
     * Обновить уведомление с кастомным сообщением
     */
    fun updateWithMessage(
        context: Context,
        isPaused: Boolean,
        message: String
    ) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_UPDATE
            putExtra(RecordingForegroundService.EXTRA_STATE, if (isPaused) "paused" else "recording")
            putExtra(RecordingForegroundService.EXTRA_CUSTOM_MESSAGE, message)
        }
        startServiceSafely(context, intent)
    }

    /**
     * Обновить уведомление - обычная запись
     */
    fun updateRecording(context: Context) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_UPDATE
            putExtra(RecordingForegroundService.EXTRA_STATE, "recording")
            putExtra(RecordingForegroundService.EXTRA_INTERRUPTION_SOURCE, RecordingForegroundService.INTERRUPTION_NONE)
        }
        startServiceSafely(context, intent)
    }

    /**
     * Обновить уведомление - пауза (без прерывания)
     */
    fun updatePaused(context: Context) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_UPDATE
            putExtra(RecordingForegroundService.EXTRA_STATE, "paused")
            putExtra(RecordingForegroundService.EXTRA_INTERRUPTION_SOURCE, RecordingForegroundService.INTERRUPTION_NONE)
        }
        startServiceSafely(context, intent)
    }

    /**
     * Запустить foreground service
     */
    fun startService(context: Context) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_START
        }
        startServiceSafely(context, intent)
    }

    /**
     * Остановить foreground service
     */
    fun stopService(context: Context) {
        val intent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_STOP
        }
        startServiceSafely(context, intent)
    }

    private fun startServiceSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Log error but don't crash
            android.util.Log.e("NotificationHelper", "Failed to start service", e)
        }
    }
}