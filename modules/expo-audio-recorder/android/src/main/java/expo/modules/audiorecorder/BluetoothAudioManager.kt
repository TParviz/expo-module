// package expo.modules.audiorecorder

// import android.bluetooth.BluetoothAdapter
// import android.bluetooth.BluetoothDevice
// import android.bluetooth.BluetoothProfile
// import android.content.Context
// import android.media.AudioDeviceInfo
// import android.media.AudioManager
// import android.os.Build
// import android.util.Log

// /**
//  * Менеджер Bluetooth аудио
//  * 
//  * Определяет тип подключённого Bluetooth устройства для корректных уведомлений:
//  * - Bluetooth наушники (с микрофоном) → запись через микрофон наушников
//  * - Bluetooth колонка (без микрофона) → запись через микрофон телефона
//  */
// class BluetoothAudioManager(private val context: Context) {

//     companion object {
//         private const val TAG = "BluetoothAudioManager"
//     }

//     private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

//     /**
//      * Состояние Bluetooth аудио
//      */
//     data class BluetoothState(
//         val isBluetoothConnected: Boolean,
//         val isBluetoothHeadset: Boolean,    // Наушники с микрофоном
//         val isBluetoothSpeaker: Boolean,    // Колонка без микрофона
//         val deviceName: String?
//     )

//     /**
//      * Получить текущее состояние Bluetooth
//      */
//     fun getBluetoothState(): BluetoothState {
//         // Проверяем SCO (микрофон через Bluetooth)
//         val isScoOn = audioManager.isBluetoothScoOn
        
//         // Проверяем A2DP (музыка через Bluetooth)
//         val isA2dpOn = audioManager.isBluetoothA2dpOn

//         Log.d(TAG, "Bluetooth state: SCO=$isScoOn, A2DP=$isA2dpOn")

//         // Используем AudioDeviceInfo для точного определения (Android 6+)
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//             return getBluetoothStateFromDevices()
//         }

//         // Fallback для старых версий
//         return BluetoothState(
//             isBluetoothConnected = isScoOn || isA2dpOn,
//             isBluetoothHeadset = isScoOn,
//             isBluetoothSpeaker = isA2dpOn && !isScoOn,
//             deviceName = null
//         )
//     }

//     /**
//      * Получить состояние через AudioDeviceInfo (Android 6+)
//      */
//     private fun getBluetoothStateFromDevices(): BluetoothState {
//         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
//             return BluetoothState(false, false, false, null)
//         }

//         var isHeadset = false
//         var isSpeaker = false
//         var deviceName: String? = null

//         // Проверяем устройства вывода (для музыки)
//         val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
//         for (device in outputDevices) {
//             when (device.type) {
//                 AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
//                     // SCO = гарнитура с микрофоном
//                     isHeadset = true
//                     deviceName = device.productName?.toString()
//                     Log.d(TAG, "Found Bluetooth SCO: $deviceName")
//                 }
//                 AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
//                     // A2DP = аудио устройство (может быть колонка или наушники)
//                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                         deviceName = device.productName?.toString()
//                     }
//                     Log.d(TAG, "Found Bluetooth A2DP: $deviceName")
//                 }
//             }
//         }

//         // Проверяем устройства ввода (микрофон)
//         val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
//         for (device in inputDevices) {
//             when (device.type) {
//                 AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
//                     // Есть Bluetooth микрофон → это наушники/гарнитура
//                     isHeadset = true
//                     Log.d(TAG, "Found Bluetooth microphone")
//                 }
//             }
//         }

//         // Если есть A2DP но нет микрофона → колонка
//         val hasA2dp = outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
//         if (hasA2dp && !isHeadset) {
//             isSpeaker = true
//         }

//         val isConnected = isHeadset || isSpeaker

//         Log.d(TAG, "Bluetooth result: connected=$isConnected, headset=$isHeadset, speaker=$isSpeaker")

//         return BluetoothState(
//             isBluetoothConnected = isConnected,
//             isBluetoothHeadset = isHeadset,
//             isBluetoothSpeaker = isSpeaker,
//             deviceName = deviceName
//         )
//     }

//     /**
//      * Проверить используется ли микрофон Bluetooth устройства
//      */
//     fun isUsingBluetoothMicrophone(): Boolean {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//             val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
//             return inputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
//         }
//         return audioManager.isBluetoothScoOn
//     }

//     /**
//      * Проверить подключены ли проводные наушники
//      */
//     fun isWiredHeadsetConnected(): Boolean {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//             val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
//             return devices.any { 
//                 it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
//                 it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
//                 it.type == AudioDeviceInfo.TYPE_USB_HEADSET
//             }
//         }
//         @Suppress("DEPRECATION")
//         return audioManager.isWiredHeadsetOn
//     }

//     /**
//      * Получить текущий аудио роутинг
//      */
//     fun getAudioRouting(): AudioRouting {
//         val btState = getBluetoothState()
        
//         return AudioRouting(
//             isBluetoothHeadset = btState.isBluetoothHeadset,
//             isBluetoothSpeaker = btState.isBluetoothSpeaker,
//             isWiredHeadset = isWiredHeadsetConnected(),
//             isSpeakerphone = audioManager.isSpeakerphoneOn,
//             isBuiltInSpeaker = !btState.isBluetoothConnected && !isWiredHeadsetConnected() && !audioManager.isSpeakerphoneOn
//         )
//     }

//     /**
//      * Информация о текущем аудио роутинге
//      */
//     data class AudioRouting(
//         val isBluetoothHeadset: Boolean,
//         val isBluetoothSpeaker: Boolean,
//         val isWiredHeadset: Boolean,
//         val isSpeakerphone: Boolean,
//         val isBuiltInSpeaker: Boolean
//     )
// }