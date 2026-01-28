package audio.recorder.helper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Состояние Bluetooth аудио
 */
data class BluetoothState(
    val isConnected: Boolean,
    val isBluetoothHeadset: Boolean,  // Наушники с микрофоном
    val isBluetoothSpeaker: Boolean,  // Колонка без микрофона
    val deviceName: String?
)

/**
 * Результат проверки разрешений Bluetooth
 */
data class BluetoothPermissionStatus(
    val hasPermission: Boolean,
    val missingPermissions: List<String>
)

/**
 * Менеджер Bluetooth аудио
 */
class BluetoothAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothAudioManager"
        
        /**
         * Получить список необходимых разрешений для Bluetooth
         */
        fun getRequiredPermissions(): List<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ требует BLUETOOTH_CONNECT
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            } else {
                // Android 11 и ниже
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            }
        }
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
        // Пробуем подключиться к профилю только если есть разрешения
        if (hasBluetoothPermission()) {
            try {
                bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to access Bluetooth profile: ${e.message}")
            }
        }
    }

    /**
     * Проверить наличие разрешений Bluetooth
     */
    fun hasBluetoothPermission(): Boolean {
        return getBluetoothPermissionStatus().hasPermission
    }

    /**
     * Получить статус разрешений Bluetooth
     */
    fun getBluetoothPermissionStatus(): BluetoothPermissionStatus {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "requestPermissions: $requiredPermissions === missing: $missingPermissions")
        return BluetoothPermissionStatus(
            hasPermission = missingPermissions.isEmpty(),
            missingPermissions = missingPermissions
        )
    }

    /**
     * Инициализировать Bluetooth профиль после получения разрешений
     */
    fun initializeAfterPermissionGranted() {
        if (hasBluetoothPermission() && headsetProfile == null) {
            try {
                bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
                Log.d(TAG, "Bluetooth profile initialized after permission granted")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to initialize Bluetooth profile: ${e.message}")
            }
        }
    }

    /**
     * Получить текущее состояние Bluetooth
     */
    fun getBluetoothState(): BluetoothState {
        // Если нет разрешений, возвращаем "не подключено"
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "No Bluetooth permission, returning disconnected state")
            return BluetoothState(
                isConnected = false,
                isBluetoothHeadset = false,
                isBluetoothSpeaker = false,
                deviceName = null
            )
        }

        return try {
            val isBluetoothScoOn = audioManager.isBluetoothScoOn
            val isBluetoothA2dpOn = audioManager.isBluetoothA2dpOn

            val connectedHeadsets = headsetProfile?.connectedDevices ?: emptyList()
            val hasHeadset = connectedHeadsets.isNotEmpty() || isBluetoothScoOn

            val deviceName = connectedHeadsets.firstOrNull()?.name

            Log.d(TAG, "Bluetooth state: sco=$isBluetoothScoOn, a2dp=$isBluetoothA2dpOn, headsets=${connectedHeadsets.size}")

            BluetoothState(
                isConnected = isBluetoothScoOn || isBluetoothA2dpOn || hasHeadset,
                isBluetoothHeadset = hasHeadset,
                isBluetoothSpeaker = isBluetoothA2dpOn && !hasHeadset,
                deviceName = deviceName
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting Bluetooth state: ${e.message}")
            BluetoothState(
                isConnected = false,
                isBluetoothHeadset = false,
                isBluetoothSpeaker = false,
                deviceName = null
            )
        }
    }

    /**
     * Проверить есть ли Bluetooth наушники с микрофоном
     */
    fun hasBluetoothHeadset(): Boolean {
        return getBluetoothState().isBluetoothHeadset
    }

    /**
     * Проверить есть ли Bluetooth колонка
     */
    fun hasBluetoothSpeaker(): Boolean {
        return getBluetoothState().isBluetoothSpeaker
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        try {
            headsetProfile?.let {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception releasing Bluetooth profile: ${e.message}")
        }
        headsetProfile = null
    }
}