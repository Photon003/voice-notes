// Bridge between the Kotlin app and the whisper.cpp speech engine.
#include <jni.h>
#include <string>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "VoiceNotes"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static size_t asset_read(void *ctx, void *out, size_t n) {
    int r = AAsset_read(static_cast<AAsset *>(ctx), out, n);
    return r < 0 ? 0 : static_cast<size_t>(r);
}
static bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}
static void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_photon_voicenotes_Whisper_nativeInit(JNIEnv *env, jclass, jobject assetManager, jstring path) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    const char *p = env->GetStringUTFChars(path, nullptr);
    AAsset *asset = AAssetManager_open(mgr, p, AASSET_MODE_STREAMING);
    LOGI("Opening model %s -> %p", p, asset);
    env->ReleaseStringUTFChars(path, p);
    if (!asset) return 0;

    whisper_model_loader loader{};
    loader.context = asset;
    loader.read = asset_read;
    loader.eof = asset_eof;
    loader.close = asset_close;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    return reinterpret_cast<jlong>(whisper_init_with_params(&loader, cparams));
}

extern "C" JNIEXPORT void JNICALL
Java_com_photon_voicenotes_Whisper_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

// Returns the text as UTF-8 bytes (safe for Bengali / Hindi / any script).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_photon_voicenotes_Whisper_nativeTranscribe(JNIEnv *env, jclass, jlong ctxPtr,
                                                    jfloatArray audio, jstring lang, jint threads,
                                                    jboolean fast) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);

    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    const jsize n = env->GetArrayLength(audio);

    whisper_full_params params;
    if (fast) {
        // Live preview: quick and rough. Only look at as much audio as we have
        // (audio_ctx 1500 = 30 s), single pass, no retries.
        params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        int actx = n / 320 + 32;
        if (actx < 128) actx = 128;
        if (actx > 1500) actx = 1500;
        params.audio_ctx = actx;
        params.single_segment = true;
        params.no_timestamps = true;
        params.temperature_inc = 0.0f;
    } else {
        // Final pass: careful, same quality as the whisper-cli default.
        params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
        params.beam_search.beam_size = 5;
    }
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language.c_str();   // "auto", "en", "bn", "hi"
    params.detect_language = false;
    params.n_threads = threads;
    params.no_context = true;

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    std::string text;
    if (rc == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGI("whisper_full failed: %d", rc);
    }
    const size_t start = text.find_first_not_of(' ');
    text = (start == std::string::npos) ? "" : text.substr(start);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_photon_voicenotes_Whisper_nativeDetectedLanguage(JNIEnv *env, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    const char *code = whisper_lang_str(whisper_full_lang_id(ctx));
    return env->NewStringUTF(code ? code : "");
}
