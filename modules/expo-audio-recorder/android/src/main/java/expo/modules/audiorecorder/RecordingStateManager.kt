package expo.modules.audiorecorder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Менеджер состояния записи
 * 
 * Сохраняет состояние в SharedPreferences для recovery после force-kill.
 * Хранит путь к PCM файлу и все параметры для конвертации.
 */
class RecordingStateManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingStateManager"
        private const val PREFS_NAME = "AudioRecorderCoreState"
        
        // Ключи
        private const val KEY_IS_RECORDING = "is_recording"
        private const val KEY_RECORDING_ID = "recording_id"
        private const val KEY_PCM_FILE_PATH = "pcm_file_path"
        private const val KEY_OUTPUT_FILE_PATH = "output_file_path"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_PAUSED_DURATION = "paused_duration"
        private const val KEY_IS_PAUSED = "is_paused"
        private const val KEY_PAUSE_START_TIME = "pause_start_time"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_BIT_RATE = "bit_rate"
        private const val KEY_TOTAL_SAMPLES = "total_samples"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_MAX_DURATION = "max_duration"
    }

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Сохранить состояние записи
     */
    fun saveState(state: RecordingState) {
        prefs.edit().apply {
            putBoolean(KEY_IS_RECORDING, true)
            putString(KEY_RECORDING_ID, state.recordingId)
            putString(KEY_PCM_FILE_PATH, state.pcmFilePath)
            putString(KEY_OUTPUT_FILE_PATH, state.outputFilePath)
            putLong(KEY_START_TIME, state.startTime)
            putLong(KEY_PAUSED_DURATION, state.pausedDuration)
            putBoolean(KEY_IS_PAUSED, state.isPaused)
            putLong(KEY_PAUSE_START_TIME, state.pauseStartTime)
            putInt(KEY_SAMPLE_RATE, state.sampleRate)
            putInt(KEY_CHANNELS, state.channels)
            putInt(KEY_BIT_RATE, state.bitRate)
            putLong(KEY_TOTAL_SAMPLES, state.totalSamplesWritten)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            putLong(KEY_MAX_DURATION, state.pausedDuration)
            apply()
        }
        
        Log.d(TAG, "State saved: ${state.recordingId}, samples=${state.totalSamplesWritten}")
    }

    /**
     * Получить сохранённое состояние
     */
    fun getState(): RecordingState? {
        val isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        if (!isRecording) {
            return null
        }

        val pcmFilePath = prefs.getString(KEY_PCM_FILE_PATH, null) ?: return null
        
        // Проверяем что PCM файл существует
        val pcmFile = File(pcmFilePath)
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            Log.w(TAG, "PCM file not found or empty: $pcmFilePath")
            clearState()
            return null
        }

        return RecordingState(
            recordingId = prefs.getString(KEY_RECORDING_ID, "") ?: "",
            pcmFilePath = pcmFilePath,
            outputFilePath = prefs.getString(KEY_OUTPUT_FILE_PATH, "") ?: "",
            startTime = prefs.getLong(KEY_START_TIME, 0),
            pausedDuration = prefs.getLong(KEY_PAUSED_DURATION, 0),
            isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
            pauseStartTime = prefs.getLong(KEY_PAUSE_START_TIME, 0),
            sampleRate = prefs.getInt(KEY_SAMPLE_RATE, 44100),
            channels = prefs.getInt(KEY_CHANNELS, 1),
            bitRate = prefs.getInt(KEY_BIT_RATE, 128000),
            totalSamplesWritten = prefs.getLong(KEY_TOTAL_SAMPLES, 0),
            maxDuration = prefs.getLong(KEY_MAX_DURATION, 0),
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0),
        )
    }

    /**
     * Проверить есть ли незавершённая запись
     */
    fun hasUnfinishedRecording(): Boolean {
        val isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        if (!isRecording) return false

        val pcmFilePath = prefs.getString(KEY_PCM_FILE_PATH, null) ?: return false
        val pcmFile = File(pcmFilePath)
        
        return pcmFile.exists() && pcmFile.length() > 0
    }

    /**
     * Очистить состояние
     */
    fun clearState() {
        prefs.edit().clear().apply()
        Log.d(TAG, "State cleared")
    }
}