package expo.modules.audiorecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ExpoAudioRecorderModule : Module() {
  private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var audioRecorderService: AudioRecorderService? = null

  private val context: Context
    get() = requireNotNull(appContext.reactContext)

  override fun definition() = ModuleDefinition {
    Name("ExpoAudioRecorder")

    Events(
      "onRecordingStateChanged",
      "onAudioChunk",
      "onRecordingError"
    )

    AsyncFunction("requestPermissions") { promise: Promise ->
      try {
        val hasPermission = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Check if we can request permission
        val canRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          // On Android 6+, check if permission was permanently denied
          val activity = appContext.currentActivity
          if (activity != null && !hasPermission) {
            // If shouldShowRequestPermissionRationale returns false and permission is not granted,
            // it means user selected "Don't ask again"
            activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || 
              ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == 
              PackageManager.PERMISSION_GRANTED
          } else {
            !hasPermission
          }
        } else {
          // On older Android versions, permissions are granted at install time
          true
        }

        promise.resolve(
          mapOf(
            "granted" to hasPermission,
            "canRequest" to canRequest
          )
        )
      } catch (e: Exception) {
        promise.reject("PERMISSION_CHECK_FAILED", e.message, e)
      }
    }

    AsyncFunction("startRecording") { config: Map<String, Any>, promise: Promise ->
      try {
        if (audioRecorderService == null) {
          audioRecorderService = AudioRecorderService(
            context = context,
            scope = moduleScope,
            eventEmitter = { eventName, params ->
              sendEvent(eventName, params)
            }
          )
        }

        val recordingConfig = RecordingConfig(
          sampleRate = (config["sampleRate"] as? Int) ?: 44100,
          bitRate = (config["bitRate"] as? Int) ?: 128000,
          channels = (config["channels"] as? Int) ?: 1,
          enableChunking = (config["enableChunking"] as? Boolean) ?: false,
          chunkDuration = (config["chunkDuration"] as? Int) ?: 1000
        )

        val filePath = audioRecorderService?.startRecording(recordingConfig)
        promise.resolve(filePath)
      } catch (e: Exception) {
        promise.reject("RECORDING_START_FAILED", e.message, e)
      }
    }

    AsyncFunction("stopRecording") { promise: Promise ->
      try {
        val result = audioRecorderService?.stopRecording()
        if (result != null) {
          promise.resolve(
            mapOf(
              "filePath" to result.filePath,
              "duration" to result.duration,
              "fileSize" to result.fileSize
            )
          )
        } else {
          promise.reject("NO_ACTIVE_RECORDING", "No active recording to stop", null)
        }
      } catch (e: Exception) {
        promise.reject("RECORDING_STOP_FAILED", e.message, e)
      }
    }

    AsyncFunction("pauseRecording") { promise: Promise ->
      try {
        audioRecorderService?.pauseRecording()
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("RECORDING_PAUSE_FAILED", e.message, e)
      }
    }

    AsyncFunction("resumeRecording") { promise: Promise ->
      try {
        audioRecorderService?.resumeRecording()
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("RECORDING_RESUME_FAILED", e.message, e)
      }
    }

    AsyncFunction("getStatusAsync") { promise: Promise ->
      moduleScope.launch {
        try {
          val status = audioRecorderService?.getStatus()
          promise.resolve(
            mapOf(
              "state" to (status?.state ?: "idle"),
              "filePath" to status?.filePath,
              "duration" to (status?.duration ?: 0.0),
              "isRecording" to (status?.isRecording ?: false),
              "isPaused" to (status?.isPaused ?: false),
              "noiseLevel" to (status?.noiseLevel ?: -160.0)
            )
          )
        } catch (e: Exception) {
          promise.reject("STATUS_FAILED", e.message, e)
        }
      }
    }
    
    // Recovery methods
    AsyncFunction("checkRecoveryAsync") { promise: Promise ->
      moduleScope.launch {
        try {
          val result = audioRecorderService?.checkAndRecoverUnfinishedRecording()
          if (result != null) {
            promise.resolve(
              mapOf(
                "filePath" to result.filePath,
                "originalPath" to result.originalPath,
                "duration" to result.duration,
                "fileSize" to result.fileSize,
                "timestamp" to result.timestamp
              )
            )
          } else {
            promise.resolve(null)
          }
        } catch (e: Exception) {
          promise.reject("RECOVERY_CHECK_FAILED", e.message, e)
        }
      }
    }
    
    AsyncFunction("getRecoveryFilesAsync") { promise: Promise ->
      moduleScope.launch {
        try {
          val files = audioRecorderService?.getRecoveryFiles() ?: emptyList()
          val filesList = files.map { file ->
            mapOf(
              "path" to file.path,
              "name" to file.name,
              "size" to file.size,
              "timestamp" to file.timestamp
            )
          }
          promise.resolve(filesList)
        } catch (e: Exception) {
          promise.reject("GET_RECOVERY_FILES_FAILED", e.message, e)
        }
      }
    }
    
    AsyncFunction("deleteRecoveryFileAsync") { path: String, promise: Promise ->
      moduleScope.launch {
        try {
          val deleted = audioRecorderService?.deleteRecoveryFile(path) ?: false
          promise.resolve(deleted)
        } catch (e: Exception) {
          promise.reject("DELETE_RECOVERY_FILE_FAILED", e.message, e)
        }
      }
    }
    
    AsyncFunction("cleanOldRecoveryFilesAsync") { daysToKeep: Int, promise: Promise ->
      moduleScope.launch {
        try {
          audioRecorderService?.cleanOldRecoveryFiles(daysToKeep)
          promise.resolve(true)
        } catch (e: Exception) {
          promise.reject("CLEAN_RECOVERY_FILES_FAILED", e.message, e)
        }
      }
    }

    OnDestroy {
      audioRecorderService?.release()
      audioRecorderService = null
      moduleScope.cancel()
    }
  }
}