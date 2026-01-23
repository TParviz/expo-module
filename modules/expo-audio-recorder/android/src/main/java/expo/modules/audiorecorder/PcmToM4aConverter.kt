package expo.modules.audiorecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Конвертер PCM → M4A (AAC)
 * 
 * Читает сырые PCM данные и кодирует в AAC с правильным контейнером M4A.
 * Используется как при обычном stopRecording(), так и при recovery.
 */
object PcmToM4aConverter {

    private const val TAG = "PcmToM4aConverter"
    private const val MIME_TYPE = "audio/mp4a-latm"
    private const val TIMEOUT_US = 10000L
    private const val SAMPLES_PER_FRAME = 1024 // AAC frame size

    /**
     * Конвертировать PCM файл в M4A
     * 
     * @param pcmFile Входной файл с сырыми PCM данными (16-bit, little-endian)
     * @param outputFile Выходной M4A файл
     * @param sampleRate Sample rate (например, 44100)
     * @param channels Количество каналов (1 = mono, 2 = stereo)
     * @param bitRate Битрейт AAC (например, 128000)
     * @return true если конвертация успешна
     */
    fun convert(
        pcmFile: File,
        outputFile: File,
        sampleRate: Int,
        channels: Int,
        bitRate: Int
    ): Boolean {
        Log.d(TAG, "Converting PCM to M4A:")
        Log.d(TAG, "  Input: ${pcmFile.absolutePath} (${pcmFile.length()} bytes)")
        Log.d(TAG, "  Output: ${outputFile.absolutePath}")
        Log.d(TAG, "  Config: ${sampleRate}Hz, ${channels}ch, ${bitRate}bps")

        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            Log.e(TAG, "PCM file doesn't exist or is empty")
            return false
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputStream: FileInputStream? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            // Создаём формат для AAC encoder
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_FRAME * channels * 2 * 2)
            }

            // Создаём encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Создаём muxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Открываем PCM файл
            inputStream = FileInputStream(pcmFile)

            // Буферы
            val pcmBufferSize = SAMPLES_PER_FRAME * channels * 2 // bytes
            val pcmBuffer = ByteArray(pcmBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var presentationTimeUs: Long = 0
            val frameDurationUs = (SAMPLES_PER_FRAME * 1_000_000L) / sampleRate
            var inputDone = false
            var outputDone = false
            var totalFrames = 0

            while (!outputDone) {
                // Подаём данные на вход encoder
                if (!inputDone) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()

                        val bytesRead = inputStream.read(pcmBuffer)

                        if (bytesRead <= 0) {
                            // Конец файла - отправляем EOS
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                            Log.d(TAG, "Input done, sent EOS")
                        } else {
                            inputBuffer.put(pcmBuffer, 0, bytesRead)
                            encoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                bytesRead,
                                presentationTimeUs,
                                0
                            )
                            presentationTimeUs += frameDurationUs
                        }
                    }
                }

                // Получаем закодированные данные
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

                when {
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Нет данных, продолжаем
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Получили формат - добавляем трек в muxer
                        if (muxerStarted) {
                            Log.w(TAG, "Format changed after muxer started")
                        } else {
                            val outputFormat = encoder.outputFormat
                            trackIndex = muxer.addTrack(outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer started, track index: $trackIndex")
                        }
                    }

                    outputBufferIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)!!

                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            totalFrames++
                        }

                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            Log.d(TAG, "Output done")
                        }
                    }
                }
            }

            Log.i(TAG, "Conversion complete: $totalFrames frames written")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            return false

        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing input stream", e)
            }

            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing encoder", e)
            }

            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }
        }
    }

    /**
     * Получить длительность PCM файла в секундах
     */
    fun getPcmDuration(pcmFile: File, sampleRate: Int, channels: Int): Double {
        if (!pcmFile.exists()) return 0.0
        val bytes = pcmFile.length()
        val samples = bytes / (channels * 2) // 16-bit = 2 bytes per sample
        return samples.toDouble() / sampleRate
    }
}