package audio.recorder.helper

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Информация о микрофоне
 */
data class MicrophoneInfo(
    val id: Int,
    val type: Int,
    val typeName: String,
    val name: String,
    val isDefault: Boolean
)

/**
 * Менеджер микрофонов
 * 
 * Определяет доступные микрофоны и рекомендует оптимальный.
 */
class MicrophoneManager(private val context: Context) {
    companion object {
        private const val TAG = "MicrophoneManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Получить список доступных микрофонов
     */
    fun getAvailableMicrophones(): List<MicrophoneInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return listOf(createDefaultMicrophone())
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val microphones = mutableListOf<MicrophoneInfo>()

        for (device in devices) {
            if (isMicrophoneDevice(device.type)) {
                microphones.add(MicrophoneInfo(
                    id = device.id,
                    type = device.type,
                    typeName = getDeviceTypeName(device.type),
                    name = device.productName?.toString() ?: getDeviceTypeName(device.type),
                    isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                ))
            }
        }

        // Если нет микрофонов, добавляем дефолтный
        if (microphones.isEmpty()) {
            microphones.add(createDefaultMicrophone())
        }

        Log.d(TAG, "Found ${microphones.size} microphones")
        return microphones
    }

    /**
     * Получить рекомендуемый AudioSource
     */
    fun getRecommendedAudioSource(): Int {
        val mics = getAvailableMicrophones()
        
        // Приоритет: Bluetooth > Wired > USB > Builtin
        for (mic in mics) {
            when (mic.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    Log.d(TAG, "Recommended: Bluetooth SCO")
                    return MediaRecorder.AudioSource.MIC
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                    Log.d(TAG, "Recommended: Wired headset")
                    return MediaRecorder.AudioSource.MIC
                }
            }
        }

        return MediaRecorder.AudioSource.MIC
    }

    private fun isMicrophoneDevice(type: Int): Boolean {
        return type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            else -> "Unknown ($type)"
        }
    }

    private fun createDefaultMicrophone(): MicrophoneInfo {
        return MicrophoneInfo(
            id = 0,
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            typeName = "Built-in Microphone",
            name = "Default Microphone",
            isDefault = true
        )
    }
}