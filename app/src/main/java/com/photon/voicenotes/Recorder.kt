package com.photon.voicenotes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records the microphone as 16 kHz mono audio, the format whisper expects.
 * The audio can be read while recording is still going (for live mode).
 */
class Recorder {

    private val lock = Any()
    private var buffer = ShortArray(RATE * 60)
    @Volatile var sampleCount = 0
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by MainActivity before calling
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, RATE * 2)
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        synchronized(lock) { sampleCount = 0 }
        running = true
        record.startRecording()

        thread = Thread {
            val chunk = ShortArray(RATE / 10)
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(lock) {
                    if (sampleCount + n > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                    System.arraycopy(chunk, 0, buffer, sampleCount, n)
                    sampleCount += n
                }
            }
            record.stop()
            record.release()
        }.also { it.start() }
        return true
    }

    /** Copy of samples [from, to) as floats in -1..1, safe to call while recording. */
    fun read(from: Int, to: Int): FloatArray = synchronized(lock) {
        val end = minOf(to, sampleCount)
        val start = minOf(maxOf(0, from), end)
        FloatArray(end - start) { buffer[start + it] / 32768f }
    }

    /** Stops recording and returns the whole recording. */
    fun stop(): FloatArray {
        running = false
        thread?.join()
        thread = null
        return read(0, sampleCount)
    }

    companion object {
        const val RATE = 16000
    }
}
