package expo.modules.audiorecorder


import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Конвертер PCM → M4A
 * 
 * Использует MediaCodec для кодирования AAC и MediaMuxer для упаковки в M4A контейнер.
 */
class PcmToM4aConverter {
    companion object {
        private const val TAG = "PcmToM4aConverter"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10000L
    }

    /**
     * Конвертировать PCM файл в M4A
     */
    suspend fun convert(
        pcmFile: File,
        outputFile: File,
        sampleRate: Int,
        channels: Int,
        bitRate: Int
    ): Boolean = withContext(Dispatchers.IO) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputStream: FileInputStream? = null
        
        try {
            Log.d(TAG, "Starting conversion: ${pcmFile.absolutePath} -> ${outputFile.absolutePath}")
            Log.d(TAG, "Config: sampleRate=$sampleRate, channels=$channels, bitRate=$bitRate")

            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                Log.e(TAG, "PCM file doesn't exist or is empty")
                return@withContext false
            }

            // Настройка MediaFormat для AAC
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            }

            // Создаём кодек
            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // Создаём muxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            // Читаем PCM данные
            inputStream = FileInputStream(pcmFile)
            val pcmBuffer = ByteArray(4096)
            var isEOS = false
            var presentationTimeUs = 0L
            val bytesPerSample = 2 * channels // 16-bit PCM

            while (!isEOS) {
                // Подаём данные на вход кодека
                val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    inputBuffer.clear()
                    
                    val bytesRead = inputStream.read(pcmBuffer)
                    
                    if (bytesRead > 0) {
                        inputBuffer.put(pcmBuffer, 0, bytesRead)
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            bytesRead,
                            presentationTimeUs,
                            0
                        )
                        presentationTimeUs += (bytesRead.toLong() * 1_000_000L) / (sampleRate * bytesPerSample)
                    } else {
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isEOS = true
                    }
                }

                // Получаем закодированные данные
                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                
                while (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Config data, пропускаем
                        bufferInfo.size = 0
                    }
                    
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            val outputFormat = codec.outputFormat
                            trackIndex = muxer.addTrack(outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer started, trackIndex=$trackIndex")
                        }
                        
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                    
                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
            }

            Log.i(TAG, "Conversion completed successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            false
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {}
            
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {}
        }
    }
}