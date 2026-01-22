package expo.modules.audiorecorder

/**
 * Конфигурация записи
 */
data class RecordingConfig(
    val sampleRate: Int = 44100,
    val bitRate: Int = 128000,
    val channels: Int = 1,
    val enableChunking: Boolean = false,
    val chunkDuration: Int = 1000 // ms
)

/**
 * Результат записи
 */
data class RecordingResult(
    val filePath: String,
    val duration: Double,
    val fileSize: Long
)

/**
 * Текущий статус записи
 */
data class RecordingStatus(
    val state: String,           // "idle", "recording", "paused"
    val filePath: String?,
    val duration: Double,
    val isRecording: Boolean,
    val isPaused: Boolean,
    val noiseLevel: Double
)

/**
 * Состояние для recovery
 */
data class RecordingState(
    val pcmFilePath: String,
    val outputFilePath: String,
    val startTime: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int
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
        return data.contentEquals(other.data) && sampleRate == other.sampleRate && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestamp.hashCode()
        return result
    }
}