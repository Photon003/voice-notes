package com.photon.voicenotes

import android.content.res.AssetManager

/** Thin wrapper around the offline whisper.cpp speech engine. */
class Whisper private constructor(private var ctx: Long) {

    class Result(val text: String, val language: String)

    fun transcribe(samples: FloatArray, language: String, threads: Int): Result {
        val bytes = nativeTranscribe(ctx, samples, language, threads)
        val text = String(bytes, Charsets.UTF_8).trim()
        return Result(text, nativeDetectedLanguage(ctx))
    }

    fun release() {
        if (ctx != 0L) nativeFree(ctx)
        ctx = 0L
    }

    companion object {
        init {
            System.loadLibrary("voicenotes")
        }

        /** Loads the model packed inside the app. Returns null if it fails. */
        fun load(assets: AssetManager, path: String): Whisper? {
            val ctx = nativeInit(assets, path)
            return if (ctx == 0L) null else Whisper(ctx)
        }

        @JvmStatic private external fun nativeInit(assets: AssetManager, path: String): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(
            ctx: Long, samples: FloatArray, language: String, threads: Int
        ): ByteArray
        @JvmStatic private external fun nativeDetectedLanguage(ctx: Long): String
    }
}
