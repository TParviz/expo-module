package audio.recorder.helper

import android.content.Context
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise


/**
 * Expo Native Module для обработки аудио прерываний
 * 
 * Отслеживает прерывания и отправляет события в JavaScript.
 * Не управляет записью - только мониторинг.
 */
class AudioRecorderHelperModule : Module() {
    
    private var audioFocusManager: AudioFocusManager? = null
    private var interruptionManager: InterruptionManager? = null
    private var phoneStateManager: PhoneStateManager? = null
    private var bluetoothManager: BluetoothAudioManager? = null
    private var microphoneManager: MicrophoneManager? = null

    private var isMonitoring = false

    private val context
        get() = requireNotNull(appContext.reactContext) { "React context is null" }

    override fun definition() = ModuleDefinition {
        Name("ExpoAudioInterruption")

        Events(
            "onInterruption",
            "onInterruptionEnd",
            "onPhoneCall",
            "onBluetoothChange"
        )

        OnCreate {
            val ctx = appContext.reactContext ?: return@OnCreate

            bluetoothManager = BluetoothAudioManager(ctx)
            microphoneManager = MicrophoneManager(ctx)

            interruptionManager = InterruptionManager(
                context = ctx,
                onInterruption = { info ->
                    sendEvent("onInterruption", mapOf(
                        "source" to info.source.name,
                        "policy" to info.policy.name,
                        "focusChange" to info.focusChange,
                        "message" to info.message
                    ))
                },
                onInterruptionEnd = { source ->
                    sendEvent("onInterruptionEnd", mapOf(
                        "source" to source.name
                    ))
                }
            )

            audioFocusManager = AudioFocusManager(
                context = ctx,
                onFocusLost = { focusChange ->
                    interruptionManager?.handleAudioFocusLoss(focusChange)
                },
                onFocusGained = {
                    interruptionManager?.handleAudioFocusGain()
                }
            )

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
            bluetoothManager?.release()
            bluetoothManager = null
            microphoneManager = null
        }

        // === Мониторинг ===

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

        // === Аудио фокус ===

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

        // === Bluetooth ===

        AsyncFunction("getBluetoothState") { promise: Promise ->
            try {
                val state = bluetoothManager?.getBluetoothState()
                promise.resolve(mapOf(
                    "isConnected" to (state?.isConnected ?: false),
                    "isHeadset" to (state?.isHeadset ?: false),
                    "isSpeaker" to (state?.isSpeaker ?: false),
                    "deviceName" to state?.deviceName
                ))
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        // === Микрофоны ===

        AsyncFunction("getAvailableMicrophones") { promise: Promise ->
            try {
                val mics = microphoneManager?.getAvailableMicrophones() ?: emptyList()
                promise.resolve(mics.map { mic ->
                    mapOf(
                        "id" to mic.id,
                        "type" to mic.type,
                        "typeName" to mic.typeName,
                        "name" to mic.name,
                        "isDefault" to mic.isDefault
                    )
                })
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        // === Утилиты ===

        AsyncFunction("isInPhoneCall") { promise: Promise ->
            promise.resolve(phoneStateManager?.isInCall() ?: false)
        }
    }

    private fun startMonitoringInternal() {
        if (isMonitoring) return
        
        audioFocusManager?.requestAudioFocus()
        interruptionManager?.startMonitoring()
        phoneStateManager?.startListening()
        
        isMonitoring = true
    }

    private fun stopMonitoringInternal() {
        if (!isMonitoring) return
        
        phoneStateManager?.stopListening()
        interruptionManager?.stopMonitoring()
        audioFocusManager?.abandonAudioFocus()
        
        isMonitoring = false
    }
}