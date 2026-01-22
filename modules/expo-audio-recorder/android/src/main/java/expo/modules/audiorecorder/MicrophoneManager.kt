// package expo.modules.audiorecorder

// import android.content.Context
// import android.media.AudioDeviceInfo
// import android.media.AudioManager
// import android.media.MediaRecorder
// import android.os.Build
// import android.util.Log

// /**
//  * Менеджер микрофонов
//  *
//  * Получает список всех доступных микрофонов и информацию о них.
//  */
// class MicrophoneManager(private val context: Context) {

//     companion object {
//         private const val TAG = "MicrophoneManager"
//     }

//     private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

//     /**
//      * Информация о микрофоне
//      */
//     data class MicrophoneInfo(
//         val id: Int,
//         val type: Int,
//         val typeName: String,
//         val name: String,
//         val isDefault: Boolean,
//         val address: String?,
//         val channelCounts: List<Int>,
//         val sampleRates: List<Int>
//     )

//     /**
//      * Получить список всех доступных микрофонов
//      */
//     fun getAvailableMicrophones(): List<MicrophoneInfo> {
//         val microphones = mutableListOf<MicrophoneInfo>()

//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//             val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

//             Log.d(TAG, "Found ${devices.size} input devices")

//             for (device in devices) {
//                 // Фильтруем только микрофоны
//                 if (isMicrophoneType(device.type)) {
//                     val info = MicrophoneInfo(
//                         id = device.id,
//                         type = device.type,
//                         typeName = getDeviceTypeName(device.type),
//                         name = device.productName?.toString() ?: getDeviceTypeName(device.type),
//                         isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
//                         address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                             device.address
//                         } else null,
//                         channelCounts = device.channelCounts?.toList() ?: emptyList(),
//                         sampleRates = device.sampleRates?.toList() ?: emptyList()
//                     )

//                     microphones.add(info)

//                     Log.d(TAG, "Microphone: ${info.name} (${info.typeName}), id=${info.id}")
//                 }
//             }
//         } else {
//             // Для старых версий Android возвращаем только встроенный микрофон
//             microphones.add(MicrophoneInfo(
//                 id = 0,
//                 type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
//                 typeName = "BUILTIN_MIC",
//                 name = "Built-in Microphone",
//                 isDefault = true,
//                 address = null,
//                 channelCounts = listOf(1, 2),
//                 sampleRates = listOf(8000, 16000, 44100, 48000)
//             ))
//         }

//         return microphones
//     }

//     /**
//      * Получить информацию о текущем активном микрофоне
//      */
//     fun getActiveMicrophone(): MicrophoneInfo? {
//         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//             val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

//             // Приоритет: Bluetooth SCO > Wired > USB > Built-in
//             val priorityOrder = listOf(
//                 AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
//                 AudioDeviceInfo.TYPE_WIRED_HEADSET,
//                 AudioDeviceInfo.TYPE_USB_HEADSET,
//                 AudioDeviceInfo.TYPE_USB_DEVICE,
//                 AudioDeviceInfo.TYPE_BUILTIN_MIC
//             )

//             for (type in priorityOrder) {
//                 val device = devices.find { it.type == type && isMicrophoneType(it.type) }
//                 if (device != null) {
//                     return MicrophoneInfo(
//                         id = device.id,
//                         type = device.type,
//                         typeName = getDeviceTypeName(device.type),
//                         name = device.productName?.toString() ?: getDeviceTypeName(device.type),
//                         isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
//                         address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                             device.address
//                         } else null,
//                         channelCounts = device.channelCounts?.toList() ?: emptyList(),
//                         sampleRates = device.sampleRates?.toList() ?: emptyList()
//                     )
//                 }
//             }
//         }

//         return null
//     }

//     /**
//      * Получить AudioSource для MediaRecorder/AudioRecord
//      */
//     fun getRecommendedAudioSource(): Int {
//         val activeMic = getActiveMicrophone()

//         return when (activeMic?.type) {
//             AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRecorder.AudioSource.MIC
//             AudioDeviceInfo.TYPE_WIRED_HEADSET -> MediaRecorder.AudioSource.MIC
//             AudioDeviceInfo.TYPE_USB_HEADSET -> MediaRecorder.AudioSource.MIC
//             else -> MediaRecorder.AudioSource.MIC
//         }
//     }

//     /**
//      * Проверить доступен ли какой-либо микрофон
//      */
//     fun hasMicrophone(): Boolean {
//         return getAvailableMicrophones().isNotEmpty()
//     }

//     /**
//      * Проверить является ли тип устройства микрофоном
//      */
//     private fun isMicrophoneType(type: Int): Boolean {
//         return when (type) {
//             AudioDeviceInfo.TYPE_BUILTIN_MIC,
//             AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
//             AudioDeviceInfo.TYPE_WIRED_HEADSET,
//             AudioDeviceInfo.TYPE_USB_HEADSET,
//             AudioDeviceInfo.TYPE_USB_DEVICE,
//             AudioDeviceInfo.TYPE_TELEPHONY -> true
//             else -> false
//         }
//     }

//     /**
//      * Получить название типа устройства
//      */
//     private fun getDeviceTypeName(type: Int): String {
//         return when (type) {
//             AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
//             AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
//             AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
//             AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
//             AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
//             AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
//             AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
//             AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
//             AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
//             AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
//             AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
//             AudioDeviceInfo.TYPE_IP -> "IP"
//             AudioDeviceInfo.TYPE_BUS -> "BUS"
//             AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
//             else -> "UNKNOWN($type)"
//         }
//     }

//     /**
//      * Конвертировать в Map для отправки в JS
//      */
//     fun toJsMap(info: MicrophoneInfo): Map<String, Any?> {
//         return mapOf(
//             "id" to info.id,
//             "type" to info.type,
//             "typeName" to info.typeName,
//             "name" to info.name,
//             "isDefault" to info.isDefault,
//             "address" to info.address,
//             "channelCounts" to info.channelCounts,
//             "sampleRates" to info.sampleRates
//         )
//     }
// }