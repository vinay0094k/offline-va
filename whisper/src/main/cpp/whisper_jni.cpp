#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_codvika_whisper_WhisperBridge_initContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (!ctx) LOGE("failed to load whisper model from %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_codvika_whisper_WhisperBridge_transcribe(JNIEnv *env, jobject, jlong ctxPtr,
                                                  jfloatArray samples, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm((size_t) n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = nThreads;
    params.translate = false;
    params.language = "en";
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    std::string out;
    int rc = whisper_full(ctx, params, pcm.data(), (int) pcm.size());
    if (rc == 0) {
        int n_seg = whisper_full_n_segments(ctx);
        for (int i = 0; i < n_seg; ++i) {
            out += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        LOGE("whisper_full failed: %d", rc);
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_codvika_whisper_WhisperBridge_freeContext(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr) whisper_free(reinterpret_cast<whisper_context *>(ctxPtr));
}
