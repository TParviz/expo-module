package audio.recorder.helper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Менеджер аудио фокуса
 * 
 * Запрашивает и отслеживает аудио фокус Android.
 */
class AudioFocusManager(
    private val context: Context,
    private val onFocusLost: (focusChange: Int) -> Unit,
    private val onFocusGained: () -> Unit
) {
    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Focus change: $focusChange")
        
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                hasFocus = true
                onFocusGained()
            }
            
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                onFocusLost(focusChange)
            }
        }
    }

    /**
     * Запросить аудио фокус
     */
    fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestFocusApi26()
        } else {
            requestFocusLegacy()
        }
    }

    /**
     * Освободить аудио фокус
     */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    /**
     * Проверить есть ли фокус
     */
    fun hasFocus(): Boolean = hasFocus

    private fun requestFocusApi26(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        
        Log.d(TAG, "Audio focus requested (API 26+): result=$result, hasFocus=$hasFocus")
        return hasFocus
    }

    @Suppress("DEPRECATION")
    private fun requestFocusLegacy(): Boolean {
        val result = audioManager.requestAudioFocus(
            focusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        
        Log.d(TAG, "Audio focus requested (legacy): result=$result, hasFocus=$hasFocus")
        return hasFocus
    }
}