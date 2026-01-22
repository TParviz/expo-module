package audio.recorder.helper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Источники прерываний
 */
enum class InterruptionSource {
    PHONE_CALL,        // Телефонный звонок
    VOIP_CALL,         // VoIP (WhatsApp, Telegram, Zoom)
    VOICE_ASSISTANT,   // Голосовой ассистент (Siri, Google Assistant)
    VOICE_RECORDER,    // Другой диктофон
    MUSIC_PLAYER,      // Музыкальный плеер
    VIDEO_PLAYER,      // Видео приложение
    GAME,              // Игра
    NAVIGATION,        // Навигация
    NOTIFICATION,      // Уведомление
    UNKNOWN            // Неизвестно
}

/**
 * Политики обработки прерываний
 */
enum class InterruptionPolicy {
    PAUSE,             // Пауза (вызывающий код решает что делать)
    CONTINUE,          // Продолжить (можно уведомить пользователя)
    IGNORE             // Игнорировать
}

/**
 * Информация о прерывании
 */
data class InterruptionInfo(
    val source: InterruptionSource,
    val policy: InterruptionPolicy,
    val focusChange: Int,
    val message: String
)

/**
 * Менеджер прерываний
 * 
 * Отслеживает аудио прерывания и определяет их источник.
 * НЕ управляет записью напрямую - только отправляет события.
 */
class InterruptionManager(
    private val context: Context,
    private val onInterruption: (InterruptionInfo) -> Unit,
    private val onInterruptionEnd: (InterruptionSource) -> Unit
) {
    companion object {
        private const val TAG = "InterruptionManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // Политики для каждого источника
    private val policies = mapOf(
        InterruptionSource.PHONE_CALL to InterruptionPolicy.PAUSE,
        InterruptionSource.VOIP_CALL to InterruptionPolicy.PAUSE,
        InterruptionSource.VOICE_ASSISTANT to InterruptionPolicy.PAUSE,
        InterruptionSource.VOICE_RECORDER to InterruptionPolicy.PAUSE,
        InterruptionSource.MUSIC_PLAYER to InterruptionPolicy.CONTINUE,
        InterruptionSource.VIDEO_PLAYER to InterruptionPolicy.CONTINUE,
        InterruptionSource.GAME to InterruptionPolicy.IGNORE,
        InterruptionSource.NAVIGATION to InterruptionPolicy.IGNORE,
        InterruptionSource.NOTIFICATION to InterruptionPolicy.IGNORE,
        InterruptionSource.UNKNOWN to InterruptionPolicy.IGNORE
    )

    // Трекинг состояния
    @Volatile private var hasMusicPlayback = false
    @Volatile private var hasVideoPlayback = false
    private val activeInterruptions = mutableSetOf<InterruptionSource>()

    // AudioPlaybackCallback для детекции музыки/видео
    private val playbackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                updatePlaybackState(configs)
            }
        }
    } else null

    /**
     * Начать мониторинг прерываний
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            Log.d(TAG, "Starting interruption monitoring")
            audioManager.registerAudioPlaybackCallback(playbackCallback, null)
            updatePlaybackState(audioManager.activePlaybackConfigurations)
        }
    }

    /**
     * Остановить мониторинг
     */
    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback", e)
            }
        }
        hasMusicPlayback = false
        hasVideoPlayback = false
        activeInterruptions.clear()
    }

    /**
     * Обработать потерю аудио фокуса
     */
    fun handleAudioFocusLoss(focusChange: Int) {
        Log.d(TAG, "Audio focus loss: focusChange=$focusChange")

        // Проверяем isMusicActive первым
        if (audioManager.isMusicActive) {
            Log.d(TAG, "isMusicActive=true, treating as MUSIC_PLAYER")
            notifyInterruption(InterruptionSource.MUSIC_PLAYER, focusChange)
            return
        }

        // Проверяем через callback
        if (hasMusicPlayback || hasVideoPlayback) {
            val source = if (hasVideoPlayback) InterruptionSource.VIDEO_PLAYER else InterruptionSource.MUSIC_PLAYER
            Log.d(TAG, "Playback callback: $source")
            notifyInterruption(source, focusChange)
            return
        }

        // Обновляем состояние и проверяем снова
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePlaybackState(audioManager.activePlaybackConfigurations)
            if (hasMusicPlayback || hasVideoPlayback) {
                val source = if (hasVideoPlayback) InterruptionSource.VIDEO_PLAYER else InterruptionSource.MUSIC_PLAYER
                notifyInterruption(source, focusChange)
                return
            }
        }

        // Детектим источник
        val source = detectInterruptionSource(focusChange)
        notifyInterruption(source, focusChange)
    }

    /**
     * Обработать возврат аудио фокуса
     */
    fun handleAudioFocusGain() {
        Log.d(TAG, "Audio focus gained")
        
        activeInterruptions.toList().forEach { source ->
            activeInterruptions.remove(source)
            onInterruptionEnd(source)
        }
    }

    /**
     * Получить текущую политику для источника
     */
    fun getPolicy(source: InterruptionSource): InterruptionPolicy {
        return policies[source] ?: InterruptionPolicy.IGNORE
    }

    /**
     * Установить кастомную политику
     */
    fun setPolicy(source: InterruptionSource, policy: InterruptionPolicy) {
        // Можно добавить mutableMapOf если нужна кастомизация
    }

    // === Private ===

    private fun notifyInterruption(source: InterruptionSource, focusChange: Int) {
        val policy = policies[source] ?: InterruptionPolicy.IGNORE
        val message = getInterruptionMessage(source)

        Log.d(TAG, "Interruption: source=$source, policy=$policy")

        activeInterruptions.add(source)

        onInterruption(InterruptionInfo(
            source = source,
            policy = policy,
            focusChange = focusChange,
            message = message
        ))
    }

    private fun detectInterruptionSource(focusChange: Int): InterruptionSource {
        // 1. Телефония
        if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
            return InterruptionSource.PHONE_CALL
        }

        // 2. Audio mode
        when (audioManager.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> {
                return InterruptionSource.PHONE_CALL
            }
            AudioManager.MODE_IN_COMMUNICATION -> {
                return if (isLikelyVoIPCall()) InterruptionSource.VOIP_CALL 
                       else InterruptionSource.VOICE_RECORDER
            }
        }

        // 3. Проверяем ассистента
        if (isAssistantActive()) {
            return InterruptionSource.VOICE_ASSISTANT
        }

        // 4. По типу потери фокуса
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> InterruptionSource.MUSIC_PLAYER
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> InterruptionSource.NOTIFICATION
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> InterruptionSource.NAVIGATION
            else -> InterruptionSource.UNKNOWN
        }
    }

    private fun updatePlaybackState(configs: List<AudioPlaybackConfiguration>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        var foundMusic = false
        var foundVideo = false

        configs?.forEach { config ->
            when (config.audioAttributes.usage) {
                AudioAttributes.USAGE_MEDIA -> {
                    when (config.audioAttributes.contentType) {
                        AudioAttributes.CONTENT_TYPE_MUSIC -> foundMusic = true
                        AudioAttributes.CONTENT_TYPE_MOVIE -> foundVideo = true
                        else -> foundMusic = true
                    }
                }
                AudioAttributes.USAGE_GAME -> foundMusic = true
            }
        }

        // Fallback на isMusicActive
        if (!foundMusic && !foundVideo && audioManager.isMusicActive) {
            foundMusic = true
        }

        hasMusicPlayback = foundMusic
        hasVideoPlayback = foundVideo
    }

    private fun isLikelyVoIPCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.activePlaybackConfigurations.forEach { config ->
                if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                    return true
                }
            }
        }
        return audioManager.isSpeakerphoneOn
    }

    private fun isAssistantActive(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val configs = audioManager.activeRecordingConfigurations
            // Если больше 1 записи и MODE_NORMAL - скорее всего ассистент
            if (configs.size > 1 && audioManager.mode == AudioManager.MODE_NORMAL) {
                return true
            }
            // Проверяем audioSource
            configs.forEach { config ->
                if (config.audioSource == 6 || config.audioSource == 1999) { // VOICE_RECOGNITION, HOTWORD
                    return true
                }
            }
        }
        return false
    }

    private fun getInterruptionMessage(source: InterruptionSource): String {
        return when (source) {
            InterruptionSource.PHONE_CALL -> "Incoming phone call"
            InterruptionSource.VOIP_CALL -> "VoIP call"
            InterruptionSource.VOICE_ASSISTANT -> "Voice assistant active"
            InterruptionSource.VOICE_RECORDER -> "Another app is using microphone"
            InterruptionSource.MUSIC_PLAYER -> "Music playback started"
            InterruptionSource.VIDEO_PLAYER -> "Video playback started"
            InterruptionSource.GAME -> "Game audio"
            InterruptionSource.NAVIGATION -> "Navigation guidance"
            InterruptionSource.NOTIFICATION -> "Notification"
            InterruptionSource.UNKNOWN -> "Unknown interruption"
        }
    }
}