package com.photon.voicenotes

import android.content.res.AssetManager

/**
 * The two speech models packed in the app:
 *  - general multilingual model for Auto / English / Hindi
 *  - Bengali-trained model for বাংলা
 * Models load the first time they are needed. Only use from the worker thread.
 */
class Models(private val assets: AssetManager) {

    private val loaded = HashMap<String, Whisper?>()
    @Volatile private var ready = emptySet<String>()

    /** Safe to call from any thread. */
    fun isLoaded(lang: String) = pathFor(lang) in ready

    /** Model for a language code; falls back to the general model if needed. */
    fun forLanguage(lang: String): Whisper? {
        val path = pathFor(lang)
        val w = loaded.getOrPut(path) {
            Whisper.load(assets, path, noTimestamps = path == BENGALI).also { ready = ready + path }
        }
        return w ?: if (path != GENERAL) forLanguage("en") else null
    }

    fun releaseAll() {
        loaded.values.forEach { it?.release() }
        loaded.clear()
        ready = emptySet()
    }

    private fun pathFor(lang: String) = if (lang == "bn") BENGALI else GENERAL

    companion object {
        const val GENERAL = "models/ggml-base.bin"
        const val BENGALI = "models/ggml-small-bn-q8_0.bin"
    }
}
