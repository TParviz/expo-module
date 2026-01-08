package expo.modules.firstmodule

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import java.net.URL
import java.io.File
import java.io.IOException
import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import android.content.pm.PackageManager
import expo.modules.interfaces.permissions.Permissions
import androidx.core.content.ContextCompat
import android.os.Environment


class FirstModule : Module() {

  private var mediaRecorder: MediaRecorder? = null
  private var outputFile: String? = null
  private var isPaused = false

  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  override fun definition() = ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('FirstModule')` in JavaScript.
    Name("FirstModule")


    // Запрос разрешений
  AsyncFunction("requestPermissions") { promise: Promise ->
      val context = appContext.reactContext ?: run {
        promise.reject("ERROR", "Context is null", null)
        return@AsyncFunction
      }

      val permission = Manifest.permission.RECORD_AUDIO
      val granted = ContextCompat.checkSelfPermission(context, permission) == 
                    PackageManager.PERMISSION_GRANTED

      if (granted) {
        promise.resolve(mapOf("granted" to true))
      } else {
        // В реальном приложении нужно запросить разрешение через Activity
        promise.resolve(mapOf("granted" to false))
      }
  }

  // Начать запись
  AsyncFunction("startRecording") { outputPath: String?, promise: Promise ->
    try {
      if (mediaRecorder != null) {
        promise.reject("ALREADY_RECORDING", "Recording already in progress", null)
        return@AsyncFunction
      }

      val context = appContext.reactContext ?: run {
        promise.reject("ERROR", "Context is null", null)
        return@AsyncFunction
      }

      // val granted = Permissions.hasGrantedPermissions(
      //   appContext.permissions,
      //   Manifest.permission.RECORD_AUDIO
      // )
      
      // if (!granted) {
      //   promise.reject("PERMISSION_DENIED", "Audio recording permission not granted", null)
      //   return@AsyncFunction
      // }

      val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) 
        ?: context.filesDir
      
      val file = if (outputPath != null) {
        File(outputPath).also { 
          it.parentFile?.mkdirs()
        }
      } else {
        File(outputDir, "audio_${System.currentTimeMillis()}.m4a")
      }
      
      outputFile = file.absolutePath
      isPaused = false
      // Log.d(TAG, "Recording to: ${file.absolutePath}")

      mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
      } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
      }
      
      try {
        mediaRecorder?.apply {
          setAudioSource(MediaRecorder.AudioSource.MIC)
          setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
          setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
          setAudioEncodingBitRate(128000)
          setAudioSamplingRate(44100)
          setOutputFile(file.absolutePath)
          
          try {
            prepare()
            // Log.d(TAG, "MediaRecorder prepared")
          } catch (e: IOException) {
            throw IOException("Failed to prepare MediaRecorder: ${e.message}")
          }
          
          start()
          // Log.d(TAG, "Recording started")
        }
      } catch (e: Exception) {
        mediaRecorder?.release()
        mediaRecorder = null
        outputFile = null
        throw e
      }

      promise.resolve(mapOf(
        "uri" to file.absolutePath,
        "status" to "recording"
      ))

      sendEvent("onRecordingStatusChanged", mapOf(
        "isRecording" to true,
        "isPaused" to false,
        "uri" to file.absolutePath
      ))

    } catch (e: IOException) {
      // Log.e(TAG, "Recording failed", e)
      promise.reject("RECORDING_FAILED", "Failed to start recording: ${e.message}", e)
    } catch (e: Exception) {
      // Log.e(TAG, "Unexpected error", e)
      promise.reject("ERROR", "Unexpected error: ${e.message}", e)
    }
  }

    //stopRecording
  AsyncFunction("stopRecording") { promise: Promise ->
      try {
        if (mediaRecorder == null) {
          promise.reject("NO_RECORDING", "No recording in progress", null)
          return@AsyncFunction
        }

        // Log.d(TAG, "Stopping recording")
        
        try {
          // Если была пауза, сначала возобновляем чтобы корректно остановить
          if (isPaused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
          }
          
          mediaRecorder?.apply {
            stop()
            release()
          }
        } catch (e: RuntimeException) {
          // Log.e(TAG, "Stop failed, releasing anyway", e)
          mediaRecorder?.release()
        }
        
        mediaRecorder = null
        isPaused = false

        val uri = outputFile
        outputFile = null
        
        if (uri != null && File(uri).exists()) {
          // Log.d(TAG, "Recording saved to: $uri, size: ${File(uri).length()} bytes")
        } else {
          // Log.w(TAG, "Recording file not found or empty")
        }

        promise.resolve(mapOf(
          "uri" to uri,
          "status" to "stopped"
        ))

        sendEvent("onRecordingStatusChanged", mapOf(
          "isRecording" to false,
          "isPaused" to false,
          "uri" to uri
        ))

      } catch (e: Exception) {
        // Log.e(TAG, "Stop recording error", e)
        promise.reject("STOP_FAILED", "Failed to stop recording: ${e.message}", e)
      }
    }

   // Пауза записи (только для Android 7.0+)
   AsyncFunction("pauseRecording") { promise: Promise ->
    try {
      if (mediaRecorder == null) {
        promise.reject("NO_RECORDING", "No recording in progress", null)
        return@AsyncFunction
      }

      if (isPaused) {
        promise.reject("ALREADY_PAUSED", "Recording is already paused", null)
        return@AsyncFunction
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder?.pause()
        isPaused = true
        // Log.d(TAG, "Recording paused")
        
        promise.resolve(mapOf(
          "status" to "paused",
          "uri" to outputFile
        ))

        sendEvent("onRecordingStatusChanged", mapOf(
          "isRecording" to true,
          "isPaused" to true,
          "uri" to outputFile
        ))
      } else {
        promise.reject(
          "NOT_SUPPORTED", 
          "Pause recording is not supported on Android versions below 7.0 (API 24)", null
        )
      }
    } catch (e: Exception) {
      // Log.e(TAG, "Pause failed", e)
      promise.reject("PAUSE_FAILED", "Failed to pause recording: ${e.message}", e)
    }
  }

  // Возобновление записи (только для Android 7.0+)
  AsyncFunction("resumeRecording") { promise: Promise ->
    try {
      if (mediaRecorder == null) {
        promise.reject("NO_RECORDING", "No recording in progress", null)
        return@AsyncFunction
      }

      if (!isPaused) {
        promise.reject("NOT_PAUSED", "Recording is not paused", null)
        return@AsyncFunction
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder?.resume()
        isPaused = false
        // Log.d(TAG, "Recording resumed")
        
        promise.resolve(mapOf(
          "status" to "recording",
          "uri" to outputFile
        ))

        sendEvent("onRecordingStatusChanged", mapOf(
          "isRecording" to true,
          "isPaused" to false,
          "uri" to outputFile
        ))
      } else {
        promise.reject(
          "NOT_SUPPORTED", 
          "Resume recording is not supported on Android versions below 7.0 (API 24)", null
        )
      }
    } catch (e: Exception) {
      // Log.e(TAG, "Resume failed", e)
      promise.reject("RESUME_FAILED", "Failed to resume recording: ${e.message}", e)
    }
  }

  // Проверка статуса записи
  Function("isRecording") {
    return@Function mediaRecorder != null
  }

  // Проверка паузы
  Function("isPaused") {
    return@Function isPaused
  }

  // Получить текущий статус
  Function("getStatus") {
    return@Function mapOf(
      "isRecording" to (mediaRecorder != null),
      "isPaused" to isPaused,
      "uri" to outputFile
    )
  }

  OnDestroy {
    try {
      mediaRecorder?.apply {
        stop()
        release()
      }
      mediaRecorder = null
      isPaused = false
    } catch (e: Exception) {
      // Log.e(TAG, "Cleanup error", e)
    }
  }

    Events("onRecordingStatusChanged")

    // Enables the module to be used as a native view. Definition components that are accepted as part of
    // the view definition: Prop, Events.
    // View(FirstModuleView::class) {
    //   // Defines a setter for the `url` prop.
    //   Prop("url") { view: FirstModuleView, url: URL ->
    //     view.webView.loadUrl(url.toString())
    //   }
    //   // Defines an event that the view can send to JavaScript.
    //   Events("onLoad")
    // }
  }
}
