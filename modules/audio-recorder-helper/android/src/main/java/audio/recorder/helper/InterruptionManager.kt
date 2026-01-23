package audio.recorder.helper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Collections

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
    PAUSE_AUTO,        // Пауза с автовозобновлением + Push
    CONTINUE_NOTIFY,   // Продолжить + уведомление (1 раз)
    CONTINUE_SILENT    // Продолжить молча
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
 * ПОЛИТИКИ:
 * - Звонок/VoIP/Диктофон/Ассистент → PAUSE_AUTO (не STOP!)
 * - Музыка/Видео → CONTINUE_NOTIFY (с учётом Bluetooth)
 * - Навигация/Игры → CONTINUE_SILENT
 */
class InterruptionManager(
    private val context: Context,
    private val bluetoothManager: BluetoothAudioManager,
    private val onInterruption: (InterruptionInfo) -> Unit,
    private val onInterruptionEnd: (InterruptionSource) -> Unit
) {
    companion object {
        private const val TAG = "InterruptionManager"

        // Задержка перед детекцией ассистента (мс)
        // Даёт время isMusicActive обновиться
        private const val ASSISTANT_DETECTION_DELAY_MS = 150L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Трекинг активного воспроизведения через AudioPlaybackCallback
    @Volatile private var hasMusicPlayback = false
    @Volatile private var hasVideoPlayback = false
    @Volatile private var hasAssistantPlayback = false
    @Volatile private var lastPlaybackUpdate = 0L

    // AudioPlaybackCallback для точного определения музыки/видео/ассистента
    private val playbackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                updatePlaybackState(configs)
            }
        }
    } else null

    // Политики для каждого источника
    private val policies = mapOf(
        InterruptionSource.PHONE_CALL to InterruptionPolicy.PAUSE_AUTO,
        InterruptionSource.VOIP_CALL to InterruptionPolicy.PAUSE_AUTO,
        InterruptionSource.VOICE_RECORDER to InterruptionPolicy.PAUSE_AUTO,
        InterruptionSource.VOICE_ASSISTANT to InterruptionPolicy.PAUSE_AUTO,

        InterruptionSource.MUSIC_PLAYER to InterruptionPolicy.CONTINUE_NOTIFY,
        InterruptionSource.VIDEO_PLAYER to InterruptionPolicy.CONTINUE_NOTIFY,

        InterruptionSource.GAME to InterruptionPolicy.CONTINUE_SILENT,
        InterruptionSource.NAVIGATION to InterruptionPolicy.CONTINUE_SILENT,
        InterruptionSource.NOTIFICATION to InterruptionPolicy.CONTINUE_SILENT,
        InterruptionSource.UNKNOWN to InterruptionPolicy.CONTINUE_SILENT
    )

    // Источники которые поставили на паузу (потокобезопасные наборы)
    private val pausedBySources = Collections.synchronizedSet(mutableSetOf<InterruptionSource>())

    // Источники о которых уже уведомляли (потокобезопасные)
    private val notifiedSources = Collections.synchronizedSet(mutableSetOf<InterruptionSource>())

    // Слушатели состояния медиа
    private val mediaListeners = Collections.synchronizedSet(mutableSetOf<(Boolean, InterruptionSource) -> Unit>())

    // Кэш детекции
    private var lastDetectedSource: InterruptionSource? = null
    private var lastDetectionTime: Long = 0
    private val CACHE_DURATION_MS = 500L

    // Последний медиа-источник
    @Volatile private var lastMediaSource: InterruptionSource? = null

    // Флаг: ожидаем отложенную проверку ассистента
    @Volatile private var pendingAssistantCheck = false

    fun addMediaStateListener(listener: (Boolean, InterruptionSource) -> Unit) {
        mediaListeners.add(listener)
    }

    fun removeMediaStateListener(listener: (Boolean, InterruptionSource) -> Unit) {
        mediaListeners.remove(listener)
    }

    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            Log.d(TAG, "Starting AudioPlaybackCallback monitoring")
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
                try {
                    updatePlaybackState(audioManager.activePlaybackConfigurations)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read activePlaybackConfigurations on start", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register AudioPlaybackCallback", e)
            }
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering playback callback", e)
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
        reset()
    }

    private fun updatePlaybackState(configs: List<AudioPlaybackConfiguration>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val prevMusic = hasMusicPlayback
        val prevVideo = hasVideoPlayback
        val prevSource = when {
            prevVideo -> InterruptionSource.VIDEO_PLAYER
            prevMusic -> InterruptionSource.MUSIC_PLAYER
            else -> null
        }

        var foundMusic = false
        var foundVideo = false
        var foundAssistant = false

        configs?.forEach { config ->
            try {
                val attrs = config.audioAttributes
                when (attrs.usage) {
                    AudioAttributes.USAGE_MEDIA -> {
                        when (attrs.contentType) {
                            AudioAttributes.CONTENT_TYPE_MOVIE -> foundVideo = true
                            else -> foundMusic = true
                        }
                    }
                    AudioAttributes.USAGE_GAME -> foundMusic = true
                    AudioAttributes.USAGE_ASSISTANT -> foundAssistant = true
                    AudioAttributes.USAGE_VOICE_COMMUNICATION -> { /* voip */ }
                }
            } catch (e: Exception) {
                // ignore problematic entry
            }
        }

        if (!foundMusic && !foundVideo && !foundAssistant) {
            try {
                if (audioManager.isMusicActive) foundMusic = true
            } catch (e: Exception) { /* ignore */ }
        }

        hasMusicPlayback = foundMusic
        hasVideoPlayback = foundVideo
        hasAssistantPlayback = foundAssistant
        lastPlaybackUpdate = System.currentTimeMillis()


        val newSource = when {
            hasVideoPlayback -> InterruptionSource.VIDEO_PLAYER
            hasMusicPlayback -> InterruptionSource.MUSIC_PLAYER
            else -> null
        }

        // Если появилось медиа и была отложенная проверка ассистента — отменяем её
        if (newSource != null && pendingAssistantCheck) {
            pendingAssistantCheck = false
            mainHandler.removeCallbacksAndMessages(null)
        }

        if (prevSource != newSource) {
            prevSource?.let { ps ->
                lastMediaSource = null
                notifiedSources.remove(ps)
                synchronized(mediaListeners) {
                    mediaListeners.forEach { listener ->
                        try { listener(false, ps) } catch (e: Exception) { /* ignore */ }
                    }
                }
            }

            newSource?.let { ns ->
                lastMediaSource = ns
                synchronized(mediaListeners) {
                    mediaListeners.forEach { listener ->
                        try { listener(true, ns) } catch (e: Exception) { /* ignore */ }
                    }
                }

                val policy = policies[ns] ?: InterruptionPolicy.CONTINUE_SILENT
                if (policy == InterruptionPolicy.CONTINUE_NOTIFY) {
                    if (!notifiedSources.contains(ns)) {
                        notifiedSources.add(ns)
                        val info = InterruptionInfo(
                            ns,
                            policy,
                            AudioManager.AUDIOFOCUS_GAIN,
                            getNotificationMessage(ns, policy)
                        )
                        try {
                            onInterruption(info)
                        } catch (e: Exception) {
                            Log.w(TAG, "onInterruption listener threw on media start", e)
                        }
                    }
                }
            }
        }
    }

    fun hasActiveMediaPlayback(): Boolean {
        val isRecent = System.currentTimeMillis() - lastPlaybackUpdate < 2000
        return isRecent && (hasMusicPlayback || hasVideoPlayback)
    }

    fun handleAudioFocusLoss(focusChange: Int) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                updatePlaybackState(audioManager.activePlaybackConfigurations)
            } catch (e: Exception) {
                Log.w(TAG, "Error updating playback state", e)
            }
        }

        val audioInfo = getActiveAudioInfo()
        val isMusicActive = try { audioManager.isMusicActive } catch (e: Exception) { false }


        // [1] Media priority — если есть явные признаки media → media
        if (isMusicActive || hasActiveMediaPlayback() || audioInfo.hasMusic || audioInfo.hasMovie) {
            val source = if (audioInfo.hasMovie || hasVideoPlayback) InterruptionSource.VIDEO_PLAYER else InterruptionSource.MUSIC_PLAYER
            handleMediaInterruption(source, focusChange)
            return
        }

        // [2] Phone/VoIP check
        val callState = try { telephonyManager.callState } catch (e: Exception) { TelephonyManager.CALL_STATE_IDLE }
        val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }

        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            fireInterruption(InterruptionSource.PHONE_CALL, focusChange)
            return
        }

        when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> {
                fireInterruption(InterruptionSource.PHONE_CALL, focusChange)
                return
            }
            AudioManager.MODE_IN_COMMUNICATION -> {
                if (hasActiveVoIPSession()) {
                    fireInterruption(InterruptionSource.VOIP_CALL, focusChange)
                    return
                }
            }
        }

        // [3] Не можем сразу определить — ждём немного и проверяем снова
        // Это решает проблему race condition: музыка может ещё не успеть "зарегистрироваться"
        pendingAssistantCheck = true

        mainHandler.postDelayed({
            if (!pendingAssistantCheck) {
                Log.d(TAG, "Delayed check cancelled (media detected)")
                return@postDelayed
            }
            pendingAssistantCheck = false

            performDelayedDetection(focusChange, audioInfo)
        }, ASSISTANT_DETECTION_DELAY_MS)
    }

    /**
     * Отложенная детекция — вызывается после небольшой задержки
     */
    private fun performDelayedDetection(focusChange: Int, originalAudioInfo: AudioInfo) {
        Log.d(TAG, "=== performDelayedDetection ===")

        // Перепроверяем состояние медиа
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                updatePlaybackState(audioManager.activePlaybackConfigurations)
            } catch (e: Exception) { /* ignore */ }
        }

        val isMusicActive = try { audioManager.isMusicActive } catch (e: Exception) { false }
        val audioInfo = getActiveAudioInfo()


        // Если за время ожидания появилось медиа — это медиа, не ассистент
        if (isMusicActive || hasActiveMediaPlayback() || audioInfo.hasMusic || audioInfo.hasMovie) {
            val source = if (audioInfo.hasMovie || hasVideoPlayback) InterruptionSource.VIDEO_PLAYER else InterruptionSource.MUSIC_PLAYER
            handleMediaInterruption(source, focusChange)
            return
        }

        // Теперь безопасно проверяем ассистента
        if (isAssistantUsingMicrophoneSafe()) {
            fireInterruption(InterruptionSource.VOICE_ASSISTANT, focusChange)
            return
        }

        // Fallback детекция
        val source = detectInterruptionSourceWithAudioInfo(focusChange, audioInfo)

        val policy = policies[source] ?: InterruptionPolicy.CONTINUE_SILENT
        val safePolicy = when {
            !isMicrophoneInterruption(source, focusChange) && policy == InterruptionPolicy.PAUSE_AUTO -> InterruptionPolicy.CONTINUE_NOTIFY
            else -> policy
        }

        val info = InterruptionInfo(source, safePolicy, focusChange, getNotificationMessage(source, safePolicy))

        when (safePolicy) {
            InterruptionPolicy.PAUSE_AUTO -> {
                pausedBySources.add(source)
                onInterruption(info)
            }
            InterruptionPolicy.CONTINUE_NOTIFY -> {
                if (!notifiedSources.contains(source)) {
                    notifiedSources.add(source)
                    onInterruption(info)
                }
            }
            InterruptionPolicy.CONTINUE_SILENT -> { /* nothing */ }
        }
    }

    /**
     * Вспомогательный метод для отправки прерывания с PAUSE_AUTO политикой
     */
    private fun fireInterruption(source: InterruptionSource, focusChange: Int) {
        val policy = policies[source] ?: InterruptionPolicy.PAUSE_AUTO
        pausedBySources.add(source)
        onInterruption(InterruptionInfo(source, policy, focusChange, getNotificationMessage(source, policy)))
    }

    private fun handleMediaInterruption(source: InterruptionSource, focusChange: Int) {
        val policy = InterruptionPolicy.CONTINUE_NOTIFY
        if (!notifiedSources.contains(source)) {
            notifiedSources.add(source)
            onInterruption(InterruptionInfo(source, policy, focusChange, getNotificationMessage(source, policy)))
        }
    }

    private fun isMicrophoneInterruption(source: InterruptionSource, focusChange: Int): Boolean {
        try {
            if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) return true
        } catch (e: Exception) { /* ignore */ }

        val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) return true
        if (mode == AudioManager.MODE_IN_COMMUNICATION && hasActiveVoIPSession()) return true

        return when (source) {
            InterruptionSource.PHONE_CALL,
            InterruptionSource.VOIP_CALL,
            InterruptionSource.VOICE_ASSISTANT,
            InterruptionSource.VOICE_RECORDER -> {
                val isMusicPlaying = try { audioManager.isMusicActive } catch (e: Exception) { false }
                !(isMusicPlaying && source == InterruptionSource.VOICE_RECORDER)
            }
            else -> false
        }
    }

    private fun hasActiveVoIPSession(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val configs = audioManager.activePlaybackConfigurations
                for (config in configs) {
                    if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) return true
                }
            } catch (e: Exception) { /* ignore */ }
        }
        return try { audioManager.isSpeakerphoneOn } catch (e: Exception) { false }
    }

    fun handleAudioFocusGain() {
        // Отменяем отложенную проверку если была
        pendingAssistantCheck = false
        mainHandler.removeCallbacksAndMessages(null)

        synchronized(pausedBySources) {
            if (pausedBySources.isNotEmpty()) {
                val source = pausedBySources.first()
                onInterruptionEnd(source)
                pausedBySources.clear()
                notifiedSources.remove(source)
            }
        }
    }

    fun reset() {
        pendingAssistantCheck = false
        mainHandler.removeCallbacksAndMessages(null)
        pausedBySources.clear()
        notifiedSources.clear()
        lastDetectedSource = null
        lastDetectionTime = 0
        hasMusicPlayback = false
        hasVideoPlayback = false
        hasAssistantPlayback = false
        lastPlaybackUpdate = 0
        lastMediaSource = null
        mediaListeners.clear()
    }

    fun getPolicy(source: InterruptionSource): InterruptionPolicy {
        return policies[source] ?: InterruptionPolicy.CONTINUE_SILENT
    }

    private fun detectInterruptionSourceWithAudioInfo(focusChange: Int, audioInfoParam: AudioInfo? = null): InterruptionSource {
        val now = System.currentTimeMillis()
        if (lastDetectedSource != null && now - lastDetectionTime < CACHE_DURATION_MS) return lastDetectedSource!!

        val audioInfo = audioInfoParam ?: getActiveAudioInfo()

        if (hasMusicPlayback) return cacheAndReturn(InterruptionSource.MUSIC_PLAYER)
        if (hasVideoPlayback) return cacheAndReturn(InterruptionSource.VIDEO_PLAYER)

        val musicActive = try { audioManager.isMusicActive } catch (e: Exception) { false }
        if (musicActive) return cacheAndReturn(InterruptionSource.MUSIC_PLAYER)

        val callState = try { telephonyManager.callState } catch (e: Exception) { TelephonyManager.CALL_STATE_IDLE }
        if (callState != TelephonyManager.CALL_STATE_IDLE) return cacheAndReturn(InterruptionSource.PHONE_CALL)

        val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }
        when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> return cacheAndReturn(InterruptionSource.PHONE_CALL)
            AudioManager.MODE_IN_COMMUNICATION -> return if (isLikelyVoIPCall()) cacheAndReturn(InterruptionSource.VOIP_CALL) else cacheAndReturn(InterruptionSource.VOICE_RECORDER)
        }

        if (audioInfo.hasMusic) return cacheAndReturn(InterruptionSource.MUSIC_PLAYER)
        if (audioInfo.hasMovie) return cacheAndReturn(InterruptionSource.VIDEO_PLAYER)
        if (audioInfo.hasVoIP) return cacheAndReturn(InterruptionSource.VOIP_CALL)

        // Ассистент проверяем только если есть явные признаки (USAGE_ASSISTANT)
        if (audioInfo.hasAssistant || hasAssistantPlayback) {
            return cacheAndReturn(InterruptionSource.VOICE_ASSISTANT)
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) return cacheAndReturn(InterruptionSource.MUSIC_PLAYER)

        return cacheAndReturn(detectByFocusType(focusChange, audioInfo))
    }

    private fun cacheAndReturn(source: InterruptionSource): InterruptionSource {
        lastDetectedSource = source
        lastDetectionTime = System.currentTimeMillis()
        return source
    }

    private fun detectByFocusType(focusChange: Int, audioInfo: AudioInfo): InterruptionSource {
        val musicActive = try { audioManager.isMusicActive } catch (e: Exception) { false }
        if (musicActive) return InterruptionSource.MUSIC_PLAYER
        if (audioInfo.hasAssistant) return InterruptionSource.VOICE_ASSISTANT

        return when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> when {
                audioInfo.hasMusic -> InterruptionSource.MUSIC_PLAYER
                audioInfo.hasMovie -> InterruptionSource.VIDEO_PLAYER
                audioInfo.hasGame -> InterruptionSource.GAME
                else -> InterruptionSource.UNKNOWN
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> when {
                audioInfo.hasNavigation -> InterruptionSource.NAVIGATION
                audioInfo.hasMusic -> InterruptionSource.MUSIC_PLAYER
                else -> InterruptionSource.NOTIFICATION
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> when {
                audioInfo.hasNavigation -> InterruptionSource.NAVIGATION
                else -> InterruptionSource.NOTIFICATION
            }
            else -> InterruptionSource.UNKNOWN
        }
    }

    private fun isLikelyVoIPCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val configs = audioManager.activePlaybackConfigurations
                for (config in configs) {
                    if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) return true
                }
            } catch (e: Exception) { /* ignore */ }
        }
        return try { audioManager.isSpeakerphoneOn } catch (e: Exception) { false }
    }

    private fun getActiveAudioInfo(): AudioInfo {
        val info = AudioInfo()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val configs = audioManager.activePlaybackConfigurations
                for (config in configs) {
                    try {
                        val attrs = config.audioAttributes
                        when (attrs.usage) {
                            AudioAttributes.USAGE_MEDIA -> {
                                when (attrs.contentType) {
                                    AudioAttributes.CONTENT_TYPE_MOVIE -> info.hasMovie = true
                                    else -> info.hasMusic = true
                                }
                            }
                            AudioAttributes.USAGE_GAME -> info.hasGame = true
                            AudioAttributes.USAGE_VOICE_COMMUNICATION -> info.hasVoIP = true
                            AudioAttributes.USAGE_ASSISTANT -> info.hasAssistant = true
                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> info.hasNavigation = true
                        }
                    } catch (e: Exception) { /* ignore entry */ }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        try {
            if (!info.hasMusic && !info.hasMovie && audioManager.isMusicActive) info.hasMusic = true
        } catch (e: Exception) { /* ignore */ }

        // НЕ вызываем isAssistantUsingMicrophone здесь — это делается отдельно в delayed check
        // Это ключевое изменение: убираем ложные срабатывания

        return info
    }

    /**
     * Проверка использования микрофона ассистентом
     * ВАЖНО: вызывать только после задержки, когда уже точно известно что это не медиа
     */
    private fun isAssistantUsingMicrophoneSafe(): Boolean {
        return try { isAssistantUsingMicrophone() } catch (e: Exception) { false }
    }

    private fun isAssistantUsingMicrophone(): Boolean {
        // Если музыка играет — точно не ассистент
        try {
            if (audioManager.isMusicActive) return false
        } catch (e: Exception) { /* ignore */ }

        // Если есть активное медиа воспроизведение — не ассистент
        if (hasActiveMediaPlayback()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val recordingConfigs = audioManager.activeRecordingConfigurations

                // Если нет записей или только одна (наш диктофон) — не ассистент
                if (recordingConfigs.isEmpty()) {
                    return false
                }

                // Только 1 recorder = наш диктофон, не ассистент
                if (recordingConfigs.size == 1) {
                    return false
                }

                var foundVoiceRecognition = false
                var foundHotword = false

                for (config in recordingConfigs) {
                    try {
                        val audioSource = config.audioSource
                        Log.d(TAG, "Recording config: audioSource=$audioSource")
                        when (audioSource) {
                            MediaRecorder.AudioSource.VOICE_RECOGNITION -> foundVoiceRecognition = true
                            1999 -> foundHotword = true // HOTWORD
                        }
                    } catch (e: Exception) { /* ignore entry */ }
                }

                // Явные признаки ассистента
                if (foundVoiceRecognition || foundHotword) {
                    return true
                }

                // 2+ recorders без медиа и без звонка = вероятно ассистент (Google Assistant использует MIC)
                val callIdle = try {
                    telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
                } catch (e: Exception) { true }

                val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }
                val notInCall = mode == AudioManager.MODE_NORMAL

                if (callIdle && notInCall && recordingConfigs.size >= 2) {
                    return true
                }
                Log.d(TAG, "isAssistantUsingMicrophone: f" +
                        "alse (recorders=${recordingConfigs.size}, " +
                        "callIdle=$callIdle, notInCall=$notInCall)")
                return false

            } catch (e: Exception) {
                Log.w(TAG, "Error checking recording configs", e)
            }
        }
        return false
    }

    private fun getNotificationMessage(source: InterruptionSource, policy: InterruptionPolicy): String {
        if (policy == InterruptionPolicy.PAUSE_AUTO) {
            return when (source) {
                InterruptionSource.PHONE_CALL -> "Запись на паузе: входящий звонок"
                InterruptionSource.VOIP_CALL -> "Запись на паузе: VoIP звонок"
                InterruptionSource.VOICE_ASSISTANT -> "Запись на паузе: голосовой ассистент"
                InterruptionSource.VOICE_RECORDER -> "Запись на паузе: другой диктофон"
                else -> "Запись на паузе"
            }
        }

        val btState = bluetoothManager.getBluetoothState()
        return when {
            btState.isBluetoothHeadset -> "⚠️ Запись продолжается (через Bluetooth наушники)"
            btState.isBluetoothSpeaker -> ""
            else -> when (source) {
                InterruptionSource.MUSIC_PLAYER -> "⚠️ Запись продолжается: музыка может записаться"
                InterruptionSource.VIDEO_PLAYER -> "⚠️ Запись продолжается: звук видео может записаться"
                else -> "⚠️ Запись продолжается"
            }
        }
    }

    private data class AudioInfo(
        var hasMusic: Boolean = false,
        var hasMovie: Boolean = false,
        var hasGame: Boolean = false,
        var hasVoIP: Boolean = false,
        var hasAssistant: Boolean = false,
        var hasNavigation: Boolean = false
    )
}