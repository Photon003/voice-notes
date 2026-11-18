package com.photon.voicenotes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records the microphone as 16 kHz mono audio, the format whisper expects. */
class Recorder {

    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false
    @Volatile var sampleCount = 0
        private set
    private var thread: Thread? = null

    val seconds: Float get() = sampleCount / RATE.toFloat()

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

        synchronized(chunks) { chunks.clear() }
        sampleCount = 0
        running = true
        record.startRecording()

        thread = Thread {
            val buf = ShortArray(RATE / 10)
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                    sampleCount += n
                }
            }
            record.stop()
            record.release()
        }.also { it.start() }
        return true
    }

    /** Stops recording and returns the audio as floats in -1..1. */
    fun stop(): FloatArray {
        running = false
        thread?.join()
        thread = null
        val out = FloatArray(sampleCount)
        var i = 0
        synchronized(chunks) {
            for (c in chunks) for (s in c) {
                if (i < out.size) out[i++] = s / 32768f
            }
            chunks.clear()
        }
        return out
    }

    companion object {
        const val RATE = 16000
    }
}
