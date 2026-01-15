package expo.modules.audiorecorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioFocusManager(
    private val context: Context,
    private val onFocusLost: () -> Unit,
    private val onFocusGained: () -> Unit
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Полная потеря фокуса (например, входящий звонок)
                // Нужно остановить или приостановить запись
                android.util.Log.w("AudioFocus", "AUDIOFOCUS_LOSS - паузим запись")
                hasAudioFocus = false
                onFocusLost()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Временная потеря (например, уведомление)
                // Паузим запись
                android.util.Log.w("AudioFocus", "AUDIOFOCUS_LOSS_TRANSIENT - паузим запись")
                hasAudioFocus = false
                onFocusLost()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Можно продолжать тихо (но для записи лучше приостановить)
                android.util.Log.w("AudioFocus", "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK - паузим запись")
                hasAudioFocus = false
                onFocusLost()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                // Фокус вернулся
                android.util.Log.i("AudioFocus", "AUDIOFOCUS_GAIN - можно возобновить")
                hasAudioFocus = true
                onFocusGained()
            }
        }
    }

    /**
     * Запрашивает аудиофокус
     * @return true если фокус получен
     */
    fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            // Android 7.1 и ниже
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        if (hasAudioFocus) {
            android.util.Log.i("AudioFocus", "Audio focus granted")
        } else {
            android.util.Log.e("AudioFocus", "Audio focus denied")
        }

        return hasAudioFocus
    }

    /**
     * Освобождает аудиофокус
     */
    fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }

        hasAudioFocus = false
        android.util.Log.i("AudioFocus", "Audio focus abandoned")
    }

    /**
     * Проверяет наличие аудиофокуса
     */
    fun hasAudioFocus(): Boolean = hasAudioFocus
}