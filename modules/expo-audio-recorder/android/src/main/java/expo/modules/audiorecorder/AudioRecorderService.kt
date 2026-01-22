package expo.modules.audiorecorder

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Чистый аудио рекордер
 * 
 * Записывает аудио в PCM формат, конвертирует в M4A при остановке.
 * Не содержит логики прерываний - только запись.
 * 
 * Особенности:
 * - Запись в PCM для надёжного recovery при force-kill
 * - Конвертация PCM → M4A при stopRecording()
 * - Стриминг аудио чанков для real-time обработки
 * - Расчёт уровня шума в dB
 */
class AudioRecorderService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventEmitter: (String, Map<String, Any?>) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorderCore"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    
    // Файлы
    private var pcmFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null
    private var currentFilePath: String? = null
    
    // Состояние
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    // Тайминги
    private var recordingStartTime: Long = 0
    private var pausedDuration: Long = 0
    private var pauseStartTime: Long = 0
    private var totalSamplesWritten: Long = 0
    
    // Конфигурация
    private var config: RecordingConfig? = null
    
    // Стриминг чанков
    private val chunkSampleRate = 16000
    private var chunkBuffer = mutableListOf<Short>()
    private var lastChunkTime = 0L
    
    // Уровень шума
    private var currentNoiseLevel = -160.0

    // Recovery
    private val stateManager = RecordingStateManager(context)

    // Конвертер
    private val converter = PcmToM4aConverter()

    /**
     * Начать запись
     */
    fun startRecording(recordingConfig: RecordingConfig): String {
        if (isRecording.get()) {
            throw IllegalStateException("Recording already in progress")
        }

        config = recordingConfig
        
        // Создаём PCM файл
        pcmFile = createPcmFile()
        pcmOutputStream = FileOutputStream(pcmFile)
        
        // Создаём путь для финального M4A
        currentFilePath = createOutputFilePath()
        
        // Сброс таймингов
        recordingStartTime = System.currentTimeMillis()
        pausedDuration = 0
        pauseStartTime = 0
        totalSamplesWritten = 0
        chunkBuffer.clear()
        lastChunkTime = System.currentTimeMillis()

        // Инициализируем AudioRecord
        initializeAudioRecord(recordingConfig)

        // Запускаем Foreground Service
        startForegroundService()

        // Сохраняем состояние для recovery
        stateManager.saveState(
            RecordingState(
                pcmFilePath = pcmFile!!.absolutePath,
                outputFilePath = currentFilePath!!,
                startTime = recordingStartTime,
                sampleRate = recordingConfig.sampleRate,
                channels = recordingConfig.channels,
                bitRate = recordingConfig.bitRate
            )
        )

        // Запускаем запись
        isRecording.set(true)
        isPaused.set(false)
        
        audioRecord?.startRecording()
        
        recordingJob = scope.launch(Dispatchers.IO) {
            recordAudioLoop()
        }

        emitStateChange()
        
        Log.i(TAG, "Recording started: $currentFilePath")
        return currentFilePath!!
    }

    /**
     * Остановить запись и получить результат
     */
    suspend fun stopRecording(): RecordingResult {
        if (!isRecording.get()) {
            throw IllegalStateException("No active recording")
        }

        isRecording.set(false)
        isPaused.set(false)

        // Ждём завершения записи
        recordingJob?.cancelAndJoin()

        // Закрываем PCM поток
        try {
            pcmOutputStream?.flush()
            pcmOutputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PCM stream", e)
        }

        // Останавливаем AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        // Конвертируем PCM → M4A
        val cfg = config ?: throw IllegalStateException("No config")
        val outputFile = File(currentFilePath!!)
        
        val convertSuccess = converter.convert(
            pcmFile = pcmFile!!,
            outputFile = outputFile,
            sampleRate = cfg.sampleRate,
            channels = cfg.channels,
            bitRate = cfg.bitRate
        )

        if (!convertSuccess) {
            Log.e(TAG, "Failed to convert PCM to M4A")
            // Копируем PCM как fallback
            pcmFile?.copyTo(outputFile, overwrite = true)
        }

        // Удаляем PCM файл
        pcmFile?.delete()
        pcmFile = null

        // Очищаем состояние recovery
        stateManager.clearState()

        // Останавливаем Foreground Service
        stopForegroundService()

        val duration = calculateDuration()
        val fileSize = outputFile.length()

        emitStateChange()
        
        // Отправляем событие завершения
        eventEmitter("onRecordingEvent", mapOf(
            "type" to "completed",
            "filePath" to currentFilePath,
            "duration" to duration,
            "fileSize" to fileSize
        ))

        Log.i(TAG, "Recording stopped: duration=${duration}s, size=${fileSize}")

        return RecordingResult(
            filePath = currentFilePath!!,
            duration = duration,
            fileSize = fileSize
        )
    }

    /**
     * Приостановить запись
     */
    fun pauseRecording() {
        if (!isRecording.get() || isPaused.get()) {
            throw IllegalStateException("Cannot pause: not recording or already paused")
        }

        isPaused.set(true)
        pauseStartTime = System.currentTimeMillis()
        
        updateForegroundServiceNotification("paused")
        emitStateChange()
        
        Log.i(TAG, "Recording paused")
    }

    /**
     * Возобновить запись
     */
    fun resumeRecording() {
        if (!isRecording.get() || !isPaused.get()) {
            throw IllegalStateException("Cannot resume: not recording or not paused")
        }

        pausedDuration += System.currentTimeMillis() - pauseStartTime
        pauseStartTime = 0
        isPaused.set(false)
        
        updateForegroundServiceNotification("recording")
        emitStateChange()
        
        Log.i(TAG, "Recording resumed")
    }

    /**
     * Отменить запись (без сохранения)
     */
    fun cancelRecording() {
        if (!isRecording.get()) {
            return
        }

        isRecording.set(false)
        isPaused.set(false)

        recordingJob?.cancel()

        try {
            pcmOutputStream?.close()
        } catch (e: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        // Удаляем файлы
        pcmFile?.delete()
        currentFilePath?.let { File(it).delete() }

        stateManager.clearState()
        stopForegroundService()
        
        emitStateChange()
        
        eventEmitter("onRecordingEvent", mapOf(
            "type" to "canceled"
        ))
        
        Log.i(TAG, "Recording canceled")
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
            filePath = currentFilePath,
            duration = calculateDuration(),
            isRecording = isRecording.get(),
            isPaused = isPaused.get(),
            noiseLevel = currentNoiseLevel
        )
    }

    /**
     * Проверить есть ли незавершённая запись для recovery
     */
    fun hasUnfinishedRecording(): Boolean {
        return stateManager.hasUnfinishedRecording()
    }

    /**
     * Восстановить незавершённую запись
     */
    suspend fun recoverUnfinishedRecording(): RecordingResult? {
        val state = stateManager.getState() ?: return null
        
        val pcmFile = File(state.pcmFilePath)
        if (!pcmFile.exists()) {
            stateManager.clearState()
            return null
        }

        val outputFile = File(state.outputFilePath)
        
        val success = converter.convert(
            pcmFile = pcmFile,
            outputFile = outputFile,
            sampleRate = state.sampleRate,
            channels = state.channels,
            bitRate = state.bitRate
        )

        if (success) {
            pcmFile.delete()
            stateManager.clearState()
            
            val duration = outputFile.length().toDouble() / (state.sampleRate * state.channels * 2) 
            
            return RecordingResult(
                filePath = state.outputFilePath,
                duration = duration,
                fileSize = outputFile.length()
            )
        }

        return null
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        if (isRecording.get()) {
            cancelRecording()
        }
    }

    // === Private методы ===

    private fun initializeAudioRecord(config: RecordingConfig) {
        val channelConfig = if (config.channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }

        Log.d(TAG, "AudioRecord initialized: sampleRate=${config.sampleRate}, channels=${config.channels}, bufferSize=$bufferSize")
    }

    private suspend fun recordAudioLoop() {
        val cfg = config ?: return
        val bufferSize = 4096
        val buffer = ShortArray(bufferSize)

        while (isRecording.get()) {
            if (isPaused.get()) {
                delay(50)
                continue
            }

            val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
            
            if (read > 0) {
                // Записываем в PCM файл
                writePcmData(buffer, read)
                
                // Обновляем счётчик сэмплов
                totalSamplesWritten += read
                
                // Вычисляем уровень шума
                calculateNoiseLevel(buffer, read)
                
                // Обрабатываем чанки для стриминга
                if (cfg.enableChunking) {
                    processChunk(buffer, read, cfg)
                }
            }
        }
    }

    private fun writePcmData(buffer: ShortArray, count: Int) {
        try {
            val byteBuffer = ByteArray(count * 2)
            for (i in 0 until count) {
                val sample = buffer[i]
                byteBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
            pcmOutputStream?.write(byteBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PCM data", e)
        }
    }

    private fun calculateNoiseLevel(buffer: ShortArray, count: Int) {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        val db = if (rms > 0) 20 * log10(rms) else -160.0
        
        // Сглаживание
        currentNoiseLevel = currentNoiseLevel * 0.7 + db * 0.3
    }

    private fun processChunk(buffer: ShortArray, count: Int, cfg: RecordingConfig) {
        // Даунсэмплинг если нужно
        val ratio = cfg.sampleRate / chunkSampleRate
        
        for (i in 0 until count step ratio) {
            chunkBuffer.add(buffer[i])
        }

        val chunkSamples = chunkSampleRate * cfg.chunkDuration / 1000
        val now = System.currentTimeMillis()
        
        if (chunkBuffer.size >= chunkSamples || now - lastChunkTime >= cfg.chunkDuration) {
            if (chunkBuffer.isNotEmpty()) {
                val chunk = chunkBuffer.take(chunkSamples.coerceAtMost(chunkBuffer.size))
                val floatData = chunk.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
                
                eventEmitter("onAudioChunk", mapOf(
                    "data" to floatData.toList(),
                    "sampleRate" to chunkSampleRate,
                    "timestamp" to now
                ))
                
                chunkBuffer = chunkBuffer.drop(chunk.size).toMutableList()
                lastChunkTime = now
            }
        }
    }

    private fun calculateDuration(): Double {
        if (!isRecording.get() && recordingStartTime == 0L) {
            return 0.0
        }
        
        val endTime = if (isRecording.get()) System.currentTimeMillis() else System.currentTimeMillis()
        val totalPaused = if (isPaused.get() && pauseStartTime > 0) {
            pausedDuration + (System.currentTimeMillis() - pauseStartTime)
        } else {
            pausedDuration
        }
        
        return (endTime - recordingStartTime - totalPaused) / 1000.0
    }

    private fun createPcmFile(): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val tempDir = File(dir, "temp")
        if (!tempDir.exists()) tempDir.mkdirs()
        return File(tempDir, "recording_${System.currentTimeMillis()}.pcm")
    }

    private fun createOutputFilePath(): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val recordingsDir = File(dir, "Recordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
        return File(recordingsDir, "recording_${System.currentTimeMillis()}.m4a").absolutePath
    }

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

    private fun updateForegroundServiceNotification(state: String) {
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
}