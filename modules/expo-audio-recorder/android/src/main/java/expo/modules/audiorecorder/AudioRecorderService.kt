package expo.modules.audiorecorder

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * 7. Автоматическая остановка по maxDuration
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

    // === Max Duration Timer ===
    private val mainHandler = Handler(Looper.getMainLooper())
    private var maxDurationRunnable: Runnable? = null
    private var maxDurationSeconds: Int = 0

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
        maxDurationSeconds = config.maxDuration

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

        // === Запускаем таймер maxDuration ===
        if (maxDurationSeconds > 0) {
            startMaxDurationTimer()
            Log.i(TAG, "Max duration timer started: ${maxDurationSeconds}s")
        }

        emitStateChange()
        
        // Логируем доступные микрофоны
        logAvailableMicrophones()

        Log.i(TAG, "Recording started: ${currentPcmFile?.absolutePath}")

        return getOutputFilePath()
    }

    /**
     * Остановить запись и сохранить файл
     * 
     * @param reason Причина остановки: "user" | "duration" | "error"
     */
    suspend fun stopRecording(reason: String = "user"): RecordingResult = withContext(Dispatchers.IO) {
        if (!isRecording.get()) {
            throw IllegalStateException("No active recording")
        }

        Log.i(TAG, "Stopping recording, reason: $reason")

        // Останавливаем таймер
        stopMaxDurationTimer()

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
        
        // Отправляем событие completed с reason
        emitRecordingEvent("completed", mapOf(
            "filePath" to outputFile.absolutePath,
            "duration" to duration,
            "fileSize" to fileSize,
            "reason" to reason  // <-- NEW: "user" | "duration" | "error"
        ))

        Log.i(TAG, "Recording stopped: ${outputFile.absolutePath}, duration=${duration}s, reason=$reason")

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

        // Останавливаем таймер
        stopMaxDurationTimer()

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
            Log.w(TAG, "Cannot pause: isRecording=${isRecording.get()}, isPaused=${isPaused.get()}")
            return
        }

        Log.i(TAG, "Pausing recording...")

        isPaused.set(true)
        pauseStartTime = System.currentTimeMillis()
        
        // Пауза таймера maxDuration
        pauseMaxDurationTimer()
        
        updateNotification("paused")
        emitStateChange()

        Log.i(TAG, "Recording paused")
    }

    /**
     * Возобновить запись
     */
    fun resumeRecording() {
        if (!isRecording.get() || !isPaused.get()) {
            Log.w(TAG, "Cannot resume: isRecording=${isRecording.get()}, isPaused=${isPaused.get()}")
            return
        }

        Log.i(TAG, "Resuming recording...")

        pausedDuration += System.currentTimeMillis() - pauseStartTime
        pauseStartTime = 0
        isPaused.set(false)
        
        // Возобновление таймера maxDuration
        resumeMaxDurationTimer()
        
        updateNotification("recording")
        emitStateChange()

        Log.i(TAG, "Recording resumed")
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
            filePath = currentPcmFile?.absolutePath,
            duration = calculateCurrentDuration(),
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
     * Проверить находимся ли в grace period
     */
    fun isInGracePeriod(): Boolean {
        if (!isRecording.get()) return false
        val elapsed = System.currentTimeMillis() - recordingStartTime
        return elapsed < GRACE_PERIOD_MS
    }

    /**
     * Получить список микрофонов
     */
    fun getAvailableMicrophones(): List<MicrophoneInfo> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices
            .filter { isMicrophoneType(it.type) }
            .map { device ->
                MicrophoneInfo(
                    id = device.id,
                    type = device.type,
                    typeName = getDeviceTypeName(device.type),
                    name = device.productName?.toString() ?: "Unknown",
                    isDefault = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.address
                    } else null,
                    channelCounts = device.channelCounts?.toList() ?: emptyList(),
                    sampleRates = device.sampleRates?.toList() ?: emptyList()
                )
            }
    }

    /**
     * Получить активный микрофон
     */
    fun getActiveMicrophone(): MicrophoneInfo? {
        val mics = getAvailableMicrophones()
        // Приоритет: Bluetooth > Wired > USB > Built-in
        return mics.find { it.typeName == "BLUETOOTH_SCO" }
            ?: mics.find { it.typeName == "WIRED_HEADSET" }
            ?: mics.find { it.typeName == "USB_HEADSET" || it.typeName == "USB_DEVICE" }
            ?: mics.find { it.typeName == "BUILTIN_MIC" }
            ?: mics.firstOrNull()
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        if (isRecording.get()) {
            cancelRecording()
        }
        stopMaxDurationTimer()
    }

    // ==================== MAX DURATION TIMER ====================

    private var timerPausedAt: Long = 0
    private var remainingDurationMs: Long = 0

    private fun startMaxDurationTimer() {
        if (maxDurationSeconds <= 0) return

        remainingDurationMs = maxDurationSeconds * 1000L
        
        maxDurationRunnable = Runnable {
            Log.i(TAG, "Max duration reached, auto-stopping recording")
            
            // Останавливаем запись из main thread
            scope.launch {
                try {
                    stopRecording(reason = "duration")
                } catch (e: Exception) {
                    Log.e(TAG, "Error auto-stopping recording", e)
                }
            }
        }
        
        mainHandler.postDelayed(maxDurationRunnable!!, remainingDurationMs)
        Log.d(TAG, "Max duration timer scheduled for ${remainingDurationMs}ms")
    }

    private fun stopMaxDurationTimer() {
        maxDurationRunnable?.let { 
            mainHandler.removeCallbacks(it) 
            Log.d(TAG, "Max duration timer stopped")
        }
        maxDurationRunnable = null
        remainingDurationMs = 0
        timerPausedAt = 0
    }

    private fun pauseMaxDurationTimer() {
        if (maxDurationRunnable == null || maxDurationSeconds <= 0) return
        
        timerPausedAt = System.currentTimeMillis()
        
        // Вычисляем сколько осталось
        val elapsed = System.currentTimeMillis() - recordingStartTime - pausedDuration
        remainingDurationMs = (maxDurationSeconds * 1000L) - elapsed
        
        // Убираем callback
        mainHandler.removeCallbacks(maxDurationRunnable!!)
        
        Log.d(TAG, "Max duration timer paused, remaining: ${remainingDurationMs}ms")
    }

    private fun resumeMaxDurationTimer() {
        if (maxDurationRunnable == null || maxDurationSeconds <= 0 || remainingDurationMs <= 0) return
        
        // Перезапускаем с оставшимся временем
        mainHandler.postDelayed(maxDurationRunnable!!, remainingDurationMs)
        timerPausedAt = 0
        
        Log.d(TAG, "Max duration timer resumed, remaining: ${remainingDurationMs}ms")
    }

    // ==================== PRIVATE: Recording Loop ====================

    private suspend fun recordAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            currentConfig?.sampleRate ?: 44100,
            if ((currentConfig?.channels ?: 1) == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val buffer = ShortArray(bufferSize / 2)

        while (isRecording.get()) {
            if (isPaused.get()) {
                delay(50)
                continue
            }

            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (readCount > 0) {
                // Записываем в PCM файл
                writePcmData(buffer, readCount)
                
                // Обновляем уровень шума
                updateNoiseLevel(buffer, readCount)
                
                // Проверяем тишину
                checkSilence()
                
                // Обрабатываем чанки если включено
                if (currentConfig?.enableChunking == true) {
                    processChunk(buffer, readCount)
                }
                
                // Периодически сохраняем состояние
                saveStateIfNeeded()
                
                // Отправляем обновление состояния
                emitStateChange()
            }
        }
    }

    private fun writePcmData(buffer: ShortArray, count: Int) {
        try {
            val byteBuffer = ByteArray(count * 2)
            for (i in 0 until count) {
                val sample = buffer[i]
                byteBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            pcmOutputStream?.write(byteBuffer)
            totalSamplesWritten += count
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
        }
    }

    private fun closePcmStream() {
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PCM stream", e)
        }
        pcmOutputStream = null
    }

    // ==================== PRIVATE: Noise & Silence ====================

    private fun updateNoiseLevel(buffer: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        val db = if (rms > 0) 20 * log10(rms) else -160.0

        currentNoiseLevel = currentNoiseLevel * (1 - noiseSmoothingFactor) + db * noiseSmoothingFactor
    }

    private fun checkSilence() {
        val now = System.currentTimeMillis()
        
        if (currentNoiseLevel < SILENCE_THRESHOLD_DB) {
            if (silenceStartTime == 0L) {
                silenceStartTime = now
            } else if (!silenceNotified && (now - silenceStartTime) > SILENCE_NOTIFY_DURATION_MS) {
                silenceNotified = true
                emitRecordingEvent("cantHearMicrophone", mapOf(
                    "silenceDuration" to ((now - silenceStartTime) / 1000.0)
                ))
            }
        } else {
            silenceStartTime = 0
            silenceNotified = false
        }
    }

    // ==================== PRIVATE: Chunks ====================

    private fun processChunk(buffer: ShortArray, count: Int) {
        // Добавляем сэмплы в буфер
        for (i in 0 until count) {
            chunkBuffer.add(buffer[i])
        }

        val chunkSamples = (chunkSampleRate * (currentConfig?.chunkDuration ?: 1000) / 1000)
        
        while (chunkBuffer.size >= chunkSamples) {
            val chunkData = chunkBuffer.take(chunkSamples).map { it.toFloat() / Short.MAX_VALUE }
            repeat(chunkSamples) { chunkBuffer.removeAt(0) }

            emitRecordingEvent("chunk", mapOf(
                "chunkIndex" to chunkIndex,
                "chunkData" to chunkData
            ))
            
            chunkIndex++
        }
    }

    // ==================== PRIVATE: State Management ====================

    private fun saveStateIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastStateSaveTime > STATE_SAVE_INTERVAL_MS) {
            saveRecordingState()
            lastStateSaveTime = now
        }
    }

    private fun saveRecordingState() {
        val state = RecordingState(
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
            maxDuration = calculateCurrentDuration().toLong(),
            timestamp = System.currentTimeMillis(),
            totalSamplesWritten = totalSamplesWritten
        )
        stateManager.saveState(state)
    }

    private fun calculateCurrentDuration(): Double {
        if (!isRecording.get()) return 0.0
        
        val now = System.currentTimeMillis()
        val currentPausedDuration = if (isPaused.get()) {
            pausedDuration + (now - pauseStartTime)
        } else {
            pausedDuration
        }
        
        val totalTime = now - recordingStartTime - currentPausedDuration
        return totalTime / 1000.0
    }

    private fun calculateDuration(): Double {
        val endTime = System.currentTimeMillis()
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