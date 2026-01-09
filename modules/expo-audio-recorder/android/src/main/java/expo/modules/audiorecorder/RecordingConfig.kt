package expo.modules.audiorecorder

data class RecordingConfig(
  val sampleRate: Int = 44100,
  val bitRate: Int = 128000,
  val channels: Int = 1,
  val enableChunking: Boolean = false,
  val chunkDuration: Int = 1000 // milliseconds
)

data class RecordingResult(
  val filePath: String,
  val duration: Double, // seconds
  val fileSize: Long // bytes
)

data class RecordingStatus(
  val state: String,
  val filePath: String?,
  val duration: Double,
  val isRecording: Boolean,
  val isPaused: Boolean,
  val noiseLevel: Double // in dB, range -160 to 0
)

data class AudioChunk(
  val data: FloatArray,
  val sampleRate: Int,
  val timestamp: Long
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as AudioChunk

    if (!data.contentEquals(other.data)) return false
    if (sampleRate != other.sampleRate) return false
    if (timestamp != other.timestamp) return false

    return true
  }

  override fun hashCode(): Int {
    var result = data.contentHashCode()
    result = 31 * result + sampleRate
    result = 31 * result + timestamp.hashCode()
    return result
  }
}