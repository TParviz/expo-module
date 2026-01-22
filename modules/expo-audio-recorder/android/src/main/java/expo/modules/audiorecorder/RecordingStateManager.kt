package expo.modules.audiorecorder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Менеджер состояния записи для recovery
 * 
 * Сохраняет состояние в SharedPreferences для восстановления после crash/force-kill.
 */
class RecordingStateManager(private val context: Context) {
    companion object {
        private const val TAG = "RecordingStateManager"
        private const val PREFS_NAME = "AudioRecorderCoreState"
        
        private const val KEY_PCM_FILE_PATH = "pcm_file_path"
        private const val KEY_OUTPUT_FILE_PATH = "output_file_path"
        private const val KEY_START_TIME = "start_time"
        private const val KEY_SAMPLE_RATE = "sample_rate"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_BIT_RATE = "bit_rate"
        private const val KEY_IS_RECORDING = "is_recording"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Сохранить состояние записи
     */
    fun saveState(state: RecordingState) {
        prefs.edit().apply {
            putString(KEY_PCM_FILE_PATH, state.pcmFilePath)
            putString(KEY_OUTPUT_FILE_PATH, state.outputFilePath)
            putLong(KEY_START_TIME, state.startTime)
            putInt(KEY_SAMPLE_RATE, state.sampleRate)
            putInt(KEY_CHANNELS, state.channels)
            putInt(KEY_BIT_RATE, state.bitRate)
            putBoolean(KEY_IS_RECORDING, true)
            apply()
        }
        Log.d(TAG, "State saved: ${state.pcmFilePath}")
    }

    /**
     * Получить сохранённое состояние
     */
    fun getState(): RecordingState? {
        val isRecording = prefs.getBoolean(KEY_IS_RECORDING, false)
        if (!isRecording) return null

        val pcmFilePath = prefs.getString(KEY_PCM_FILE_PATH, null) ?: return null
        val outputFilePath = prefs.getString(KEY_OUTPUT_FILE_PATH, null) ?: return null

        return RecordingState(
            pcmFilePath = pcmFilePath,
            outputFilePath = outputFilePath,
            startTime = prefs.getLong(KEY_START_TIME, 0),
            sampleRate = prefs.getInt(KEY_SAMPLE_RATE, 44100),
            channels = prefs.getInt(KEY_CHANNELS, 1),
            bitRate = prefs.getInt(KEY_BIT_RATE, 128000)
        )
    }

    /**
     * Очистить состояние
     */
    fun clearState() {
        prefs.edit().clear().apply()
        Log.d(TAG, "State cleared")
    }

    /**
     * Проверить есть ли незавершённая запись
     */
    fun hasUnfinishedRecording(): Boolean {
        return prefs.getBoolean(KEY_IS_RECORDING, false)
    }
}