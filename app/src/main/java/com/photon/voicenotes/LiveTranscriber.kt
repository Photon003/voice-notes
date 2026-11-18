package com.photon.voicenotes

import kotlin.math.sqrt

/**
 * Live preview while recording.
 *
 * Every step re-reads the current "chunk" of speech (a few seconds) and shows a
 * quick rough transcription. When the chunk gets long and the speaker pauses,
 * that chunk is frozen ("committed") and a new one starts, so each pass stays
 * short and fast. After recording stops, a careful final pass replaces all of it.
 *
 * step() must always run on the same single background thread.
 */
class LiveTranscriber(
    private val models: Models,
    private val recorder: Recorder,
    private val threads: Int,
    private val requestedLang: String,
    private val onText: (String) -> Unit,
) {
    @Volatile var active = true

    private val committed = StringBuilder()
    private var chunkStart = 0
    private var lang = requestedLang

    fun step() {
        if (!active) return
        val end = recorder.sampleCount
        val len = end - chunkStart
        if (len < RATE) return // wait for at least 1 second of new speech

        var cut = -1
        if (len >= COMMIT_AFTER) {
            val audio = recorder.read(chunkStart, end)
            cut = findPause(audio, MIN_CHUNK)
            if (cut < 0 && len >= FORCE_COMMIT) cut = audio.size
        }

        if (cut > 0) {
            val audio = recorder.read(chunkStart, chunkStart + cut)
            val text = transcribe(audio)
            if (text.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
            }
            chunkStart += cut
            if (active) onText(committed.toString())
        } else {
            val audio = recorder.read(chunkStart, end)
            val text = transcribe(audio)
            if (active) onText(join(committed.toString(), text))
        }
    }

    private fun transcribe(audio: FloatArray): String {
        if (isSilent(audio)) return "" // avoids made-up words on silence
        val w = models.forLanguage(lang) ?: return ""
        val r = w.transcribe(audio, lang, threads, fast = true)
        // In Auto mode, stick to the first detected language so the preview doesn't flip.
        if (requestedLang == "auto" && lang == "auto" && audio.size >= 3 * RATE && r.language.isNotEmpty()) {
            lang = r.language
        }
        return r.text
    }

    private fun join(a: String, b: String) = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a $b"
    }

    private fun frameRms(audio: FloatArray): FloatArray {
        val frames = audio.size / FRAME
        return FloatArray(frames) { f ->
            var sum = 0f
            for (i in f * FRAME until (f + 1) * FRAME) sum += audio[i] * audio[i]
            sqrt(sum / FRAME)
        }
    }

    private fun isSilent(audio: FloatArray): Boolean {
        val rms = frameRms(audio)
        return rms.isEmpty() || rms.max() < SILENCE_FLOOR * 2
    }

    /** Latest pause (≥ 0.4 s of quiet) after [from] samples; returns a cut point or -1. */
    private fun findPause(audio: FloatArray, from: Int): Int {
        val rms = frameRms(audio)
        if (rms.isEmpty()) return -1
        val threshold = maxOf(SILENCE_FLOOR, 0.3f * rms.average().toFloat())
        val firstFrame = from / FRAME
        var run = 0
        for (f in rms.indices.reversed()) {
            if (f < firstFrame) break
            if (rms[f] < threshold) {
                run++
                if (run >= PAUSE_FRAMES) return (f + run / 2) * FRAME
            } else {
                run = 0
            }
        }
        return -1
    }

    companion object {
        private const val RATE = Recorder.RATE
        private const val FRAME = RATE / 50               // 20 ms
        private const val PAUSE_FRAMES = 20                // 0.4 s of quiet
        private const val MIN_CHUNK = 5 * RATE             // never cut before 5 s
        private const val COMMIT_AFTER = 8 * RATE          // look for a pause after 8 s
        private const val FORCE_COMMIT = 15 * RATE         // cut anyway at 15 s
        private const val SILENCE_FLOOR = 0.004f
    }
}
