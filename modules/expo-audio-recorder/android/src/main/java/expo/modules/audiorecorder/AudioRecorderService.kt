package expo.modules.audiorecorder

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * AudioRecorderService - Сервис записи аудио
 * 
 * КЛЮЧЕВЫЕ ОСОБЕННОСТИ:
 * 1. Сохранение сырых PCM данных в файл для надёжного recovery
 * 2. При остановке/recovery конвертируем PCM → M4A
 * 3. Grace period - игнорирование событий первую секунду
 * 4. Детекция тишины (cantHearMicrophone)
 * 5. Выбор предпочтительного микрофона
 * 6. Периодическое сохранение состояния
 */
class AudioRecorderService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventEmitter: (String, Map<String, Any?>) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorderCore"
        
        // Директории
        private const val TEMP_DIR = "AudioRecorderTemp"
        private const val RECORDINGS_DIR = "Recordings"
        private const val RECOVERY_DIR = "RecoveryFiles"
        
        // Интервал сохранения состояния (каждые 5 сек)
        private const val STATE_SAVE_INTERVAL_MS = 5000L
        
        // Grace period - игнорируем события первую секунду
        private const val GRACE_PERIOD_MS = 1000L
        
        // Порог тишины
        private const val SILENCE_THRESHOLD_DB = -50.0
        private const val SILENCE_NOTIFY_DURATION_MS = 3000L
    }

    // Состояние записи
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var pcmOutputStream: FileOutputStream? = null
    
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    // Текущая запись
    private var currentRecordingId: String? = null
    private var currentPcmFile: File? = null
    private var currentConfig: RecordingConfig? = null
    
    // Тайминги
    private var recordingStartTime: Long = 0
    private var pausedDuration: Long = 0
    private var pauseStartTime: Long = 0
    private var lastStateSaveTime: Long = 0
    
    // Уровень шума
    private var currentNoiseLevel: Double = -160.0
    private val noiseSmoothingFactor = 0.3
    
    // Тишина
    private var silenceStartTime: Long = 0
    private var silenceNotified = false
    
    // Счётчики
    private var totalSamplesWritten: Long = 0
    private var chunkIndex: Int = 0
    
    // Чанки
    private val chunkBuffer = mutableListOf<Short>()
    private val chunkSampleRate = 16000
    
    // Менеджеры
    private val stateManager = RecordingStateManager(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Callback для внешних прерываний
    var onPauseRequested: (() -> Unit)? = null
    var onResumeRequested: (() -> Unit)? = null

    // ==================== PUBLIC API ====================

    /**
     * Начать запись
     */
    fun startRecording(config: RecordingConfig): String {
        if (isRecording.get()) {
            throw IllegalStateException("Recording already in progress")
        }

        Log.i(TAG, "Starting recording with config: $config")

        currentConfig = config
        currentRecordingId = "rec_${System.currentTimeMillis()}"

        // Создаём PCM файл для сырых данных
        currentPcmFile = createPcmFile(currentRecordingId!!)
        pcmOutputStream = FileOutputStream(currentPcmFile!!)

        // Инициализируем AudioRecord
        initializeAudioRecord(config)

        // Сбрасываем счётчики
        recordingStartTime = System.currentTimeMillis()
        pausedDuration = 0
        pauseStartTime = 0
        lastStateSaveTime = 0
        totalSamplesWritten = 0
        chunkIndex = 0
        silenceStartTime = 0
        silenceNotified = false
        chunkBuffer.clear()

        // Запускаем Foreground Service
        startForegroundService()

        // Начинаем запись
        isRecording.set(true)
        isPaused.set(false)
        audioRecord?.startRecording()

        // Сохраняем начальное состояние
        saveRecordingState()

        // Запускаем корутину записи
        recordingJob = scope.launch(Dispatchers.IO) {
            recordAudioLoop()
        }

        emitStateChange()
        
        // Логируем доступные микрофоны
        logAvailableMicrophones()

        Log.i(TAG, "Recording started: ${currentPcmFile?.absolutePath}")

        return getOutputFilePath()
    }

    /**
     * Остановить запись и сохранить файл
     */
    suspend fun stopRecording(): RecordingResult = withContext(Dispatchers.IO) {
        if (!isRecording.get()) {
            throw IllegalStateException("No active recording")
        }

        Log.i(TAG, "Stopping recording...")

        isRecording.set(false)
        isPaused.set(false)

        // Ждём завершения записи
        recordingJob?.join()

        // Закрываем PCM поток
        closePcmStream()

        // Конвертируем PCM → M4A
        val outputFile = createOutputFile()
        val duration = calculateDuration()
        
        val convertSuccess = PcmToM4aConverter.convert(
            pcmFile = currentPcmFile!!,
            outputFile = outputFile,
            sampleRate = currentConfig?.sampleRate ?: 44100,
            channels = currentConfig?.channels ?: 1,
            bitRate = currentConfig?.bitRate ?: 128000
        )

        if (!convertSuccess) {
            Log.e(TAG, "Failed to convert PCM to M4A")
            throw IllegalStateException("Failed to convert recording")
        }

        val fileSize = outputFile.length()

        // Очищаем временные файлы
        cleanupTempFiles()

        // Освобождаем ресурсы
        releaseResources()

        // Очищаем состояние
        stateManager.clearState()

        emitStateChange()
        emitRecordingEvent("completed", mapOf(
            "filePath" to outputFile.absolutePath,
            "duration" to duration,
            "fileSize" to fileSize
        ))

        Log.i(TAG, "Recording stopped: ${outputFile.absolutePath}, duration=${duration}s")

        RecordingResult(
            filePath = outputFile.absolutePath,
            duration = duration,
            fileSize = fileSize
        )
    }

    /**
     * Отменить запись без сохранения
     */
    fun cancelRecording() {
        if (!isRecording.get()) {
            Log.w(TAG, "No active recording to cancel")
            return
        }

        Log.i(TAG, "Canceling recording...")

        isRecording.set(false)
        isPaused.set(false)

        runBlocking {
            recordingJob?.join()
        }

        closePcmStream()
        cleanupTempFiles()
        releaseResources()
        stateManager.clearState()

        emitStateChange()
        emitRecordingEvent("canceled")

        Log.i(TAG, "Recording canceled")
    }

    /**
     * Поставить на паузу
     */
    fun pauseRecording() {
        if (!isRecording.get() || isPaused.get()) {
            throw IllegalStateException("Cannot pause")
        }

        Log.i(TAG, "Pausing recording...")

        isPaused.set(true)
        pauseStartTime = System.currentTimeMillis()

        saveRecordingState()
        updateNotification("paused")
        emitStateChange()
    }

    /**
     * Возобновить запись
     */
    fun resumeRecording() {
        if (!isRecording.get() || !isPaused.get()) {
            throw IllegalStateException("Cannot resume")
        }

        Log.i(TAG, "Resuming recording...")

        pausedDuration += System.currentTimeMillis() - pauseStartTime
        pauseStartTime = 0
        isPaused.set(false)

        saveRecordingState()
        updateNotification("recording")
        emitStateChange()
    }

    /**
     * Получить текущий статус
     */
    fun getStatus(): RecordingStatus {
        val state = when {
            !isRecording.get() -> "idle"
            isPaused.get() -> "paused"
            else -> "recording"
        }

        return RecordingStatus(
            state = state,
            filePath = if (isRecording.get()) getOutputFilePath() else null,
            duration = calculateDuration(),
            isRecording = isRecording.get(),
            isPaused = isPaused.get(),
            noiseLevel = currentNoiseLevel
        )
    }

    /**
     * Проверить есть ли незавершённая запись
     */
    fun hasUnfinishedRecording(): Boolean {
        return stateManager.hasUnfinishedRecording()
    }

    /**
     * Восстановить незавершённую запись
     */
    suspend fun recoverUnfinishedRecording(): RecoveryResult? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking for unfinished recording...")

        val state = stateManager.getState() ?: return@withContext null

        Log.i(TAG, "Found unfinished recording: ${state.recordingId}")

        val pcmFile = File(state.pcmFilePath)
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            Log.w(TAG, "PCM file not found or empty")
            stateManager.clearState()
            return@withContext null
        }

        Log.d(TAG, "PCM file size: ${pcmFile.length()} bytes")

        // Создаём файл для восстановленной записи
        val recoveryDir = getRecoveryDir()
        val recoveryFile = File(recoveryDir, "recovery_${System.currentTimeMillis()}.m4a")

        // Конвертируем PCM → M4A
        val convertSuccess = PcmToM4aConverter.convert(
            pcmFile = pcmFile,
            outputFile = recoveryFile,
            sampleRate = state.sampleRate,
            channels = state.channels,
            bitRate = state.bitRate
        )

        if (!convertSuccess || !recoveryFile.exists() || recoveryFile.length() == 0L) {
            Log.e(TAG, "Failed to convert recovered PCM")
            // Fallback: копируем PCM как есть (для диагностики)
            val fallbackFile = File(recoveryDir, "recovery_${System.currentTimeMillis()}.pcm")
            pcmFile.copyTo(fallbackFile, overwrite = true)
            stateManager.clearState()
            return@withContext null
        }

        // Удаляем PCM файл
        pcmFile.delete()
        stateManager.clearState()

        val duration = state.calculateDuration()
        val fileSize = recoveryFile.length()

        Log.i(TAG, "Recording recovered: ${recoveryFile.absolutePath}, duration=${duration}s")

        emitRecordingEvent("recoveryCompleted", mapOf(
            "filePath" to recoveryFile.absolutePath,
            "duration" to duration,
            "fileSize" to fileSize
        ))

        RecoveryResult(
            filePath = recoveryFile.absolutePath,
            originalPath = state.outputFilePath,
            duration = duration,
            fileSize = fileSize,
            timestamp = state.startTime,
            recovered = true
        )
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        if (isRecording.get()) {
            cancelRecording()
        }
        releaseResources()
    }

    // ==================== PUBLIC API: Microphones ====================

    /**
     * Получить список всех доступных микрофонов
     */
    fun getAvailableMicrophones(): List<MicrophoneInfo> {
        val microphones = mutableListOf<MicrophoneInfo>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            
            for (device in devices) {
                if (isMicrophoneType(device.type)) {
                    val info = MicrophoneInfo(
                        id = device.id,
                        type = device.type,
                        typeName = getDeviceTypeName(device.type),
                        name = device.productName?.toString() ?: getDeviceTypeName(device.type),
                        isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            device.address
                        } else null,
                        channelCounts = device.channelCounts?.toList() ?: emptyList(),
                        sampleRates = device.sampleRates?.toList() ?: emptyList()
                    )
                    microphones.add(info)
                }
            }
        } else {
            // Для старых версий Android возвращаем только встроенный микрофон
            microphones.add(MicrophoneInfo(
                id = 0,
                type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                typeName = "BUILTIN_MIC",
                name = "Built-in Microphone",
                isDefault = true,
                address = null,
                channelCounts = listOf(1, 2),
                sampleRates = listOf(8000, 16000, 44100, 48000)
            ))
        }

        return microphones
    }

    /**
     * Получить активный микрофон
     */
    fun getActiveMicrophone(): MicrophoneInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            
            // Приоритет: Bluetooth SCO > Wired > USB > Built-in
            val priorityOrder = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BUILTIN_MIC
            )

            for (type in priorityOrder) {
                val device = devices.find { it.type == type && isMicrophoneType(it.type) }
                if (device != null) {
                    return MicrophoneInfo(
                        id = device.id,
                        type = device.type,
                        typeName = getDeviceTypeName(device.type),
                        name = device.productName?.toString() ?: getDeviceTypeName(device.type),
                        isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            device.address
                        } else null,
                        channelCounts = device.channelCounts?.toList() ?: emptyList(),
                        sampleRates = device.sampleRates?.toList() ?: emptyList()
                    )
                }
            }
        }
        return null
    }

    /**
     * Проверить находимся ли в grace period
     */
    fun isInGracePeriod(): Boolean {
        if (recordingStartTime == 0L) return false
        return System.currentTimeMillis() - recordingStartTime < GRACE_PERIOD_MS
    }

    // ==================== PRIVATE: Recording Loop ====================

    private suspend fun recordAudioLoop() = withContext(Dispatchers.IO) {
        val bufferSize = 4096
        val buffer = ShortArray(bufferSize)

        Log.d(TAG, "Recording loop started")

        try {
            while (isRecording.get()) {
                if (isPaused.get()) {
                    delay(100)
                    continue
                }

                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                if (readSize > 0) {
                    // Записываем сырые PCM данные
                    writePcmData(buffer, readSize)

                    // Обновляем уровень шума
                    updateNoiseLevel(buffer, readSize)

                    // Проверяем тишину
                    checkSilence()

                    // Обрабатываем чанки для стриминга
                    if (currentConfig?.enableChunking == true) {
                        processChunk(buffer, readSize)
                    }

                    // Периодически сохраняем состояние
                    maybeSaveState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording loop error", e)
            emitRecordingEvent("audioFileError", mapOf(
                "error" to mapOf(
                    "code" to "RECORDING_LOOP_ERROR",
                    "message" to (e.message ?: "Unknown error")
                )
            ))
        }

        Log.d(TAG, "Recording loop ended")
    }

    private fun writePcmData(buffer: ShortArray, size: Int) {
        try {
            val byteBuffer = ByteArray(size * 2)
            for (i in 0 until size) {
                val sample = buffer[i].toInt()
                byteBuffer[i * 2] = (sample and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            
            pcmOutputStream?.write(byteBuffer)
            totalSamplesWritten += size
            
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
        }
    }

    private fun closePcmStream() {
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
            pcmOutputStream = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PCM stream", e)
        }
    }

    // ==================== PRIVATE: Noise & Silence ====================

    private fun updateNoiseLevel(buffer: ShortArray, size: Int) {
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble() / 32768.0
            sum += sample * sample
        }
        val rms = sqrt(sum / size)
        val db = if (rms > 0) 20 * log10(rms) else -160.0

        currentNoiseLevel = if (currentNoiseLevel == -160.0) {
            db
        } else {
            currentNoiseLevel * (1 - noiseSmoothingFactor) + db * noiseSmoothingFactor
        }
    }

    private fun checkSilence() {
        if (currentNoiseLevel < SILENCE_THRESHOLD_DB) {
            if (silenceStartTime == 0L) {
                silenceStartTime = System.currentTimeMillis()
            } else {
                val duration = System.currentTimeMillis() - silenceStartTime
                if (duration >= SILENCE_NOTIFY_DURATION_MS && !silenceNotified) {
                    emitRecordingEvent("cantHearMicrophone", mapOf(
                        "silenceDuration" to (duration / 1000.0)
                    ))
                    silenceNotified = true
                    Log.w(TAG, "Silence detected for ${duration}ms")
                }
            }
        } else {
            silenceStartTime = 0L
            silenceNotified = false
        }
    }

    // ==================== PRIVATE: Chunking ====================

    private fun processChunk(buffer: ShortArray, size: Int) {
        for (i in 0 until size) {
            chunkBuffer.add(buffer[i])
        }

        val chunkSamples = (currentConfig!!.sampleRate * currentConfig!!.chunkDuration) / 1000

        if (chunkBuffer.size >= chunkSamples) {
            try {
                val downsampled = downsample(
                    chunkBuffer.toShortArray(),
                    currentConfig!!.sampleRate,
                    chunkSampleRate
                )

                // Отправляем чанк
                eventEmitter("onAudioChunk", mapOf(
                    "data" to downsampled.toList(),
                    "sampleRate" to chunkSampleRate,
                    "timestamp" to System.currentTimeMillis()
                ))

                emitRecordingEvent("chunk", mapOf(
                    "chunkIndex" to chunkIndex,
                    "chunkData" to downsampled.toList()
                ))

                chunkIndex++

            } catch (e: Exception) {
                Log.e(TAG, "Chunk processing error", e)
                emitRecordingEvent("chunkWasLost", mapOf(
                    "chunkIndex" to chunkIndex,
                    "error" to mapOf(
                        "code" to "CHUNK_PROCESSING_ERROR",
                        "message" to (e.message ?: "Unknown")
                    )
                ))
            }

            chunkBuffer.clear()
        }
    }

    private fun downsample(data: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        val ratio = fromRate.toFloat() / toRate.toFloat()
        val outputSize = (data.size / ratio).toInt()
        val output = FloatArray(outputSize)

        for (i in output.indices) {
            val srcIndex = (i * ratio).toInt()
            if (srcIndex < data.size) {
                output[i] = data[srcIndex] / 32768.0f
            }
        }

        return output
    }

    // ==================== PRIVATE: State Management ====================

    private fun maybeSaveState() {
        val now = System.currentTimeMillis()
        if (now - lastStateSaveTime >= STATE_SAVE_INTERVAL_MS) {
            saveRecordingState()
            lastStateSaveTime = now
        }
    }

    private fun saveRecordingState() {
        stateManager.saveState(
            RecordingState(
                recordingId = currentRecordingId ?: return,
                pcmFilePath = currentPcmFile?.absolutePath ?: return,
                outputFilePath = getOutputFilePath(),
                startTime = recordingStartTime,
                pausedDuration = pausedDuration,
                isPaused = isPaused.get(),
                pauseStartTime = pauseStartTime,
                sampleRate = currentConfig?.sampleRate ?: 44100,
                channels = currentConfig?.channels ?: 1,
                bitRate = currentConfig?.bitRate ?: 128000,
                totalSamplesWritten = totalSamplesWritten
            )
        )
    }

    private fun calculateDuration(): Double {
        if (recordingStartTime == 0L) return 0.0

        val endTime = if (isPaused.get()) pauseStartTime else System.currentTimeMillis()
        val totalTime = endTime - recordingStartTime - pausedDuration
        return totalTime / 1000.0
    }

    // ==================== PRIVATE: AudioRecord ====================

    private fun initializeAudioRecord(config: RecordingConfig) {
        val channelConfig = if (config.channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }

        // Устанавливаем предпочтительный микрофон (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && config.microphoneId != null) {
            setPreferredMicrophone(config.microphoneId)
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private fun setPreferredMicrophone(microphoneId: Int) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        
        val targetDevice = devices.find { it.id == microphoneId }
        
        if (targetDevice != null) {
            val success = audioRecord?.setPreferredDevice(targetDevice) ?: false
            if (success) {
                Log.i(TAG, "Set preferred microphone: ${targetDevice.productName} (id=$microphoneId)")
                emitRecordingEvent("microphoneSelected", mapOf(
                    "id" to microphoneId,
                    "name" to (targetDevice.productName?.toString() ?: "Unknown"),
                    "type" to targetDevice.type
                ))
            } else {
                Log.w(TAG, "Failed to set preferred microphone: $microphoneId")
                emitRecordingEvent("microphoneSelectionFailed", mapOf(
                    "id" to microphoneId,
                    "reason" to "setPreferredDevice returned false"
                ))
            }
        } else {
            Log.w(TAG, "Microphone not found: $microphoneId")
            emitRecordingEvent("microphoneSelectionFailed", mapOf(
                "id" to microphoneId,
                "reason" to "Microphone not found"
            ))
        }
    }

    private fun logAvailableMicrophones() {
        val mics = getAvailableMicrophones()
        Log.d(TAG, "Available microphones: ${mics.size}")
        mics.forEach { mic ->
            Log.d(TAG, "  - ${mic.name} (${mic.typeName})")
        }
        
        emitRecordingEvent("microphonesDetected", mapOf(
            "microphones" to mics.map { micToMap(it) },
            "activeMicrophone" to getActiveMicrophone()?.let { micToMap(it) }
        ))
    }

    private fun micToMap(info: MicrophoneInfo): Map<String, Any?> {
        return mapOf(
            "id" to info.id,
            "type" to info.type,
            "typeName" to info.typeName,
            "name" to info.name,
            "isDefault" to info.isDefault,
            "address" to info.address,
            "channelCounts" to info.channelCounts,
            "sampleRates" to info.sampleRates
        )
    }

    private fun isMicrophoneType(type: Int): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_TELEPHONY -> true
            else -> false
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            else -> "UNKNOWN($type)"
        }
    }

    // ==================== PRIVATE: Files ====================

    private fun createPcmFile(recordingId: String): File {
        val tempDir = getTempDir()
        return File(tempDir, "${recordingId}.pcm")
    }

    private fun createOutputFile(): File {
        val recordingsDir = getRecordingsDir()
        val timestamp = System.currentTimeMillis()
        return File(recordingsDir, "recording_$timestamp.m4a")
    }

    private fun getOutputFilePath(): String {
        val recordingsDir = getRecordingsDir()
        return File(recordingsDir, "recording_${currentRecordingId}.m4a").absolutePath
    }

    private fun getTempDir(): File {
        val dir = File(context.getExternalFilesDir(null), TEMP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), RECORDINGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getRecoveryDir(): File {
        val dir = File(context.getExternalFilesDir(null), RECOVERY_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cleanupTempFiles() {
        currentPcmFile?.delete()
        currentPcmFile = null
    }

    private fun releaseResources() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        stopForegroundService()

        currentRecordingId = null
        currentConfig = null
        chunkBuffer.clear()
        currentNoiseLevel = -160.0
    }

    // ==================== PRIVATE: Foreground Service ====================

    private fun startForegroundService() {
        try {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    private fun updateNotification(state: String) {
        try {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_UPDATE
                putExtra("state", state)
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    // ==================== PRIVATE: Events ====================

    private fun emitStateChange() {
        val status = getStatus()
        eventEmitter("onRecordingStateChanged", mapOf(
            "state" to status.state,
            "filePath" to status.filePath,
            "duration" to status.duration,
            "isRecording" to status.isRecording,
            "isPaused" to status.isPaused,
            "noiseLevel" to status.noiseLevel
        ))
    }

    private fun emitRecordingEvent(type: String, extras: Map<String, Any?> = emptyMap()) {
        val event = mutableMapOf<String, Any?>(
            "type" to type,
            "timestamp" to System.currentTimeMillis()
        )
        event.putAll(extras)
        eventEmitter("onRecordingEvent", event)
        Log.d(TAG, "RecordingEvent: $type")
    }
}