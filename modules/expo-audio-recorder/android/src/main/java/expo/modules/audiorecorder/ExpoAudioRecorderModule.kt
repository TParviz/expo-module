package expo.modules.audiorecorder

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.*

/**
 * ExpoAudioRecorderModule - Expo Native Module для записи аудио
 * 
 * Функции:
 * - Запись аудио с сохранением сырых PCM данных
 * - Автоматическое восстановление после force-kill
 * - Стриминг аудио чанков
 * - Детекция тишины
 * - Выбор микрофона
 */
class ExpoAudioRecorderModule : Module() {

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioRecorderService: AudioRecorderService? = null

    private val context
        get() = requireNotNull(appContext.reactContext) { "React context is null" }

    private fun getService(): AudioRecorderService {
        if (audioRecorderService == null) {
            audioRecorderService = AudioRecorderService(
                context = context,
                scope = moduleScope,
                eventEmitter = { eventName, params ->
                    sendEvent(eventName, params)
                }
            )
        }
        return audioRecorderService!!
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoAudioRecorder")

        // События
        Events(
            "onRecordingStateChanged",
            "onAudioChunk",
            "onRecordingError",
            "onRecordingEvent"
        )

        // ==================== Permissions ====================

        AsyncFunction("requestPermissions") { promise: Promise ->
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val canRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activity = appContext.currentActivity
                    if (activity != null && !hasPermission) {
                        !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    } else {
                        true
                    }
                } else {
                    true
                }

                promise.resolve(mapOf(
                    "granted" to hasPermission,
                    "canRequest" to canRequest
                ))
            } catch (e: Exception) {
                promise.reject("PERMISSION_ERROR", e.message, e)
            }
        }

        // ==================== Core Recording ====================

        AsyncFunction("startRecording") { config: Map<String, Any?>, promise: Promise ->
            try {
                val recordingConfig = RecordingConfig(
                    sampleRate = (config["sampleRate"] as? Number)?.toInt() ?: 44100,
                    bitRate = (config["bitRate"] as? Number)?.toInt() ?: 128000,
                    channels = (config["channels"] as? Number)?.toInt() ?: 1,
                    enableChunking = (config["enableChunking"] as? Boolean) ?: false,
                    chunkDuration = (config["chunkDuration"] as? Number)?.toInt() ?: 1000,
                    microphoneId = (config["microphoneId"] as? Number)?.toInt()
                )

                val filePath = getService().startRecording(recordingConfig)
                promise.resolve(filePath)
            } catch (e: Exception) {
                promise.reject("START_RECORDING_ERROR", e.message, e)
            }
        }

        AsyncFunction("stopRecording") { promise: Promise ->
            moduleScope.launch {
                try {
                    val result = getService().stopRecording()
                    promise.resolve(mapOf(
                        "filePath" to result.filePath,
                        "duration" to result.duration,
                        "fileSize" to result.fileSize
                    ))
                } catch (e: Exception) {
                    promise.reject("STOP_RECORDING_ERROR", e.message, e)
                }
            }
        }

        AsyncFunction("cancelRecording") { promise: Promise ->
            try {
                getService().cancelRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("CANCEL_RECORDING_ERROR", e.message, e)
            }
        }

        AsyncFunction("pauseRecording") { promise: Promise ->
            try {
                getService().pauseRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("PAUSE_RECORDING_ERROR", e.message, e)
            }
        }

        AsyncFunction("resumeRecording") { promise: Promise ->
            try {
                getService().resumeRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("RESUME_RECORDING_ERROR", e.message, e)
            }
        }

        AsyncFunction("getStatus") { promise: Promise ->
            try {
                val status = getService().getStatus()
                promise.resolve(mapOf(
                    "state" to status.state,
                    "filePath" to status.filePath,
                    "duration" to status.duration,
                    "isRecording" to status.isRecording,
                    "isPaused" to status.isPaused,
                    "noiseLevel" to status.noiseLevel
                ))
            } catch (e: Exception) {
                promise.reject("STATUS_ERROR", e.message, e)
            }
        }

        // ==================== Recovery ====================

        AsyncFunction("hasUnfinishedRecording") { promise: Promise ->
            try {
                val hasUnfinished = getService().hasUnfinishedRecording()
                promise.resolve(hasUnfinished)
            } catch (e: Exception) {
                promise.reject("CHECK_UNFINISHED_ERROR", e.message, e)
            }
        }

        AsyncFunction("recoverUnfinishedRecording") { promise: Promise ->
            moduleScope.launch {
                try {
                    val result = getService().recoverUnfinishedRecording()
                    if (result != null) {
                        promise.resolve(mapOf(
                            "filePath" to result.filePath,
                            "originalPath" to result.originalPath,
                            "duration" to result.duration,
                            "fileSize" to result.fileSize,
                            "timestamp" to result.timestamp,
                            "recovered" to result.recovered
                        ))
                    } else {
                        promise.resolve(null)
                    }
                } catch (e: Exception) {
                    promise.reject("RECOVERY_ERROR", e.message, e)
                }
            }
        }

        // ==================== Microphones ====================

        AsyncFunction("getAvailableMicrophones") { promise: Promise ->
            try {
                val microphones = getService().getAvailableMicrophones()
                promise.resolve(microphones.map { mic ->
                    mapOf(
                        "id" to mic.id,
                        "type" to mic.type,
                        "typeName" to mic.typeName,
                        "name" to mic.name,
                        "isDefault" to mic.isDefault,
                        "address" to mic.address,
                        "channelCounts" to mic.channelCounts,
                        "sampleRates" to mic.sampleRates
                    )
                })
            } catch (e: Exception) {
                promise.reject("GET_MICROPHONES_ERROR", e.message, e)
            }
        }

        AsyncFunction("getActiveMicrophone") { promise: Promise ->
            try {
                val microphone = getService().getActiveMicrophone()
                if (microphone != null) {
                    promise.resolve(mapOf(
                        "id" to microphone.id,
                        "type" to microphone.type,
                        "typeName" to microphone.typeName,
                        "name" to microphone.name,
                        "isDefault" to microphone.isDefault,
                        "address" to microphone.address,
                        "channelCounts" to microphone.channelCounts,
                        "sampleRates" to microphone.sampleRates
                    ))
                } else {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                promise.reject("GET_ACTIVE_MICROPHONE_ERROR", e.message, e)
            }
        }

        // ==================== Utilities ====================

        AsyncFunction("isInGracePeriod") { promise: Promise ->
            try {
                promise.resolve(getService().isInGracePeriod())
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        // ==================== Cleanup ====================

        OnDestroy {
            audioRecorderService?.release()
            audioRecorderService = null
            moduleScope.cancel()
        }
    }
}