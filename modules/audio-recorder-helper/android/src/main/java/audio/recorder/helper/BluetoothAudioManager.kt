package audio.recorder.helper

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Состояние Bluetooth аудио
 */
data class BluetoothState(
    val isConnected: Boolean,
    val isHeadset: Boolean,      // Наушники с микрофоном
    val isSpeaker: Boolean,      // Колонка без микрофона
    val deviceName: String?
)

/**
 * Менеджер Bluetooth аудио
 * 
 * Определяет тип подключённого Bluetooth устройства.
 */
class BluetoothAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothAudioManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var headsetProfile: BluetoothHeadset? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProfile = proxy as BluetoothHeadset
                Log.d(TAG, "Bluetooth headset profile connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProfile = null
                Log.d(TAG, "Bluetooth headset profile disconnected")
            }
        }
    }

    init {
        // Подключаемся к Bluetooth Headset профилю
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
    }

    /**
     * Получить текущее состояние Bluetooth
     */
    fun getBluetoothState(): BluetoothState {
        val isBluetoothScoOn = audioManager.isBluetoothScoOn
        val isBluetoothA2dpOn = audioManager.isBluetoothA2dpOn
        
        val connectedHeadsets = headsetProfile?.connectedDevices ?: emptyList()
        val hasHeadset = connectedHeadsets.isNotEmpty() || isBluetoothScoOn
        
        val deviceName = connectedHeadsets.firstOrNull()?.name

        Log.d(TAG, "Bluetooth state: sco=$isBluetoothScoOn, a2dp=$isBluetoothA2dpOn, headsets=${connectedHeadsets.size}")

        return BluetoothState(
            isConnected = isBluetoothScoOn || isBluetoothA2dpOn || hasHeadset,
            isHeadset = hasHeadset,
            isSpeaker = isBluetoothA2dpOn && !hasHeadset,
            deviceName = deviceName
        )
    }

    /**
     * Проверить есть ли Bluetooth наушники с микрофоном
     */
    fun hasBluetoothHeadset(): Boolean {
        return getBluetoothState().isHeadset
    }

    /**
     * Проверить есть ли Bluetooth колонка
     */
    fun hasBluetoothSpeaker(): Boolean {
        return getBluetoothState().isSpeaker
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        headsetProfile?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        headsetProfile = null
    }
}