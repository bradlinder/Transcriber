#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_nativeInitContext(
        JNIEnv *env,
        jclass /* clazz */,
        jstring modelPathStr) {

    const char *model_path = env->GetStringUTFChars(modelPathStr, nullptr);
    LOGI("Initializing official whisper.cpp v1.6.0 context with model: %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(modelPathStr, model_path);

    if (!ctx) {
        LOGE("Failed to initialize whisper_context!");
        return 0;
    }

    LOGI("Official whisper.cpp v1.6.0 context initialized successfully at %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_00024Companion_nativeInitContext(
        JNIEnv *env,
        jclass clazz,
        jstring modelPathStr) {
    return Java_com_rtvs_mobile_engine_WhisperContext_nativeInitContext(env, clazz, modelPathStr);
}

JNIEXPORT void JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_nativeFreeContext(
        JNIEnv *env,
        jclass /* clazz */,
        jlong contextPtr) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Whisper context freed.");
    }
}

JNIEXPORT void JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_00024Companion_nativeFreeContext(
        JNIEnv *env,
        jclass clazz,
        jlong contextPtr) {
    Java_com_rtvs_mobile_engine_WhisperContext_nativeFreeContext(env, clazz, contextPtr);
}

JNIEXPORT jstring JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_nativeTranscribe(
        JNIEnv *env,
        jclass /* clazz */,
        jlong contextPtr,
        jfloatArray samplesArray,
        jint numThreads) {

    auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
    if (!ctx) {
        return env->NewStringUTF("");
    }

    jsize sampleCount = env->GetArrayLength(samplesArray);
    jfloat *samples = env->GetFloatArrayElements(samplesArray, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = numThreads > 0 ? numThreads : 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    LOGI("Running official whisper_full with %d samples...", sampleCount);
    int res = whisper_full(ctx, params, samples, sampleCount);

    env->ReleaseFloatArrayElements(samplesArray, samples, 0);

    if (res != 0) {
        LOGE("whisper_full failed with code %d", res);
        return env->NewStringUTF("");
    }

    std::string resultText = "";
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            resultText += text;
            resultText += " ";
        }
    }

    LOGI("Official whisper.cpp v1.6.0 result: %s", resultText.c_str());
    return env->NewStringUTF(resultText.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_rtvs_mobile_engine_WhisperContext_00024Companion_nativeTranscribe(
        JNIEnv *env,
        jclass clazz,
        jlong contextPtr,
        jfloatArray samplesArray,
        jint numThreads) {
    return Java_com_rtvs_mobile_engine_WhisperContext_nativeTranscribe(env, clazz, contextPtr, samplesArray, numThreads);
}

} // extern "C"
