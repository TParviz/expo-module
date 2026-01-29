package audio.recorder.helper

import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise

/**
 * Expo Native Module для обработки аудио прерываний
 * 
 * Отслеживает прерывания и отправляет события в JavaScript.
 * Не управляет записью - только мониторинг.
 * 
 * Поддерживает:
 * - Мониторинг аудио прерываний
 * - Управление аудио фокусом
 * - Работа с микрофонами
 * - Bluetooth аудио
 * - Уведомления для каждого типа прерывания
 */
class AudioRecorderHelperModule : Module() {
    
    companion object {
        private const val TAG = "AudioRecorderHelper"
    }
    
    private var audioFocusManager: AudioFocusManager? = null
    private var interruptionManager: InterruptionManager? = null
    private var phoneStateManager: PhoneStateManager? = null
    private var bluetoothManager: BluetoothAudioManager? = null
    private var microphoneManager: MicrophoneManager? = null
    private var recordingTimer: RecordingTimer? = null

    private var isMonitoring = false

    private val context
        get() = requireNotNull(appContext.reactContext) { "React context is null" }

    override fun definition() = ModuleDefinition {
        Name("AudioRecorderHelper")

        Events(
            "onInterruption",
            "onInterruptionEnd",
            "onPhoneCall",
            "onBluetoothChange",
            "onAudioFocusChanged",
            "onAudioStateChanged",
            "onMicrophoneChanged",
            "onNotificationUpdate", // NEW: событие обновления уведомления
            "onPauseRequested",   // NEW: Запрос на паузу записи
            "onResumeRequested"   // NEW: Запрос на возобновление записи
        )

        OnCreate {
            val ctx = appContext.reactContext ?: return@OnCreate

            bluetoothManager = BluetoothAudioManager(ctx)
            microphoneManager = MicrophoneManager(ctx)

            interruptionManager = InterruptionManager(
                context = ctx,
                bluetoothManager = bluetoothManager!!,
                onInterruption = { info ->
                    // Отправляем событие прерывания
                    sendEvent("onInterruption", mapOf(
                        "source" to info.source.name,
                        "policy" to info.policy.name,
                        "focusChange" to info.focusChange,
                        "message" to info.message
                    ))
                },
                onInterruptionEnd = { source ->
                    // Отправляем событие окончания прерывания
                    sendEvent("onInterruptionEnd", mapOf(
                        "source" to source.name
                    ))
                },
                onPauseRequested = { source ->
                    Log.d(TAG, "Pause requested by interruption: $source")
                    sendEvent("onPauseRequested", mapOf(
                        "source" to source.name,
                        "reason" to "interruption"
                    ))
                },
                // NEW: Callback для возобновления записи
                onResumeRequested = { source ->
                    Log.d(TAG, "Resume requested after interruption: $source")
                    sendEvent("onResumeRequested", mapOf(
                        "source" to source.name,
                        "reason" to "interruption_ended"
                    ))
                }
            )
            
            // Устанавливаем callback для уведомлений
            interruptionManager?.setNotificationCallback { isPaused, source, isBluetoothHeadset ->
                Log.d(TAG, "Notification update: isPaused=$isPaused, source=$source, bt=$isBluetoothHeadset")
                
                // Отправляем событие в JS для возможной обработки
                sendEvent("onNotificationUpdate", mapOf(
                    "isPaused" to isPaused,
                    "source" to source.name,
                    "isBluetoothHeadset" to isBluetoothHeadset
                ))
            }

            audioFocusManager = AudioFocusManager(
                context = ctx,
                onFocusLost = { focusChange ->
                    interruptionManager?.handleAudioFocusLoss(focusChange)
                },
                onFocusGained = {
                    interruptionManager?.handleAudioFocusGain()
                }
            )
            
            // Устанавливаем callback для событий фокуса
            audioFocusManager?.setFocusEventCallback { eventType, data ->
                sendEvent("onAudioFocusChanged", data)
            }

            phoneStateManager = PhoneStateManager(
                context = ctx,
                onCallStarted = {
                    sendEvent("onPhoneCall", mapOf(
                        "state" to "started"
                    ))
                },
                onCallEnded = {
                    sendEvent("onPhoneCall", mapOf(
                        "state" to "ended"
                    ))
                }
            )
            
        }

        OnDestroy {
            stopMonitoringInternal()
            interruptionManager?.setNotificationCallback(null)
            bluetoothManager?.release()
            bluetoothManager = null
            microphoneManager = null
        }


        // ============================================================
        // МОНИТОРИНГ
        // ============================================================
        

        AsyncFunction("startMonitoring") { promise: Promise ->
            try {
                startMonitoringInternal()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("stopMonitoring") { promise: Promise ->
            try {
                stopMonitoringInternal()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("isMonitoring") { promise: Promise ->
            promise.resolve(isMonitoring)
        }

        // ============================================================
        // АУДИО ФОКУС
        // ============================================================

        AsyncFunction("requestAudioFocus") { promise: Promise ->
            try {
                val granted = audioFocusManager?.requestAudioFocus() ?: false
                promise.resolve(granted)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("abandonAudioFocus") { promise: Promise ->
            try {
                audioFocusManager?.abandonAudioFocus()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("hasAudioFocus") { promise: Promise ->
            promise.resolve(audioFocusManager?.hasFocus() ?: false)
        }

        AsyncFunction("getAudioState") { promise: Promise ->
            try {
                val state = audioFocusManager?.getAudioState() ?: emptyMap()
                promise.resolve(state)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        // ============================================================
        // BLUETOOTH
        // ============================================================

        AsyncFunction("getBluetoothState") { promise: Promise ->
            try {
                val state = bluetoothManager?.getBluetoothState()
                if (state != null) {
                    promise.resolve(mapOf(
                        "isConnected" to state.isConnected,
                        "isHeadset" to state.isBluetoothHeadset,
                        "isSpeaker" to state.isBluetoothSpeaker,
                        "deviceName" to state.deviceName
                    ))
                } else {
                    promise.resolve(mapOf(
                        "isConnected" to false,
                        "isHeadset" to false,
                        "isSpeaker" to false,
                        "deviceName" to null
                    ))
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        /**
         * Проверить наличие разрешений Bluetooth
         */
        AsyncFunction("hasBluetoothPermission") { promise: Promise ->
            try {
                val hasPermission = bluetoothManager?.hasBluetoothPermission() ?: false
                promise.resolve(hasPermission)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        /**
         * Получить статус разрешений Bluetooth
         */
        AsyncFunction("getBluetoothPermissionStatus") { promise: Promise ->
            try {
                val status = bluetoothManager?.getBluetoothPermissionStatus()
                if (status != null) {
                    promise.resolve(mapOf(
                        "hasPermission" to status.hasPermission,
                        "missingPermissions" to status.missingPermissions
                    ))
                } else {
                    promise.resolve(mapOf(
                        "hasPermission" to false,
                        "missingPermissions" to BluetoothAudioManager.getRequiredPermissions()
                    ))
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        /**
         * Получить список необходимых разрешений для Bluetooth
         */
        AsyncFunction("getBluetoothRequiredPermissions") { promise: Promise ->
            try {
                val permissions = BluetoothAudioManager.getRequiredPermissions()
                promise.resolve(permissions)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        /**
         * Инициализировать Bluetooth после получения разрешений
         */
        AsyncFunction("initializeBluetoothAfterPermission") { promise: Promise ->
            try {
                bluetoothManager?.initializeAfterPermissionGranted()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        // ============================================================
        // МИКРОФОНЫ
        // ============================================================

        AsyncFunction("getAvailableMicrophones") { promise: Promise ->
            try {
                val mics = microphoneManager?.getAvailableMicrophones() ?: emptyList()
                val selectedId = microphoneManager?.getSelectedMicrophone()?.id
                
                promise.resolve(mics.map { mic ->
                    microphoneToMap(mic, mic.id == selectedId)
                })
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("getActiveMicrophone") { promise: Promise ->
            try {
                val mic = microphoneManager?.getActiveMicrophone()
                if (mic != null) {
                    val isSelected = microphoneManager?.isManualSelection() == true
                    promise.resolve(microphoneToMap(mic, isSelected))
                } else {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("getSelectedMicrophone") { promise: Promise ->
            try {
                val mic = microphoneManager?.getSelectedMicrophone()
                if (mic != null) {
                    promise.resolve(microphoneToMap(mic, true))
                } else {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("selectMicrophone") { id: Int?, promise: Promise ->
            try {
                val result = microphoneManager?.selectMicrophone(id)
                
                when (result) {
                    is MicrophoneSelectionResult.Success -> {
                        sendEvent("onMicrophoneChanged", microphoneToMap(result.microphone, id != null))
                        
                        promise.resolve(mapOf(
                            "success" to true,
                            "microphone" to microphoneToMap(result.microphone, id != null),
                            "error" to null
                        ))
                    }
                    is MicrophoneSelectionResult.NotFound -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to "Microphone with id=${result.requestedId} not found"
                        ))
                    }
                    is MicrophoneSelectionResult.Error -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to result.message
                        ))
                    }
                    null -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to "MicrophoneManager not initialized"
                        ))
                    }
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("selectMicrophoneByType") { type: Int, promise: Promise ->
            try {
                val result = microphoneManager?.selectMicrophoneByType(type)
                
                when (result) {
                    is MicrophoneSelectionResult.Success -> {
                        sendEvent("onMicrophoneChanged", microphoneToMap(result.microphone, true))
                        
                        promise.resolve(mapOf(
                            "success" to true,
                            "microphone" to microphoneToMap(result.microphone, true),
                            "error" to null
                        ))
                    }
                    is MicrophoneSelectionResult.NotFound -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to "Microphone type not available"
                        ))
                    }
                    is MicrophoneSelectionResult.Error -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to result.message
                        ))
                    }
                    null -> {
                        promise.resolve(mapOf(
                            "success" to false,
                            "microphone" to null,
                            "error" to "MicrophoneManager not initialized"
                        ))
                    }
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("resetMicrophoneSelection") { promise: Promise ->
            try {
                microphoneManager?.resetToAutoSelection()
                val mic = microphoneManager?.getActiveMicrophone()
                
                if (mic != null) {
                    sendEvent("onMicrophoneChanged", microphoneToMap(mic, false))
                    
                    promise.resolve(mapOf(
                        "success" to true,
                        "microphone" to microphoneToMap(mic, false),
                        "error" to null
                    ))
                } else {
                    promise.resolve(mapOf(
                        "success" to false,
                        "microphone" to null,
                        "error" to "No microphones available"
                    ))
                }
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("isManualMicrophoneSelection") { promise: Promise ->
            promise.resolve(microphoneManager?.isManualSelection() ?: false)
        }

        // ============================================================
        // УТИЛИТЫ
        // ============================================================

        AsyncFunction("isInPhoneCall") { promise: Promise ->
            promise.resolve(phoneStateManager?.isInCall() ?: false)
        }
        
        AsyncFunction("hasActiveMediaPlayback") { promise: Promise ->
            promise.resolve(interruptionManager?.hasActiveMediaPlayback() ?: false)
        }
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private fun microphoneToMap(mic: MicrophoneInfo, isSelected: Boolean): Map<String, Any?> {
        return mapOf(
            "id" to mic.id,
            "type" to mic.type,
            "typeName" to mic.typeName,
            "name" to mic.name,
            "isDefault" to mic.isDefault,
            "isSelected" to isSelected,
            "address" to mic.address,
            "channelCounts" to mic.channelCounts,
            "sampleRates" to mic.sampleRates
        )
    }

    private fun startMonitoringInternal() {
        if (isMonitoring) return
        
        audioFocusManager?.requestAudioFocus()
        interruptionManager?.startMonitoring()
        phoneStateManager?.startListening()
        
        isMonitoring = true
        Log.d(TAG, "Monitoring started")
    }

    private fun stopMonitoringInternal() {
        if (!isMonitoring) return
        
        phoneStateManager?.stopListening()
        interruptionManager?.stopMonitoring()
        audioFocusManager?.abandonAudioFocus()
        
        isMonitoring = false
        Log.d(TAG, "Monitoring stopped")
    }

}