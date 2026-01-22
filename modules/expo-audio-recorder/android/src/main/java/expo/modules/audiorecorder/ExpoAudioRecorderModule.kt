package expo.modules.audiorecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Expo Native Module для аудио рекордера
 * 
 * Экспортирует методы записи в JavaScript.
 */
class ExpoAudioRecorderModule : Module() {
    private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recorderService: AudioRecorderService? = null

    private val context
        get() = requireNotNull(appContext.reactContext) { "React context is null" }

    override fun definition() = ModuleDefinition {
        Name("ExpoAudioRecorderCore")

        Events(
            "onRecordingStateChanged",
            "onAudioChunk",
            "onRecordingEvent"
        )

        OnCreate {
            recorderService = AudioRecorderService(
                context = context,
                scope = moduleScope,
                eventEmitter = { name, data -> sendEvent(name, data) }
            )
        }

        OnDestroy {
            recorderService?.release()
            recorderService = null
        }

        // === Разрешения ===
        
        AsyncFunction("requestPermissions") { promise: Promise ->
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                
                promise.resolve(mapOf(
                    "granted" to hasPermission,
                    "status" to if (hasPermission) "granted" else "denied"
                ))
            } catch (e: Exception) {
                promise.reject("PERMISSION_ERROR", e.message, e)
            }
        }

        // === Запись ===

        AsyncFunction("startRecording") { options: Map<String, Any?>, promise: Promise ->
            try {
                val config = RecordingConfig(
                    sampleRate = (options["sampleRate"] as? Number)?.toInt() ?: 44100,
                    bitRate = (options["bitRate"] as? Number)?.toInt() ?: 128000,
                    channels = (options["channels"] as? Number)?.toInt() ?: 1,
                    enableChunking = options["enableChunking"] as? Boolean ?: false,
                    chunkDuration = (options["chunkDuration"] as? Number)?.toInt() ?: 1000
                )
                
                val filePath = recorderService?.startRecording(config)
                    ?: throw IllegalStateException("Recorder not initialized")
                
                promise.resolve(filePath)
            } catch (e: Exception) {
                promise.reject("START_ERROR", e.message, e)
            }
        }

        AsyncFunction("stopRecording") { promise: Promise ->
            moduleScope.launch {
                try {
                    val result = recorderService?.stopRecording()
                        ?: throw IllegalStateException("Recorder not initialized")
                    
                    promise.resolve(mapOf(
                        "filePath" to result.filePath,
                        "duration" to result.duration,
                        "fileSize" to result.fileSize
                    ))
                } catch (e: Exception) {
                    promise.reject("STOP_ERROR", e.message, e)
                }
            }
        }

        AsyncFunction("pauseRecording") { promise: Promise ->
            try {
                recorderService?.pauseRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("PAUSE_ERROR", e.message, e)
            }
        }

        AsyncFunction("resumeRecording") { promise: Promise ->
            try {
                recorderService?.resumeRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("RESUME_ERROR", e.message, e)
            }
        }

        AsyncFunction("cancelRecording") { promise: Promise ->
            try {
                recorderService?.cancelRecording()
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("CANCEL_ERROR", e.message, e)
            }
        }

        AsyncFunction("getStatus") { promise: Promise ->
            try {
                val status = recorderService?.getStatus()
                    ?: RecordingStatus("idle", null, 0.0, false, false, -160.0)
                
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

        // === Recovery ===

        AsyncFunction("hasUnfinishedRecording") { promise: Promise ->
            try {
                val has = recorderService?.hasUnfinishedRecording() ?: false
                promise.resolve(has)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message, e)
            }
        }

        AsyncFunction("recoverUnfinishedRecording") { promise: Promise ->
            moduleScope.launch {
                try {
                    val result = recorderService?.recoverUnfinishedRecording()
                    
                    if (result != null) {
                        promise.resolve(mapOf(
                            "filePath" to result.filePath,
                            "duration" to result.duration,
                            "fileSize" to result.fileSize
                        ))
                    } else {
                        promise.resolve(null)
                    }
                } catch (e: Exception) {
                    promise.reject("RECOVERY_ERROR", e.message, e)
                }
            }
        }
    }
}