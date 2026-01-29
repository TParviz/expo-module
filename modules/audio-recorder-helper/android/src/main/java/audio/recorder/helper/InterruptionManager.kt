package audio.recorder.helper

import android.content.Context
import android.content.Intent
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
 * 
 * АВТОМАТИЧЕСКАЯ ПАУЗА:
 * При PAUSE_AUTO автоматически вызывается onPauseRequested для паузы записи.
 * При восстановлении фокуса вызывается onResumeRequested.
 */
class InterruptionManager(
    private val context: Context,
    private val bluetoothManager: BluetoothAudioManager,
    private val onInterruption: (InterruptionInfo) -> Unit,
    private val onInterruptionEnd: (InterruptionSource) -> Unit,
    // NEW: Callbacks для управления записью
    private val onPauseRequested: ((InterruptionSource) -> Unit)? = null,
    private val onResumeRequested: ((InterruptionSource) -> Unit)? = null
) {
    companion object {
        private const val TAG = "InterruptionManager"

        // Задержка перед детекцией ассистента (мс)
        // Даёт время isMusicActive обновиться
        private const val ASSISTANT_DETECTION_DELAY_MS = 150L
        
        // === Notification Source Constants ===
        const val NOTIFICATION_SOURCE_NONE = "none"
        const val NOTIFICATION_SOURCE_PHONE_CALL = "PHONE_CALL"
        const val NOTIFICATION_SOURCE_VOIP_CALL = "VOIP_CALL"
        const val NOTIFICATION_SOURCE_VOICE_ASSISTANT = "VOICE_ASSISTANT"
        const val NOTIFICATION_SOURCE_VOICE_RECORDER = "VOICE_RECORDER"
        const val NOTIFICATION_SOURCE_MUSIC_PLAYER = "MUSIC_PLAYER"
        const val NOTIFICATION_SOURCE_VIDEO_PLAYER = "VIDEO_PLAYER"
        const val NOTIFICATION_SOURCE_GAME = "GAME"
        const val NOTIFICATION_SOURCE_NAVIGATION = "NAVIGATION"
        const val NOTIFICATION_SOURCE_NOTIFICATION = "NOTIFICATION"
        const val NOTIFICATION_SOURCE_UNKNOWN = "UNKNOWN"
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
    
    // Callback для обновления уведомлений
    private var notificationCallback: ((isPaused: Boolean, source: InterruptionSource, isBluetoothHeadset: Boolean) -> Unit)? = null

    fun addMediaStateListener(listener: (Boolean, InterruptionSource) -> Unit) {
        mediaListeners.add(listener)
    }

    fun removeMediaStateListener(listener: (Boolean, InterruptionSource) -> Unit) {
        mediaListeners.remove(listener)
    }
    
    /**
     * Установить callback для обновления уведомлений
     */
    fun setNotificationCallback(callback: ((isPaused: Boolean, source: InterruptionSource, isBluetoothHeadset: Boolean) -> Unit)?) {
        notificationCallback = callback
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

        hasMusicPlayback = foundMusic
        hasVideoPlayback = foundVideo
        hasAssistantPlayback = foundAssistant
        lastPlaybackUpdate = System.currentTimeMillis()

        val currentSource = when {
            foundVideo -> InterruptionSource.VIDEO_PLAYER
            foundMusic -> InterruptionSource.MUSIC_PLAYER
            else -> null
        }

        // Notify listeners
        if (currentSource != null && currentSource != prevSource) {
            lastMediaSource = currentSource
            notifyMediaListeners(true, currentSource)
        } else if (currentSource == null && prevSource != null) {
            notifyMediaListeners(false, prevSource)
        }
    }

    private fun notifyMediaListeners(isPlaying: Boolean, source: InterruptionSource) {
        synchronized(mediaListeners) {
            for (listener in mediaListeners) {
                try {
                    listener(isPlaying, source)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying media listener", e)
                }
            }
        }
    }

    /**
     * Обрабатывает потерю аудио фокуса
     */
    fun handleAudioFocusLoss(focusChange: Int) {
        Log.d(TAG, "=== Audio focus loss: $focusChange ===")

        // Для AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK делаем отложенную проверку
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Log.d(TAG, "Scheduling delayed assistant check for DUCK focus change")
            scheduleDelayedAssistantCheck(focusChange)
            return
        }

        // Для остальных типов - сразу обрабатываем
        Log.d(TAG, "Processing interruption immediately for focus change: $focusChange")
        processInterruption(focusChange)
    }

    private fun scheduleDelayedAssistantCheck(focusChange: Int) {
        if (pendingAssistantCheck) {
            Log.d(TAG, "Delayed check already pending, skipping")
            return
        }
        
        pendingAssistantCheck = true
        Log.d(TAG, "Scheduling delayed check in ${ASSISTANT_DETECTION_DELAY_MS}ms")

        mainHandler.postDelayed({
            pendingAssistantCheck = false
            Log.d(TAG, "=== Executing delayed assistant check ===")

            // Проверяем: если музыка играет — это музыка, не ассистент
            val musicActive = try { audioManager.isMusicActive } catch (e: Exception) { false }
            Log.d(TAG, "Music state: active=$musicActive, hasPlayback=$hasMusicPlayback, hasVideo=$hasVideoPlayback")
            
            if (musicActive || hasMusicPlayback || hasVideoPlayback) {
                Log.d(TAG, "Delayed check: music is playing, not assistant")
                processInterruption(focusChange)
                return@postDelayed
            }

            // Проверяем ассистента
            val isAssistant = isAssistantUsingMicrophoneSafe()
            Log.d(TAG, "Assistant check result: $isAssistant")
            
            if (isAssistant) {
                Log.d(TAG, "Delayed check: ASSISTANT DETECTED - will PAUSE_AUTO")
                val source = InterruptionSource.VOICE_ASSISTANT
                val policy = policies[source] ?: InterruptionPolicy.PAUSE_AUTO
                val message = getNotificationMessage(source, policy)
                
                val info = InterruptionInfo(source, policy, focusChange, message)
                handleInterruptionInternal(info)
            } else {
                Log.d(TAG, "Delayed check: no assistant, processing as regular interruption")
                processInterruption(focusChange)
            }
        }, ASSISTANT_DETECTION_DELAY_MS)
    }

    private fun processInterruption(focusChange: Int) {
        val source = detectInterruptionSource(focusChange)
        val policy = policies[source] ?: InterruptionPolicy.CONTINUE_SILENT
        val message = getNotificationMessage(source, policy)

        Log.d(TAG, "=== Interruption detected: source=$source, policy=$policy ===")

        val info = InterruptionInfo(source, policy, focusChange, message)
        handleInterruptionInternal(info)
    }

    private fun handleInterruptionInternal(info: InterruptionInfo) {
        Log.d(TAG, "=== handleInterruptionInternal: source=${info.source}, policy=${info.policy} ===")

        when (info.policy) {
            InterruptionPolicy.PAUSE_AUTO -> {
                Log.d(TAG, "✅ PAUSE_AUTO policy - adding to pausedBySources and requesting pause")
                
                pausedBySources.add(info.source)
                notifiedSources.remove(info.source)
                
                Log.d(TAG, "PausedBySources now contains: ${pausedBySources.joinToString()}")
                
                // Обновляем уведомление - ПАУЗА
                updateNotification(isPaused = true, source = info.source)
                
                // ✅ КРИТИЧНО: Вызываем паузу записи
                Log.d(TAG, "🎙️ Invoking onPauseRequested for source: ${info.source}")
                try {
                    onPauseRequested?.invoke(info.source)
                    Log.d(TAG, "✅ onPauseRequested callback invoked successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error invoking onPauseRequested", e)
                }
                
                // Отправляем событие прерывания
                try {
                    onInterruption(info)
                    Log.d(TAG, "✅ onInterruption callback invoked successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error invoking onInterruption", e)
                }
            }
            
            InterruptionPolicy.CONTINUE_NOTIFY -> {
                if (!notifiedSources.contains(info.source)) {
                    Log.d(TAG, "✅ CONTINUE_NOTIFY policy - notifying once")
                    notifiedSources.add(info.source)
                    
                    // Обновляем уведомление - ПРОДОЛЖАЕТ ЗАПИСЬ
                    updateNotification(isPaused = false, source = info.source)
                    
                    try {
                        onInterruption(info)
                        Log.d(TAG, "✅ onInterruption callback invoked for CONTINUE_NOTIFY")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error invoking onInterruption", e)
                    }
                } else {
                    Log.d(TAG, "⏭️ CONTINUE_NOTIFY - already notified for ${info.source}, skipping")
                }
            }
            
            InterruptionPolicy.CONTINUE_SILENT -> {
                Log.d(TAG, "🔇 CONTINUE_SILENT policy - doing nothing")
                // Ничего не делаем
            }
        }
    }

    /**
     * Обрабатывает получение аудио фокуса
     */
    fun handleAudioFocusGain() {
        Log.d(TAG, "=== Audio focus GAINED ===")
        Log.d(TAG, "PausedBySources before resume: ${pausedBySources.joinToString()}")

        // Копируем список для безопасной итерации
        val sourcesToResume = pausedBySources.toList()
        
        if (sourcesToResume.isEmpty()) {
            Log.d(TAG, "No sources to resume, clearing notified sources")
            notifiedSources.clear()
            return
        }
        
        pausedBySources.clear()
        notifiedSources.clear()
        
        Log.d(TAG, "Resuming ${sourcesToResume.size} source(s): ${sourcesToResume.joinToString()}")

        for (source in sourcesToResume) {
            Log.d(TAG, "=== Processing resume for source: $source ===")
            
            // Возвращаем обычное уведомление
            updateNotification(isPaused = false, source = InterruptionSource.UNKNOWN)
            
            // ✅ КРИТИЧНО: Вызываем возобновление записи
            Log.d(TAG, "🎙️ Invoking onResumeRequested for source: $source")
            try {
                onResumeRequested?.invoke(source)
                Log.d(TAG, "✅ onResumeRequested callback invoked successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error invoking onResumeRequested", e)
            }
            
            // Отправляем событие окончания прерывания
            try {
                onInterruptionEnd(source)
                Log.d(TAG, "✅ onInterruptionEnd callback invoked successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error invoking onInterruptionEnd", e)
            }
        }
    }

    /**
     * Проверить есть ли активное воспроизведение
     */
    fun hasActiveMediaPlayback(): Boolean {
        // Свежие данные из callback
        if (System.currentTimeMillis() - lastPlaybackUpdate < 1000) {
            return hasMusicPlayback || hasVideoPlayback
        }
        // Fallback на isMusicActive
        return try { audioManager.isMusicActive } catch (e: Exception) { false }
    }

    fun reset() {
        Log.d(TAG, "Resetting InterruptionManager state")
        pausedBySources.clear()
        notifiedSources.clear()
        lastDetectedSource = null
        lastDetectionTime = 0
        lastMediaSource = null
        pendingAssistantCheck = false
        hasMusicPlayback = false
        hasVideoPlayback = false
        hasAssistantPlayback = false
    }
    
    // ============================================================
    // NOTIFICATION HELPERS
    // ============================================================
    
    /**
     * Обновить уведомление foreground service
     */
    private fun updateNotification(isPaused: Boolean, source: InterruptionSource) {
        val btState = bluetoothManager.getBluetoothState()
        
        Log.d(TAG, "Updating notification: isPaused=$isPaused, source=$source, bt=${btState.isBluetoothHeadset}")
        
        // Используем callback если установлен
        try {
            notificationCallback?.invoke(isPaused, source, btState.isBluetoothHeadset)
            Log.d(TAG, "✅ Notification callback invoked")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error invoking notification callback", e)
        }
        
        // Также отправляем Intent в RecordingForegroundService
        try {
            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "expo.modules.audiorecorder.RecordingForegroundService"
                )
                action = if (isPaused) "UPDATE_PAUSED" else "UPDATE_RECORDING"
                putExtra("source", source.name)
                putExtra("isBluetoothHeadset", btState.isBluetoothHeadset)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "✅ Broadcast sent to RecordingForegroundService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send broadcast to RecordingForegroundService", e)
        }
    }

    // ============================================================
    // DETECTION LOGIC
    // ============================================================

    private fun detectInterruptionSource(focusChange: Int): InterruptionSource {
        Log.d(TAG, "=== Detecting interruption source for focusChange=$focusChange ===")
        
        // Используем кэш если запрос недавний
        if (lastDetectedSource != null &&
            System.currentTimeMillis() - lastDetectionTime < CACHE_DURATION_MS) {
            Log.d(TAG, "Using cached source: $lastDetectedSource")
            return lastDetectedSource!!
        }

        val audioInfo = getActiveAudioInfo()
        Log.d(TAG, "AudioInfo: music=${audioInfo.hasMusic}, video=${audioInfo.hasMovie}, " +
                "voip=${audioInfo.hasVoIP}, assistant=${audioInfo.hasAssistant}, " +
                "game=${audioInfo.hasGame}, nav=${audioInfo.hasNavigation}")

        // Телефонный звонок
        val callState = try { telephonyManager.callState } catch (e: Exception) { TelephonyManager.CALL_STATE_IDLE }
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.d(TAG, "Detected: PHONE_CALL (callState=$callState)")
            return cacheAndReturn(InterruptionSource.PHONE_CALL)
        }

        // VoIP звонок
        val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }
        if (mode == AudioManager.MODE_IN_COMMUNICATION || audioInfo.hasVoIP) {
            Log.d(TAG, "Detected: VOIP_CALL (mode=$mode, hasVoIP=${audioInfo.hasVoIP})")
            return cacheAndReturn(InterruptionSource.VOIP_CALL)
        }

        // Голосовой ассистент (только если уже подтверждён через delayed check)
        if (audioInfo.hasAssistant || hasAssistantPlayback) {
            Log.d(TAG, "Detected: VOICE_ASSISTANT (hasAssistant=${audioInfo.hasAssistant}, hasAssistantPlayback=$hasAssistantPlayback)")
            return cacheAndReturn(InterruptionSource.VOICE_ASSISTANT)
        }

        // Полная потеря фокуса обычно означает музыку
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            Log.d(TAG, "Detected: MUSIC_PLAYER (AUDIOFOCUS_LOSS)")
            return cacheAndReturn(InterruptionSource.MUSIC_PLAYER)
        }

        val detectedSource = detectByFocusType(focusChange, audioInfo)
        Log.d(TAG, "Detected by focus type: $detectedSource")
        return cacheAndReturn(detectedSource)
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

        return info
    }

    private fun isAssistantUsingMicrophoneSafe(): Boolean {
        return try { 
            val result = isAssistantUsingMicrophone()
            Log.d(TAG, "isAssistantUsingMicrophone result: $result")
            result
        } catch (e: Exception) { 
            Log.e(TAG, "Error checking assistant microphone", e)
            false 
        }
    }

    private fun isAssistantUsingMicrophone(): Boolean {
        try {
            if (audioManager.isMusicActive) {
                Log.d(TAG, "Music is active, not assistant")
                return false
            }
        } catch (e: Exception) { /* ignore */ }

        if (hasActiveMediaPlayback()) {
            Log.d(TAG, "Has active media playback, not assistant")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val recordingConfigs = audioManager.activeRecordingConfigurations
                Log.d(TAG, "Active recording configs count: ${recordingConfigs.size}")

                if (recordingConfigs.isEmpty()) return false
                if (recordingConfigs.size == 1) return false

                var foundVoiceRecognition = false
                var foundHotword = false

                for (config in recordingConfigs) {
                    try {
                        val audioSource = config.audioSource
                        Log.d(TAG, "Recording config: audioSource=$audioSource")
                        when (audioSource) {
                            MediaRecorder.AudioSource.VOICE_RECOGNITION -> foundVoiceRecognition = true
                            1999 -> foundHotword = true // HOTWORD constant
                        }
                    } catch (e: Exception) { /* ignore entry */ }
                }

                if (foundVoiceRecognition || foundHotword) {
                    Log.d(TAG, "Found voice recognition or hotword - IS ASSISTANT")
                    return true
                }

                val callIdle = try {
                    telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
                } catch (e: Exception) { true }

                val mode = try { audioManager.mode } catch (e: Exception) { AudioManager.MODE_NORMAL }
                val notInCall = mode == AudioManager.MODE_NORMAL

                if (callIdle && notInCall && recordingConfigs.size >= 2) {
                    Log.d(TAG, "Multiple recorders + not in call = likely assistant")
                    return true
                }

                Log.d(TAG, "isAssistantUsingMicrophone: false (recorders=${recordingConfigs.size}, callIdle=$callIdle, notInCall=$notInCall)")
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