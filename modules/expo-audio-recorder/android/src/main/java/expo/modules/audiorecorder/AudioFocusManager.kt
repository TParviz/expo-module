// package expo.modules.audiorecorder

// import android.content.Context
// import android.media.AudioAttributes
// import android.media.AudioFocusRequest
// import android.media.AudioManager
// import android.os.Build
// import android.util.Log

// /**
//  * Менеджер аудио фокуса
//  * 
//  * Запрашивает и отслеживает аудио фокус для корректной работы с другими приложениями.
//  */
// class AudioFocusManager(
//     private val context: Context,
//     private val onFocusLost: (Int) -> Unit,
//     private val onFocusGained: () -> Unit
// ) {
//     companion object {
//         private const val TAG = "AudioFocusManager"
//     }

//     private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//     private var audioFocusRequest: AudioFocusRequest? = null
//     private var hasAudioFocus = false

//     // Callback для отправки событий в JS
//     private var onFocusEvent: ((String, Map<String, Any?>) -> Unit)? = null

//     fun setFocusEventCallback(callback: (String, Map<String, Any?>) -> Unit) {
//         onFocusEvent = callback
//     }

//     private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
//         val focusName = getFocusChangeName(focusChange)
//         Log.d(TAG, "Focus change: $focusChange ($focusName)")
        
//         // Отправляем событие в JS для логирования
//         emitFocusEvent(focusChange, focusName)
        
//         when (focusChange) {
//             AudioManager.AUDIOFOCUS_LOSS -> {
//                 Log.w(TAG, "AUDIOFOCUS_LOSS - permanent loss, another app took focus")
//                 hasAudioFocus = false
//                 onFocusLost(focusChange)
//             }

//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
//                 Log.w(TAG, "AUDIOFOCUS_LOSS_TRANSIENT - temporary loss, will regain")
//                 hasAudioFocus = false
//                 onFocusLost(focusChange)
//             }

//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
//                 Log.w(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK - can lower volume")
//                 hasAudioFocus = false
//                 onFocusLost(focusChange)
//             }

//             AudioManager.AUDIOFOCUS_GAIN -> {
//                 Log.i(TAG, "AUDIOFOCUS_GAIN - focus restored")
//                 hasAudioFocus = true
//                 onFocusGained()
//             }

//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
//                 Log.i(TAG, "AUDIOFOCUS_GAIN_TRANSIENT - temporary gain")
//                 hasAudioFocus = true
//                 onFocusGained()
//             }

//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
//                 Log.i(TAG, "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK - gain with ducking")
//                 hasAudioFocus = true
//                 onFocusGained()
//             }

//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
//                 Log.i(TAG, "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE - exclusive gain")
//                 hasAudioFocus = true
//                 onFocusGained()
//             }

//             else -> {
//                 Log.w(TAG, "Unknown focus change: $focusChange")
//             }
//         }
//     }

//     private fun getFocusChangeName(focusChange: Int): String {
//         return when (focusChange) {
//             AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "AUDIOFOCUS_GAIN_TRANSIENT"
//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
//             AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
//             AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
//             AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
//             else -> "UNKNOWN($focusChange)"
//         }
//     }

//     private fun emitFocusEvent(focusChange: Int, focusName: String) {
//         onFocusEvent?.invoke("audioFocusChanged", mapOf(
//             "focusChange" to focusChange,
//             "focusName" to focusName,
//             "hasFocus" to (focusChange > 0),
//             "timestamp" to System.currentTimeMillis()
//         ))
//     }

//     /**
//      * Запросить аудио фокус
//      */
//     fun requestAudioFocus(): Boolean {
//         // Логируем текущее состояние аудио системы
//         logCurrentAudioState()
        
//         val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             val audioAttributes = AudioAttributes.Builder()
//                 .setUsage(AudioAttributes.USAGE_MEDIA)
//                 .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                 .build()

//             audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//                 .setAudioAttributes(audioAttributes)
//                 .setOnAudioFocusChangeListener(focusChangeListener)
//                 .setAcceptsDelayedFocusGain(false)
//                 .setWillPauseWhenDucked(false)
//                 .build()

//             audioManager.requestAudioFocus(audioFocusRequest!!)
//         } else {
//             @Suppress("DEPRECATION")
//             audioManager.requestAudioFocus(
//                 focusChangeListener,
//                 AudioManager.STREAM_MUSIC,
//                 AudioManager.AUDIOFOCUS_GAIN
//             )
//         }

//         hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

//         if (hasAudioFocus) {
//             Log.i(TAG, "Audio focus granted")
//         } else {
//             Log.w(TAG, "Audio focus denied: $result")
//         }

//         return hasAudioFocus
//     }

//     /**
//      * Освободить аудио фокус
//      */
//     fun abandonAudioFocus() {
//         if (!hasAudioFocus) return

//         val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             audioFocusRequest?.let {
//                 audioManager.abandonAudioFocusRequest(it)
//             } ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
//         } else {
//             @Suppress("DEPRECATION")
//             audioManager.abandonAudioFocus(focusChangeListener)
//         }

//         hasAudioFocus = false
//         Log.i(TAG, "Audio focus abandoned: $result")
//     }

//     /**
//      * Есть ли аудио фокус
//      */
//     fun hasAudioFocus(): Boolean = hasAudioFocus

//     /**
//      * Логировать текущее состояние аудио системы
//      */
//     private fun logCurrentAudioState() {
//         val mode = audioManager.mode
//         val modeName = when (mode) {
//             AudioManager.MODE_NORMAL -> "MODE_NORMAL"
//             AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
//             AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
//             AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
//             AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
//             else -> "UNKNOWN($mode)"
//         }

//         val isMusicActive = audioManager.isMusicActive
//         val isSpeakerOn = audioManager.isSpeakerphoneOn
//         val isBluetoothSco = audioManager.isBluetoothScoOn
//         val isBluetoothA2dp = audioManager.isBluetoothA2dpOn

//         Log.d(TAG, "=== Current Audio State ===")
//         Log.d(TAG, "  Mode: $modeName")
//         Log.d(TAG, "  Music active: $isMusicActive")
//         Log.d(TAG, "  Speakerphone: $isSpeakerOn")
//         Log.d(TAG, "  Bluetooth SCO: $isBluetoothSco")
//         Log.d(TAG, "  Bluetooth A2DP: $isBluetoothA2dp")

//         // Отправляем состояние в JS
//         emitAudioStateEvent(modeName, isMusicActive, isSpeakerOn, isBluetoothSco, isBluetoothA2dp)

//         // Логируем активные аудио сессии (Android 8+)
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//             try {
//                 val configs = audioManager.activePlaybackConfigurations
//                 Log.d(TAG, "  Active playback sessions: ${configs.size}")
                
//                 configs.forEachIndexed { index, config ->
//                     val usage = config.audioAttributes.usage
//                     val contentType = config.audioAttributes.contentType
//                     val usageName = getUsageName(usage)
//                     val contentTypeName = getContentTypeName(contentType)
//                     Log.d(TAG, "    [$index] Usage: $usageName, ContentType: $contentTypeName")
//                 }
//             } catch (e: Exception) {
//                 Log.e(TAG, "Error getting playback configs", e)
//             }
//         }
//         Log.d(TAG, "===========================")
//     }

//     private fun emitAudioStateEvent(
//         mode: String,
//         isMusicActive: Boolean,
//         isSpeakerOn: Boolean,
//         isBluetoothSco: Boolean,
//         isBluetoothA2dp: Boolean
//     ) {
//         onFocusEvent?.invoke("audioStateChanged", mapOf(
//             "mode" to mode,
//             "isMusicActive" to isMusicActive,
//             "isSpeakerphoneOn" to isSpeakerOn,
//             "isBluetoothSco" to isBluetoothSco,
//             "isBluetoothA2dp" to isBluetoothA2dp,
//             "timestamp" to System.currentTimeMillis()
//         ))
//     }

//     private fun getUsageName(usage: Int): String {
//         return when (usage) {
//             android.media.AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
//             android.media.AudioAttributes.USAGE_MEDIA -> "MEDIA"
//             android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
//             android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_SIGNALLING"
//             android.media.AudioAttributes.USAGE_ALARM -> "ALARM"
//             android.media.AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
//             android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
//             android.media.AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
//             android.media.AudioAttributes.USAGE_GAME -> "GAME"
//             android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
//             else -> "OTHER($usage)"
//         }
//     }

//     private fun getContentTypeName(contentType: Int): String {
//         return when (contentType) {
//             android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN -> "UNKNOWN"
//             android.media.AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
//             android.media.AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
//             android.media.AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
//             android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
//             else -> "OTHER($contentType)"
//         }
//     }
// }