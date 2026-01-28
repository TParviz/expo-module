package expo.modules.audiorecorder

/**
 * Конфигурация записи
 */
data class RecordingConfig(
    val sampleRate: Int = 44100,
    val bitRate: Int = 128000,
    val channels: Int = 1,
    val enableChunking: Boolean = false,
    val chunkDuration: Int = 1000,
    val microphoneId: Int? = null,
    val maxDuration: Int = 0  // 0 = без лимита, >0 = секунды
)

/**
 * Результат записи
 */
data class RecordingResult(
    val filePath: String,
    val duration: Double,  // seconds
    val fileSize: Long     // bytes
)

/**
 * Результат восстановления
 */
data class RecoveryResult(
    val filePath: String,
    val originalPath: String,
    val duration: Double,
    val fileSize: Long,
    val timestamp: Long,
    val recovered: Boolean
)

/**
 * Статус записи
 */
data class RecordingStatus(
    val state: String,           // "idle" | "recording" | "paused"
    val filePath: String?,
    val duration: Double,
    val isRecording: Boolean,
    val isPaused: Boolean,
    val noiseLevel: Double       // dB, -160 to 0
)

/**
 * Аудио чанк для стриминга
 */
data class AudioChunk(
    val data: FloatArray,
    val sampleRate: Int,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        return data.contentEquals(other.data) && 
               sampleRate == other.sampleRate && 
               timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * Файл восстановления
 */
data class RecoveryFile(
    val path: String,
    val name: String,
    val size: Long,
    val timestamp: Long
)

/**
 * Информация о файле
 */
data class FileInfo(
    val mime: String,
    val duration: Double,  // seconds
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int
)

/**
 * Состояние записи для recovery
 */
data class RecordingState(
    val recordingId: String,
    val pcmFilePath: String,
    val outputFilePath: String,
    val startTime: Long,
    val pausedDuration: Long,
    val isPaused: Boolean,
    val pauseStartTime: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int,
    val maxDuration: Long, 
    val timestamp: Long,
    val totalSamplesWritten: Long
) {
    /**
     * Рассчитать длительность записи
     */
    fun calculateDuration(): Double {
        // Используем количество записанных сэмплов для точного расчёта
        if (totalSamplesWritten > 0 && sampleRate > 0) {
            return totalSamplesWritten.toDouble() / sampleRate
        }
        
        // Fallback на время
        val currentTime = System.currentTimeMillis()
        val endTime = if (isPaused) pauseStartTime else currentTime
        val totalTime = endTime - startTime - pausedDuration
        return totalTime / 1000.0
    }
}

/**
 * Информация о микрофоне
 */
data class MicrophoneInfo(
    val id: Int,
    val type: Int,
    val typeName: String,
    val name: String,
    val isDefault: Boolean,
    val address: String?,
    val channelCounts: List<Int>,
    val sampleRates: List<Int>
)