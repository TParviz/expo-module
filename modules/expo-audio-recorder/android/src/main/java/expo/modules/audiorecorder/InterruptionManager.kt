// package expo.modules.audiorecorder

// import android.content.Context
// import android.media.AudioAttributes
// import android.media.AudioManager
// import android.media.AudioRecordingConfiguration
// import android.os.Build
// import android.telephony.TelephonyManager
// import android.util.Log

// /**
//  * Источники прерываний
//  */
// enum class InterruptionSource {
//     PHONE_CALL,        // Телефонный звонок
//     VOIP_CALL,         // VoIP (WhatsApp, Telegram, Zoom)
//     VOICE_ASSISTANT,   // Голосовой ассистент (Siri, Google Assistant)
//     VOICE_RECORDER,    // Другой диктофон
//     MUSIC_PLAYER,      // Музыкальный плеер
//     VIDEO_PLAYER,      // Видео приложение
//     GAME,              // Игра
//     NAVIGATION,        // Навигация
//     NOTIFICATION,      // Уведомление
//     UNKNOWN            // Неизвестно
// }

// /**
//  * Политики обработки прерываний
//  */
// enum class InterruptionPolicy {
//     PAUSE_AUTO,        // Пауза с автовозобновлением + Push
//     CONTINUE_NOTIFY,   // Продолжить + уведомление (1 раз)
//     CONTINUE_SILENT    // Продолжить молча
// }

// /**
//  * Менеджер прерываний
//  *
//  * НОВЫЕ ПОЛИТИКИ (по требованиям):
//  * - Звонок/VoIP/Диктофон/Ассистент → PAUSE_AUTO (не STOP!)
//  * - Музыка/Видео → CONTINUE_NOTIFY (с учётом Bluetooth)
//  * - Навигация/Игры → CONTINUE_SILENT
//  */
// class InterruptionManager(
//     private val context: Context,
//     private val bluetoothManager: BluetoothAudioManager,
//     private val onPause: (InterruptionSource) -> Unit,
//     private val onResume: (InterruptionSource) -> Unit,
//     private val onNotify: (InterruptionSource, String) -> Unit
// ) {
//     companion object {
//         private const val TAG = "InterruptionManager"
//     }

//     private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//     private val telephonyManager =
//         context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

//     // Политики для каждого источника
//     // ВАЖНО: Музыка и видео НЕ останавливают и НЕ паузят запись!
//     private val policies = mapOf(
//         // Перехват микрофона → ПАУЗА + авто-возобновление
//         InterruptionSource.PHONE_CALL to InterruptionPolicy.PAUSE_AUTO,
//         InterruptionSource.VOIP_CALL to InterruptionPolicy.PAUSE_AUTO,
//         InterruptionSource.VOICE_RECORDER to InterruptionPolicy.PAUSE_AUTO,
//         InterruptionSource.VOICE_ASSISTANT to InterruptionPolicy.PAUSE_AUTO,  // Изменено: теперь PAUSE_AUTO для ассистента

//         // Звук без перехвата микрофона → ПРОДОЛЖАЕМ ЗАПИСЬ
//         // Музыка/Видео не мешают записи - просто уведомляем пользователя
//         InterruptionSource.MUSIC_PLAYER to InterruptionPolicy.CONTINUE_NOTIFY,
//         InterruptionSource.VIDEO_PLAYER to InterruptionPolicy.CONTINUE_NOTIFY,

//         // Остальное → продолжаем молча
//         InterruptionSource.GAME to InterruptionPolicy.CONTINUE_SILENT,
//         InterruptionSource.NAVIGATION to InterruptionPolicy.CONTINUE_SILENT,
//         InterruptionSource.NOTIFICATION to InterruptionPolicy.CONTINUE_SILENT,
//         InterruptionSource.UNKNOWN to InterruptionPolicy.CONTINUE_SILENT
//     )

//     // Источники которые поставили на паузу
//     private val pausedBySources = mutableSetOf<InterruptionSource>()

//     // Источники о которых уже уведомляли
//     private val notifiedSources = mutableSetOf<InterruptionSource>()

//     // Кэш детекции
//     private var lastDetectedSource: InterruptionSource? = null
//     private var lastDetectionTime: Long = 0
//     private val CACHE_DURATION_MS = 500L

//     /**
//      * Обработать потерю аудио фокуса
//      */
//     fun handleAudioFocusLoss(focusChange: Int) {
//         val source = detectInterruptionSource(focusChange)
//         val policy = policies[source] ?: InterruptionPolicy.CONTINUE_SILENT

//         Log.d(TAG, "Focus loss: source=$source, policy=$policy, focusChange=$focusChange")

//         // ВАЖНО: Музыка и видео НИКОГДА не должны останавливать запись
//         // Даже если детекция ошиблась, проверяем ещё раз
//         val safePolicy = when {
//             // Если это точно НЕ микрофонное прерывание - продолжаем
//             !isMicrophoneInterruption(source, focusChange) -> {
//                 Log.d(TAG, "Not a microphone interruption, continuing recording")
//                 if (policy == InterruptionPolicy.PAUSE_AUTO) {
//                     InterruptionPolicy.CONTINUE_NOTIFY
//                 } else {
//                     policy
//                 }
//             }

//             else -> policy
//         }

//         when (safePolicy) {
//             InterruptionPolicy.PAUSE_AUTO -> {
//                 Log.i(TAG, "Auto-pausing due to $source")
//                 pausedBySources.add(source)
//                 onPause(source)
//             }

//             InterruptionPolicy.CONTINUE_NOTIFY -> {
//                 // Проверяем Bluetooth для правильного уведомления
//                 val message = getMusicNotificationMessage(source)

//                 if (!notifiedSources.contains(source) && message.isNotEmpty()) {
//                     Log.i(TAG, "Continuing with notification: $source")
//                     notifiedSources.add(source)
//                     onNotify(source, message)
//                 } else {
//                     Log.d(TAG, "Continuing silently (already notified or no message): $source")
//                 }
//             }

//             InterruptionPolicy.CONTINUE_SILENT -> {
//                 Log.d(TAG, "Continuing silently: $source")
//                 // Ничего не делаем
//             }
//         }
//     }

//     /**
//      * Проверить является ли это прерывание микрофонным (требует паузу)
//      *
//      * Микрофонные прерывания:
//      * - Телефонный звонок (TelephonyManager показывает звонок)
//      * - VoIP звонок (MODE_IN_COMMUNICATION + USAGE_VOICE_COMMUNICATION)
//      * - Голосовой ассистент (USAGE_ASSISTANT)
//      *
//      * НЕ микрофонные (запись продолжается):
//      * - Музыка (USAGE_MEDIA + CONTENT_TYPE_MUSIC)
//      * - Видео (USAGE_MEDIA + CONTENT_TYPE_MOVIE)
//      * - Игры (USAGE_GAME)
//      * - Навигация (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
//      * - Уведомления
//      */
//     private fun isMicrophoneInterruption(source: InterruptionSource, focusChange: Int): Boolean {
//         // Проверяем телефонию напрямую
//         if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
//             Log.d(TAG, "isMicrophoneInterruption: YES (phone call active)")
//             return true
//         }

//         // Проверяем audio mode
//         val mode = audioManager.mode
//         if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) {
//             Log.d(TAG, "isMicrophoneInterruption: YES (audio mode = $mode)")
//             return true
//         }

//         // MODE_IN_COMMUNICATION может быть VoIP
//         if (mode == AudioManager.MODE_IN_COMMUNICATION) {
//             // Дополнительно проверяем - есть ли реально VoIP сессия
//             if (hasActiveVoIPSession()) {
//                 Log.d(TAG, "isMicrophoneInterruption: YES (VoIP session detected)")
//                 return true
//             }
//         }

//         // Проверяем активные recording сессии для ассистента/recorder
//         val hasActiveRecording = hasActiveRecordingSession()
//         if (hasActiveRecording && source == InterruptionSource.VOICE_ASSISTANT) {
//             Log.d(TAG, "isMicrophoneInterruption: NO (voice assistant via recording - continue)")
//             return false
//         }

//         // Проверяем тип источника
//         return when (source) {
//             InterruptionSource.PHONE_CALL,
//             InterruptionSource.VOIP_CALL,
//             InterruptionSource.VOICE_RECORDER -> {
//                 // Но даже для этих источников перепроверяем
//                 // Если играет музыка - это скорее всего ложное срабатывание
//                 val isMusicPlaying = audioManager.isMusicActive
//                 if (isMusicPlaying && source == InterruptionSource.VOICE_RECORDER) {
//                     Log.d(TAG, "isMicrophoneInterruption: NO (music is playing, false positive)")
//                     false
//                 } else {
//                     Log.d(TAG, "isMicrophoneInterruption: YES (source = $source)")
//                     true
//                 }
//             }
//             // VOICE_ASSISTANT - не считаем микрофонным прерыванием
//             // Запись продолжается, просто записывается тишина
//             InterruptionSource.VOICE_ASSISTANT -> {
//                 Log.d(TAG, "isMicrophoneInterruption: NO (voice assistant - continue recording)")
//                 false
//             }

//             else -> {
//                 Log.d(TAG, "isMicrophoneInterruption: NO (source = $source)")
//                 false
//             }
//         }
//     }

//     /**
//      * Проверить есть ли активная VoIP сессия (обновлено для recording)
//      */
//     private fun hasActiveVoIPSession(): Boolean {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             // Проверяем playback
//             val playbackConfigs = audioManager.activePlaybackConfigurations
//             for (config in playbackConfigs) {
//                 if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
//                     return true
//                 }
//             }
//             // Проверяем recording (API 31+)
// //            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
// //                val recordingConfigs = audioManager.activeRecordingConfigurations
// //                for (config in recordingConfigs) {
// //                    if (config.clientAudioAttributes?.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
// //                        return true
// //                    }
// //                }
// //            }
//         }
//         return false
//     }

//     /**
//      * Проверить есть ли активная recording сессия
//      */
//     private fun hasActiveRecordingSession(): Boolean {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             return audioManager.activeRecordingConfigurations.isNotEmpty()
//         }
//         return false
//     }

//     /**
//      * Обработать возврат аудио фокуса
//      */
//     fun handleAudioFocusGain() {
//         Log.d(TAG, "Focus gained, paused sources: $pausedBySources")

//         if (pausedBySources.isNotEmpty()) {
//             val source = pausedBySources.first()
//             Log.i(TAG, "Auto-resuming after $source")

//             onResume(source)

//             pausedBySources.clear()
//             notifiedSources.remove(source)
//         }
//     }

//     /**
//      * Сбросить состояние
//      */
//     fun reset() {
//         pausedBySources.clear()
//         notifiedSources.clear()
//         lastDetectedSource = null
//         lastDetectionTime = 0
//     }

//     // ==================== PRIVATE: Detection ====================

//     /**
//      * Определить источник прерывания
//      */
//     private fun detectInterruptionSource(focusChange: Int): InterruptionSource {
//         // Кэширование
//         val now = System.currentTimeMillis()
//         if (lastDetectedSource != null && now - lastDetectionTime < CACHE_DURATION_MS) {
//             return lastDetectedSource!!
//         }

//         Log.d(TAG, "=== Detecting interruption source ===")

//         // 1. Проверяем телефонию
//         val callState = telephonyManager.callState
//         Log.d(TAG, "Call state: $callState")

//         if (callState != TelephonyManager.CALL_STATE_IDLE) {
//             return cacheAndReturn(InterruptionSource.PHONE_CALL)
//         }

//         // 2. Проверяем audio mode
//         val mode = audioManager.mode
//         Log.d(TAG, "Audio mode: $mode")

//         when (mode) {
//             AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE -> {
//                 return cacheAndReturn(InterruptionSource.PHONE_CALL)
//             }

//             AudioManager.MODE_IN_COMMUNICATION -> {
//                 // VoIP или другое приложение использует микрофон
//                 return if (isLikelyVoIPCall()) {
//                     cacheAndReturn(InterruptionSource.VOIP_CALL)
//                 } else {
//                     cacheAndReturn(InterruptionSource.VOICE_RECORDER)
//                 }
//             }
//         }

//         // 3. Анализируем активные аудио сессии (playback и recording)
//         val audioInfo = getActiveAudioInfo()
//         Log.d(TAG, "Audio info: $audioInfo")

//         // VoIP из playback/recording
//         if (audioInfo.hasVoIP) {
//             return cacheAndReturn(InterruptionSource.VOIP_CALL)
//         }

//         if (audioInfo.hasMusic && isMusicPlaying()) {
//             return InterruptionSource.MUSIC_PLAYER
//         }

//         // Ассистент из playback/recording
//         if (audioInfo.hasAssistant) {
//             return cacheAndReturn(InterruptionSource.VOICE_ASSISTANT)
//         }

//         // Если есть active recording и не определено иначе - assume VOICE_ASSISTANT или RECORDER
//         if (audioInfo.hasActiveRecording && !audioInfo.hasMusic && !audioInfo.hasMovie) {
//             Log.d(TAG, "Active recording detected without media - assuming VOICE_ASSISTANT or RECORDER")
//             return cacheAndReturn(if (isLikelyAssistant()) InterruptionSource.VOICE_ASSISTANT else InterruptionSource.VOICE_RECORDER)
//         }

//         // 4. Анализируем по типу потери фокуса
//         return cacheAndReturn(detectByFocusType(focusChange, audioInfo))
//     }

//     private fun isMusicPlaying(): Boolean {
//         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
//             // Fallback для старых устройств (редко нужно)
//             return audioManager.isMusicActive // Или просто return false
//         }

//         val configs = audioManager.activePlaybackConfigurations
//         for (config in configs) {
//             val attrs = config.audioAttributes
//             if (attrs.usage == AudioAttributes.USAGE_MEDIA &&
//                 attrs.contentType == AudioAttributes.CONTENT_TYPE_MUSIC) {
//                 Log.d(TAG, "Music detected: usage=${attrs.usage}, contentType=${attrs.contentType}")
//                 return true
//             }
//         }
//         return false
//     }

//     private fun cacheAndReturn(source: InterruptionSource): InterruptionSource {
//         lastDetectedSource = source
//         lastDetectionTime = System.currentTimeMillis()
//         Log.d(TAG, "Detected source: $source")
//         return source
//     }

//     /**
//      * Определить по типу потери фокуса (обновлено с лучшим fallback для музыки/видео)
//      */
//     private fun detectByFocusType(focusChange: Int, audioInfo: AudioInfo): InterruptionSource {
//         // ВАЖНО: Сначала проверяем музыку/видео - они имеют приоритет
//         // чтобы случайно не заблокировать запись

//         // Проверяем играет ли музыка через системный API
//         val isMusicActive = audioManager.isMusicActive
//         if (isMusicActive) {
//             Log.d(TAG, "detectByFocusType: isMusicActive=true, returning MUSIC_PLAYER")
//             return InterruptionSource.MUSIC_PLAYER
//         }

//         return when (focusChange) {
//             AudioManager.AUDIOFOCUS_LOSS -> {
//                 // Постоянная потеря - скорее всего медиа приложение
//                 when {
//                     audioInfo.hasMusic -> InterruptionSource.MUSIC_PLAYER
//                     audioInfo.hasMovie -> InterruptionSource.VIDEO_PLAYER
//                     audioInfo.hasGame -> InterruptionSource.GAME
//                     // Изменено: Fallback на MUSIC_PLAYER вместо UNKNOWN, если LOSS (часто музыка/видео)
//                     else -> {
//                         Log.d(
//                             TAG,
//                             "detectByFocusType: AUDIOFOCUS_LOSS but unknown source, assuming MUSIC_PLAYER"
//                         )
//                         InterruptionSource.MUSIC_PLAYER
//                     }
//                 }
//             }

//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
//                 // Временная потеря
//                 when {
//                     audioInfo.hasNavigation -> InterruptionSource.NAVIGATION
//                     audioInfo.hasMusic -> InterruptionSource.MUSIC_PLAYER
//                     audioInfo.hasMovie -> InterruptionSource.VIDEO_PLAYER
//                     // Изменено: Если active recording, assume ASSISTANT вместо NOTIFICATION
//                     audioInfo.hasActiveRecording -> InterruptionSource.VOICE_ASSISTANT
//                     else -> {
//                         Log.d(
//                             TAG,
//                             "detectByFocusType: AUDIOFOCUS_LOSS_TRANSIENT but unknown, assuming notification"
//                         )
//                         InterruptionSource.NOTIFICATION
//                     }
//                 }
//             }

//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
//                 // Можно приглушить - обычно навигация или уведомление
//                 // Это НИКОГДА не должно останавливать запись
//                 when {
//                     audioInfo.hasNavigation -> InterruptionSource.NAVIGATION
//                     audioInfo.hasActiveRecording -> InterruptionSource.VOICE_ASSISTANT  // Новое: для ассистента
//                     else -> InterruptionSource.NOTIFICATION
//                 }
//             }

//             else -> InterruptionSource.UNKNOWN
//         }
//     }

//     /**
//      * Проверить похоже ли на VoIP звонок
//      */
//     private fun isLikelyVoIPCall(): Boolean {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             val configs = audioManager.activePlaybackConfigurations
//             for (config in configs) {
//                 if (config.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
//                     return true
//                 }
//             }
//         }

//         // Эвристика: громкая связь включена
//         return audioManager.isSpeakerphoneOn
//     }

//     /**
//      * Эвристика для ассистента (если API <31)
//      */
//     private fun isLikelyAssistant(): Boolean {
//         // Можно добавить больше эвристик, напр. по UID процесса или пакету (требует permissions)
//         // Пока: если mode normal и recording active, assume assistant
//         return audioManager.mode == AudioManager.MODE_NORMAL && hasActiveRecordingSession()
//     }

//     /**
//      * Получить информацию об активных аудио сессиях (обновлено с recording)
//      */
//     private fun getActiveAudioInfo(): AudioInfo {
//         val info = AudioInfo()
//         Log.d(TAG, "getActiveAudioInfo start: $info")
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             try {
//                 // Playback configs
//                 val playbackConfigs = audioManager.activePlaybackConfigurations
//                 for (config in playbackConfigs) {
//                     val attrs = config.audioAttributes
//                     val usage = attrs.usage
//                     val contentType = attrs.contentType
//                     Log.d(TAG, "Playback usage: $usage, contentType: $contentType")
//                     when (usage) {
//                         AudioAttributes.USAGE_MEDIA -> {
//                             when (contentType) {
//                                 AudioAttributes.CONTENT_TYPE_MUSIC -> info.hasMusic = true
//                                 AudioAttributes.CONTENT_TYPE_MOVIE -> info.hasMovie = true
//                                 else -> info.hasMusic = true  // Fallback для unknown media
//                             }
//                         }

//                         AudioAttributes.USAGE_GAME -> info.hasGame = true
//                         AudioAttributes.USAGE_VOICE_COMMUNICATION -> info.hasVoIP = true
//                         AudioAttributes.USAGE_ASSISTANT -> info.hasAssistant = true
//                         AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> info.hasNavigation =
//                             true
//                     }
//                 }

//                 // Recording configs (для микрофонных источников)
//                 info.hasActiveRecording = hasActiveRecordingSession()
//                 if (info.hasActiveRecording) {
//                     Log.d(TAG, "Active recording detected via heuristic")
//                     // Эвристика для определения типа
//                     val mode = audioManager.mode
//                     when (mode) {
//                         AudioManager.MODE_IN_COMMUNICATION -> {
//                             info.hasVoIP = true
//                         }

//                         AudioManager.MODE_NORMAL -> {
//                             // Assume assistant для normal mode с active recording
//                             info.hasAssistant = true
//                         }

//                         else -> {
//                             // Fallback на recorder
//                             info.hasVoiceRecorder = true
//                         }
//                     }
//                 } else {
//                     Log.d(TAG, "No active recording detected")
//                 }

//             } catch (e: Exception) {
//                 Log.e(TAG, "Error getting audio info", e)
//             }
//         }

//         return info
//     }

//     /**
//      * Получить сообщение для уведомления о музыке
//      */
//     private fun getMusicNotificationMessage(source: InterruptionSource): String {
//         val btState = bluetoothManager.getBluetoothState()

//         return when {
//             btState.isBluetoothHeadset -> {
//                 // Bluetooth наушники с микрофоном
//                 // Запись через микрофон наушников, музыка отдельно
//                 "⚠️ Запись продолжается (через Bluetooth наушники)"
//             }

//             btState.isBluetoothSpeaker -> {
//                 // Bluetooth колонка без микрофона
//                 // Запись через микрофон телефона
//                 // Можно не уведомлять - музыка далеко
//                 ""
//             }

//             else -> {
//                 // Обычные динамики
//                 when (source) {
//                     InterruptionSource.MUSIC_PLAYER -> "⚠️ Запись продолжается: музыка может записаться"
//                     InterruptionSource.VIDEO_PLAYER -> "⚠️ Запись продолжается: звук видео может записаться"
//                     else -> "⚠️ Запись продолжается"
//                 }
//             }
//         }
//     }

//     /**
//      * Информация об активных аудио сессиях (расширено)
//      */
//     private data class AudioInfo(
//         var hasMusic: Boolean = false,
//         var hasMovie: Boolean = false,
//         var hasGame: Boolean = false,
//         var hasVoIP: Boolean = false,
//         var hasAssistant: Boolean = false,
//         var hasNavigation: Boolean = false,
//         var hasVoiceRecorder: Boolean = false,  // Новое: для другого recorder
//         var hasActiveRecording: Boolean = false  // Новое: флаг active recording
//     )
// }