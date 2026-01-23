package audio.recorder.helper

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
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
    val isDefault: Boolean,
    val address: String?,
    val channelCounts: List<Int>,
    val sampleRates: List<Int>
) {
    companion object {
        /**
         * Создать MicrophoneInfo из AudioDeviceInfo
         */
        fun fromAudioDeviceInfo(device: AudioDeviceInfo): MicrophoneInfo {
            return MicrophoneInfo(
                id = device.id,
                type = device.type,
                typeName = getDeviceTypeName(device.type),
                name = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: getDeviceTypeName(device.type),
                isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    device.address
                } else null,
                channelCounts = device.channelCounts?.toList() ?: emptyList(),
                sampleRates = device.sampleRates?.toList() ?: emptyList()
            )
        }

        private fun getDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Analog"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Digital"
                AudioDeviceInfo.TYPE_AUX_LINE -> "Aux Line"
                AudioDeviceInfo.TYPE_IP -> "IP"
                AudioDeviceInfo.TYPE_BUS -> "Bus"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote Submix"
                else -> "Unknown ($type)"
            }
        }
    }

    /**
     * Конвертировать в Map для отправки в JS
     */
    fun toJsMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "type" to type,
            "typeName" to typeName,
            "name" to name,
            "isDefault" to isDefault,
            "address" to address,
            "channelCounts" to channelCounts,
            "sampleRates" to sampleRates
        )
    }
}

/**
 * Результат выбора микрофона
 */
sealed class MicrophoneSelectionResult {
    data class Success(val microphone: MicrophoneInfo) : MicrophoneSelectionResult()
    data class NotFound(val requestedId: Int) : MicrophoneSelectionResult()
    data class Error(val message: String) : MicrophoneSelectionResult()
}

/**
 * Менеджер микрофонов с поддержкой выбора конкретного устройства
 */
class MicrophoneManager(private val context: Context) {

    companion object {
        private const val TAG = "MicrophoneManager"

        // Приоритет микрофонов по умолчанию
        private val DEFAULT_PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_MIC
        )

        // Типы устройств, которые являются микрофонами
        private val MICROPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_TELEPHONY
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Выбранный пользователем микрофон (null = автовыбор)
    @Volatile
    private var selectedMicrophoneId: Int? = null

    // Кэш устройств
    @Volatile
    private var cachedDevices: List<MicrophoneInfo>? = null
    @Volatile
    private var cacheTimestamp: Long = 0
    private val cacheDurationMs = 1000L

    /**
     * Получить список всех доступных микрофонов
     */
    fun getAvailableMicrophones(forceRefresh: Boolean = false): List<MicrophoneInfo> {
        val now = System.currentTimeMillis()

        // Возвращаем кэш если актуален
        if (!forceRefresh && cachedDevices != null && (now - cacheTimestamp) < cacheDurationMs) {
            return cachedDevices!!
        }

        val microphones = mutableListOf<MicrophoneInfo>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                Log.d(TAG, "Found ${devices.size} input devices")

                for (device in devices) {
                    if (device.type in MICROPHONE_TYPES) {
                        val info = MicrophoneInfo.fromAudioDeviceInfo(device)
                        microphones.add(info)
                        Log.d(TAG, "Microphone: ${info.name}, id=${info.id}, type=${info.typeName}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting microphones", e)
            }
        }

        // Fallback для старых версий или если ничего не найдено
        if (microphones.isEmpty()) {
            microphones.add(createDefaultMicrophone())
        }

        cachedDevices = microphones
        cacheTimestamp = now
        return microphones
    }

    /**
     * Получить микрофон по ID
     */
    fun getMicrophoneById(id: Int): MicrophoneInfo? {
        return getAvailableMicrophones().find { it.id == id }
    }

    /**
     * Получить микрофон по типу
     */
    fun getMicrophoneByType(type: Int): MicrophoneInfo? {
        return getAvailableMicrophones().find { it.type == type }
    }

    /**
     * Выбрать микрофон по ID
     *
     * @param id ID микрофона (null для автовыбора)
     * @return результат выбора
     */
    fun selectMicrophone(id: Int?): MicrophoneSelectionResult {
        if (id == null) {
            selectedMicrophoneId = null
            Log.i(TAG, "Microphone selection reset to auto")
            val auto = getActiveMicrophone()
            return if (auto != null) {
                MicrophoneSelectionResult.Success(auto)
            } else {
                MicrophoneSelectionResult.Error("No microphones available")
            }
        }

        val microphone = getMicrophoneById(id)
        return if (microphone != null) {
            selectedMicrophoneId = id
            Log.i(TAG, "Selected microphone: ${microphone.name} (id=$id)")
            MicrophoneSelectionResult.Success(microphone)
        } else {
            Log.w(TAG, "Microphone with id=$id not found")
            MicrophoneSelectionResult.NotFound(id)
        }
    }

    /**
     * Выбрать микрофон по типу
     *
     * @param type тип микрофона (AudioDeviceInfo.TYPE_*)
     * @return результат выбора
     */
    fun selectMicrophoneByType(type: Int): MicrophoneSelectionResult {
        val microphone = getMicrophoneByType(type)
        return if (microphone != null) {
            selectedMicrophoneId = microphone.id
            Log.i(TAG, "Selected microphone by type: ${microphone.name} (type=$type)")
            MicrophoneSelectionResult.Success(microphone)
        } else {
            Log.w(TAG, "Microphone with type=$type not found")
            MicrophoneSelectionResult.Error("Microphone type not available")
        }
    }

    /**
     * Получить текущий выбранный микрофон
     */
    fun getSelectedMicrophone(): MicrophoneInfo? {
        val id = selectedMicrophoneId
        return if (id != null) {
            getMicrophoneById(id)
        } else {
            null
        }
    }

    /**
     * Получить активный микрофон (выбранный пользователем или по приоритету)
     */
    fun getActiveMicrophone(): MicrophoneInfo? {
        // Если пользователь выбрал микрофон — используем его
        val selected = getSelectedMicrophone()
        if (selected != null) {
            // Проверяем что он всё ещё доступен
            val stillAvailable = getMicrophoneById(selected.id)
            if (stillAvailable != null) {
                return stillAvailable
            }
            // Если недоступен — сбрасываем выбор
            Log.w(TAG, "Selected microphone no longer available, resetting to auto")
            selectedMicrophoneId = null
        }

        // Автовыбор по приоритету
        val available = getAvailableMicrophones()
        for (type in DEFAULT_PRIORITY) {
            val mic = available.find { it.type == type }
            if (mic != null) {
                return mic
            }
        }

        return available.firstOrNull()
    }

    /**
     * Проверить, выбран ли микрофон вручную
     */
    fun isManualSelection(): Boolean = selectedMicrophoneId != null

    /**
     * Сбросить выбор микрофона на автоматический
     */
    fun resetToAutoSelection() {
        selectedMicrophoneId = null
        Log.i(TAG, "Reset to auto microphone selection")
    }

    /**
     * Применить выбранный микрофон к AudioRecord
     *
     * @param audioRecord экземпляр AudioRecord
     * @return true если успешно применено
     */
    fun applyToAudioRecord(audioRecord: AudioRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        val microphone = getActiveMicrophone() ?: return false

        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val device = devices.find { it.id == microphone.id }

            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val result = audioRecord.setPreferredDevice(device)
                Log.d(TAG, "Applied microphone ${microphone.name} to AudioRecord: $result")
                result
            } else {
                Log.w(TAG, "Device not found or API < 28")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying microphone to AudioRecord", e)
            false
        }
    }

    /**
     * Получить AudioDeviceInfo для выбранного микрофона
     * (для использования с MediaRecorder на API 28+)
     */
    fun getPreferredAudioDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }

        val microphone = getActiveMicrophone() ?: return null

        return try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            devices.find { it.id == microphone.id }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting preferred audio device", e)
            null
        }
    }

    /**
     * Получить рекомендуемый AudioSource для MediaRecorder
     */
    fun getRecommendedAudioSource(): Int {
        val activeMic = getActiveMicrophone()

        return when (activeMic?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRecorder.AudioSource.MIC
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET -> MediaRecorder.AudioSource.MIC
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            // Для голосовых вызовов
            AudioDeviceInfo.TYPE_TELEPHONY -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.MIC
        }
    }

    /**
     * Проверить доступен ли какой-либо микрофон
     */
    fun hasMicrophone(): Boolean {
        return getAvailableMicrophones().isNotEmpty()
    }

    /**
     * Проверить доступен ли Bluetooth микрофон
     */
    fun hasBluetoothMicrophone(): Boolean {
        return getAvailableMicrophones().any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /**
     * Проверить доступен ли проводной микрофон (гарнитура)
     */
    fun hasWiredMicrophone(): Boolean {
        return getAvailableMicrophones().any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /**
     * Очистить кэш устройств
     */
    fun invalidateCache() {
        cachedDevices = null
        cacheTimestamp = 0
    }

    private fun createDefaultMicrophone(): MicrophoneInfo {
        return MicrophoneInfo(
            id = 0,
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            typeName = "Built-in Microphone",
            name = "Built-in Microphone",
            isDefault = true,
            address = null,
            channelCounts = listOf(1, 2),
            sampleRates = listOf(8000, 16000, 44100, 48000)
        )
    }
}