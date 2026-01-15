package expo.modules.audiorecorder

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.log10

class AudioRecorderService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventEmitter: (String, Map<String, Any?>) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var recordingJob: Job? = null
    private var currentFilePath: String? = null
    private var recordingStartTime: Long = 0
    private var pausedDuration: Long = 0
    private var pauseStartTime: Long = 0

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var config: RecordingConfig? = null
    private var audioTrackIndex = -1
    private var muxerStarted = false

    // Audio chunk streaming
    private val _audioChunks = MutableSharedFlow<AudioChunk>(replay = 0)
    private val audioChunks = _audioChunks.asSharedFlow()

    // Downsampler for 44.1kHz -> 16kHz
    private val chunkSampleRate = 16000
    private var chunkBuffer = mutableListOf<Short>()

    // Noise level calculation
    private var currentNoiseLevel = -160.0 // dB
    private val noiseLevelSmoothingFactor = 0.3 // Smoothing for noise level updates

    // Audio focus and phone state management
    private var audioFocusManager: AudioFocusManager? = null
    private var phoneStateManager: PhoneStateManager? = null
    private var wasAutoPaused = false // Паузилась ли запись автоматически

    companion object {
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10000L
    }

    fun startRecording(recordingConfig: RecordingConfig): String {
        if (isRecording.get()) {
            throw IllegalStateException("Recording already in progress")
        }

        config = recordingConfig

        // Initialize audio focus and phone state managers
        initializeInterruptionHandlers()

        // Request audio focus
        val hasFocus = audioFocusManager?.requestAudioFocus() ?: false
        if (!hasFocus) {
            Log.w("AudioRecorder", "Could not obtain audio focus, but continuing...")
        }

        // Start phone state monitoring
        phoneStateManager?.startListening()

        // Create file in documents directory
        val outputFile = createOutputFile()
        currentFilePath = outputFile.absolutePath

        // Reset timing
        recordingStartTime = System.currentTimeMillis()
        pausedDuration = 0
        pauseStartTime = 0
        wasAutoPaused = false

        // Initialize audio components
        initializeAudioRecord(recordingConfig)
        initializeMediaCodec(recordingConfig)
        initializeMediaMuxer(outputFile)

        // Start foreground service for background recording
        startForegroundService()

        // Start recording
        isRecording.set(true)
        isPaused.set(false)

        audioRecord?.startRecording()

        // Start recording job
        recordingJob = scope.launch(Dispatchers.IO) {
            recordAudio()
        }

        // Emit state change
        emitStateChange()

        return currentFilePath!!
    }

    fun stopRecording(): RecordingResult {
        if (!isRecording.get()) {
            throw IllegalStateException("No active recording")
        }

        isRecording.set(false)
        isPaused.set(false)

        // Stop monitoring
        phoneStateManager?.stopListening()
        audioFocusManager?.abandonAudioFocus()

        // Wait for recording job to finish
        recordingJob?.let {
            if (it.isActive) {
                // Will be cancelled and cleaned up in recordAudio()
            }
        }

        val duration = calculateDuration()
        val filePath = currentFilePath ?: throw IllegalStateException("No file path")
        val fileSize = File(filePath).length()

        // Cleanup
        cleanup()

        // Stop foreground service
        stopForegroundService()

        // Emit state change
        emitStateChange()

        return RecordingResult(
            filePath = filePath,
            duration = duration,
            fileSize = fileSize
        )
    }

    fun pauseRecording() {
        if (!isRecording.get() || isPaused.get()) {
            throw IllegalStateException("Cannot pause: not recording or already paused")
        }

        isPaused.set(true)
        pauseStartTime = System.currentTimeMillis()

        // Update foreground service notification
        updateForegroundServiceNotification("pause")

        emitStateChange()
    }

    fun resumeRecording() {
        if (!isRecording.get() || !isPaused.get()) {
            throw IllegalStateException("Cannot resume: not recording or not paused")
        }

        pausedDuration += System.currentTimeMillis() - pauseStartTime
        pauseStartTime = 0
        isPaused.set(false)

        // Update foreground service notification
        updateForegroundServiceNotification("resume")

        emitStateChange()
    }

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

    fun release() {
        if (isRecording.get()) {
            stopRecording()
        }
        cleanup()
    }

    private fun createOutputFile(): File {
        val documentsDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("Cannot access documents directory")

        val recordingsDir = File(documentsDir, "Recordings")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        val timestamp = System.currentTimeMillis()
        return File(recordingsDir, "recording_$timestamp.m4a")
    }

    private fun initializeAudioRecord(config: RecordingConfig) {
        val channelConfig = if (config.channels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }

        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelConfig,
            audioFormat
        )

        val bufferSize = minBufferSize * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }
    }

    private fun initializeMediaCodec(config: RecordingConfig) {
        val format = MediaFormat.createAudioFormat(
            MIME_TYPE,
            config.sampleRate,
            config.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun initializeMediaMuxer(outputFile: File) {
        mediaMuxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
    }

    private suspend fun recordAudio() = withContext(Dispatchers.IO) {
        val bufferSize = 4096
        val buffer = ShortArray(bufferSize)
        var presentationTimeUs = 0L

        try {
            while (isRecording.get()) {
                if (isPaused.get()) {
                    Thread.sleep(100)
                    continue
                }

                // Read audio data
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                if (readSize > 0) {
                    // Calculate noise level (RMS to dB)
                    calculateNoiseLevel(buffer, readSize)

                    // Process for file (full quality)
                    encodeAudioData(buffer, readSize, presentationTimeUs)

                    // Process for chunks (downsampled)
                    if (config?.enableChunking == true) {
                        processChunk(buffer, readSize)
                    }

                    // Update presentation time
                    val durationUs = (readSize * 1_000_000L) / (config?.sampleRate ?: 44100)
                    presentationTimeUs += durationUs
                }
            }

            // Flush encoder
            flushEncoder()

        } catch (e: Exception) {
            emitError("RECORDING_ERROR", e.message ?: "Unknown error")
        }
    }

    private fun encodeAudioData(buffer: ShortArray, size: Int, presentationTimeUs: Long) {
        try {
            val codec = mediaCodec ?: return

            // Get input buffer
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    codec.getInputBuffer(inputBufferIndex)
                } else {
                    @Suppress("DEPRECATION")
                    codec.inputBuffers[inputBufferIndex]
                }

                inputBuffer?.clear()

                // Convert short array to byte buffer
                for (i in 0 until size) {
                    inputBuffer?.putShort(buffer[i])
                }

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    size * 2, // 2 bytes per short
                    presentationTimeUs,
                    0
                )
            }

            // Get output buffer
            drainEncoder(false)

        } catch (e: Exception) {
            // Log error but continue recording
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IllegalStateException("Format changed twice")
                    }

                    val newFormat = codec.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputBufferIndex < 0 -> {
                    // Ignore
                }
                else -> {
                    val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        codec.getOutputBuffer(outputBufferIndex)
                    } else {
                        @Suppress("DEPRECATION")
                        codec.outputBuffers[outputBufferIndex]
                    }

                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun flushEncoder() {
        try {
            val codec = mediaCodec ?: return

            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            drainEncoder(true)

        } catch (e: Exception) {
            // Log error
        }
    }

    private fun processChunk(buffer: ShortArray, size: Int) {
        // Add to chunk buffer
        for (i in 0 until size) {
            chunkBuffer.add(buffer[i])
        }

        // Check if we have enough data for a chunk
        val chunkDurationSamples = (config!!.sampleRate * config!!.chunkDuration) / 1000

        if (chunkBuffer.size >= chunkDurationSamples) {
            // Downsample and emit chunk
            val downsampledData = downsample(
                chunkBuffer.toShortArray(),
                config!!.sampleRate,
                chunkSampleRate
            )

            scope.launch {
                _audioChunks.emit(
                    AudioChunk(
                        data = downsampledData,
                        sampleRate = chunkSampleRate,
                        timestamp = System.currentTimeMillis()
                    )
                )

                // Emit to React Native
                emitAudioChunk(downsampledData)
            }

            // Clear processed samples
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
                // Normalize to -1.0 to 1.0
                output[i] = data[srcIndex] / 32768.0f
            }
        }

        return output
    }

    private fun calculateDuration(): Double {
        if (recordingStartTime == 0L) return 0.0

        val currentTime = if (isPaused.get()) {
            pauseStartTime
        } else {
            System.currentTimeMillis()
        }

        val totalTime = currentTime - recordingStartTime - pausedDuration
        return totalTime / 1000.0
    }

    private fun calculateNoiseLevel(buffer: ShortArray, size: Int) {
        // Calculate RMS (Root Mean Square)
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble() / 32768.0 // Normalize to -1.0 to 1.0
            sum += sample * sample
        }
        val rms = sqrt(sum / size)

        // Convert RMS to dB
        // Reference: 0 dBFS for full scale
        val db = if (rms > 0) {
            20 * log10(rms)
        } else {
            -160.0 // Minimum representable value
        }

        // Smooth the noise level to avoid rapid fluctuations
        currentNoiseLevel = if (currentNoiseLevel == -160.0) {
            db
        } else {
            currentNoiseLevel * (1 - noiseLevelSmoothingFactor) + db * noiseLevelSmoothingFactor
        }
    }

    private fun initializeInterruptionHandlers() {
        // Audio focus manager
        audioFocusManager = AudioFocusManager(
            context = context,
            onFocusLost = {
                // Автоматическая пауза при потере фокуса
                if (isRecording.get() && !isPaused.get()) {
                    Log.w("AudioRecorder", "Audio focus lost - auto pausing")
                    try {
                        pauseRecording()
                        wasAutoPaused = true

                        // Эмитим событие об автоматической паузе
                        eventEmitter(
                            "onRecordingError",
                            mapOf(
                                "code" to "AUDIO_FOCUS_LOST",
                                "message" to "Recording paused due to audio focus loss (call, notification, etc)"
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AudioRecorder", "Failed to auto-pause", e)
                    }
                }
            },
            onFocusGained = {
                // Не возобновляем автоматически - пусть пользователь решает
                Log.i("AudioRecorder", "Audio focus regained")

                if (wasAutoPaused) {
                    eventEmitter(
                        "onRecordingError",
                        mapOf(
                            "code" to "AUDIO_FOCUS_GAINED",
                            "message" to "Audio focus regained - you can resume recording"
                        )
                    )
                }
            }
        )

        // Phone state manager
        phoneStateManager = PhoneStateManager(
            context = context,
            onCallStarted = {
                // Автоматическая пауза при звонке
                if (isRecording.get() && !isPaused.get()) {
                    Log.w("AudioRecorder", "Phone call started - auto pausing")
                    try {
                        pauseRecording()
                        wasAutoPaused = true

                        eventEmitter(
                            "onRecordingError",
                            mapOf(
                                "code" to "PHONE_CALL_STARTED",
                                "message" to "Recording paused due to incoming/outgoing call"
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AudioRecorder", "Failed to auto-pause on call", e)
                    }
                }
            },
            onCallEnded = {
                // Уведомляем что можно возобновить
                Log.i("AudioRecorder", "Phone call ended")

                if (wasAutoPaused) {
                    eventEmitter(
                        "onRecordingError",
                        mapOf(
                            "code" to "PHONE_CALL_ENDED",
                            "message" to "Call ended - you can resume recording"
                        )
                    )
                }
            }
        )
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START_RECORDING
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Log error but continue recording
            Log.e("AudioRecorder", "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP_RECORDING
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Log error
            Log.e("AudioRecorder", "Failed to stop foreground service", e)
        }
    }

    private fun updateForegroundServiceNotification(action: String) {
        try {
            val serviceAction = when (action) {
                "pause" -> RecordingService.ACTION_PAUSE_RECORDING
                "resume" -> RecordingService.ACTION_RESUME_RECORDING
                else -> return
            }

            val intent = Intent(context, RecordingService::class.java).apply {
                this.action = serviceAction
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Log error
            Log.e("AudioRecorder", "Failed to update notification", e)
        }
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null

            muxerStarted = false
            audioTrackIndex = -1
            currentFilePath = null
            chunkBuffer.clear()
            currentNoiseLevel = -160.0

        } catch (e: Exception) {
            // Log error
        }
    }

    private fun emitStateChange() {
        val status = getStatus()
        eventEmitter(
            "onRecordingStateChanged",
            mapOf(
                "state" to status.state,
                "filePath" to status.filePath,
                "duration" to status.duration,
                "isRecording" to status.isRecording,
                "isPaused" to status.isPaused,
                "noiseLevel" to status.noiseLevel
            )
        )
    }

    private fun emitAudioChunk(data: FloatArray) {
        eventEmitter(
            "onAudioChunk",
            mapOf(
                "data" to data.toList(),
                "sampleRate" to chunkSampleRate,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun emitError(code: String, message: String) {
        eventEmitter(
            "onRecordingError",
            mapOf(
                "code" to code,
                "message" to message
            )
        )
    }
}