/* MIT-licensed product adapter. mGBA remains licensed under MPL-2.0. */
#include "mgba_session.h"

#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>

#include <mgba/core/version.h>

#define MAX_ROM_SIZE (32 * 1024 * 1024)

static MgbaSession* session_from_handle(jlong handle) {
    return (MgbaSession*) (uintptr_t) handle;
}

JNIEXPORT jstring JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_version(JNIEnv* env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, projectVersion);
}

static jboolean can_create(MgbaPlatform platform) {
    MgbaSession* session = mgba_session_create(platform);
    if (!session) {
        return JNI_FALSE;
    }
    mgba_session_destroy(session);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbaCore(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return can_create(MGBA_PLATFORM_GBA);
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbCore(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return can_create(MGBA_PLATFORM_GB);
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCreate(
        JNIEnv* env, jclass clazz, jint platform) {
    (void) env;
    (void) clazz;
    MgbaPlatform native_platform = platform == 1 ? MGBA_PLATFORM_GB : MGBA_PLATFORM_GBA;
    return (jlong) (uintptr_t) mgba_session_create(native_platform);
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadRom(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray rom) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !rom) {
        return JNI_FALSE;
    }
    jsize size = (*env)->GetArrayLength(env, rom);
    if (size <= 0 || size > MAX_ROM_SIZE) {
        return JNI_FALSE;
    }
    void* data = malloc((size_t) size);
    if (!data) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, rom, 0, size, data);
    if ((*env)->ExceptionCheck(env)) {
        free(data);
        return JNI_FALSE;
    }
    return mgba_session_load_rom_owned(session, data, (size_t) size)
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadRomFile(
        JNIEnv* env, jclass clazz, jlong handle, jstring path) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !path) {
        return JNI_FALSE;
    }
    const char* native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (!native_path) {
        return JNI_FALSE;
    }
    bool loaded = mgba_session_load_rom_file(session, native_path);
    (*env)->ReleaseStringUTFChars(env, path, native_path);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRunFrame(
        JNIEnv* env, jclass clazz, jlong handle, jint keys,
        jintArray pixels, jshortArray audio) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !pixels || !audio) {
        return -1;
    }
    jsize pixel_capacity = (*env)->GetArrayLength(env, pixels);
    jsize audio_capacity = (*env)->GetArrayLength(env, audio);
    jint* pixel_data = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!pixel_data) {
        return -1;
    }
    jshort* audio_data = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!audio_data) {
        (*env)->ReleaseIntArrayElements(env, pixels, pixel_data, JNI_ABORT);
        return -1;
    }
    int produced = mgba_session_run_frame(session, (uint32_t) keys,
            (int32_t*) pixel_data, (size_t) pixel_capacity,
            (int16_t*) audio_data, (size_t) audio_capacity);
    jint release_mode = produced >= 0 ? 0 : JNI_ABORT;
    (*env)->ReleaseShortArrayElements(env, audio, audio_data, release_mode);
    (*env)->ReleaseIntArrayElements(env, pixels, pixel_data, release_mode);
    return produced;
}

JNIEXPORT jobject JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoBuffer(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    size_t byte_count = 0;
    const void* buffer = mgba_session_video_buffer(
            session_from_handle(handle), &byte_count);
    if (!buffer || !byte_count) {
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, (void*) buffer, (jlong) byte_count);
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRunFrameDirect(
        JNIEnv* env, jclass clazz, jlong handle, jint keys, jshortArray audio) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !audio) {
        return -1;
    }
    jsize audio_capacity = (*env)->GetArrayLength(env, audio);
    jshort* audio_data = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!audio_data) {
        return -1;
    }
    int produced = mgba_session_run_frame_direct(
            session, (uint32_t) keys, (int16_t*) audio_data,
            (size_t) audio_capacity);
    (*env)->ReleaseShortArrayElements(
            env, audio, audio_data, produced >= 0 ? 0 : JNI_ABORT);
    return produced;
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeFrameCounter(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    uint64_t counter = 0;
    return mgba_session_frame_counter(session_from_handle(handle), &counter)
            ? (jlong) counter : -1;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoWidth(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    return mgba_session_video_width(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoHeight(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    return mgba_session_video_height(session_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSetDmgPalette(
        JNIEnv* env, jclass clazz, jlong handle, jint s0, jint s1, jint s2, jint s3) {
    (void) env;
    (void) clazz;
    (void) mgba_session_set_dmg_palette(session_from_handle(handle),
            (uint32_t) s0, (uint32_t) s1, (uint32_t) s2, (uint32_t) s3);
}

JNIEXPORT jbyteArray JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSaveState(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    size_t size = mgba_session_state_size(session);
    if (!size || size > INT_MAX) {
        return NULL;
    }
    void* state = malloc(size);
    if (!state) {
        return NULL;
    }
    if (!mgba_session_save_state(session, state, size)) {
        free(state);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, state);
        if ((*env)->ExceptionCheck(env)) {
            result = NULL;
        }
    }
    free(state);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadState(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray state) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !state) {
        return JNI_FALSE;
    }
    jsize size = (*env)->GetArrayLength(env, state);
    if (size <= 0) {
        return JNI_FALSE;
    }
    void* data = malloc((size_t) size);
    if (!data) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, state, 0, size, data);
    bool loaded = !(*env)->ExceptionCheck(env)
            && mgba_session_load_state(session, data, (size_t) size);
    free(data);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeReset(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    return mgba_session_reset(session_from_handle(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCopySavedata(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    void* data = NULL;
    size_t size = 0;
    if (!mgba_session_copy_savedata(session_from_handle(handle), &data, &size)
            || size > INT_MAX) {
        free(data);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result && size) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, data);
        if ((*env)->ExceptionCheck(env)) {
            result = NULL;
        }
    }
    free(data);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRestoreSavedata(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray input) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !input) {
        return JNI_FALSE;
    }
    jsize size = (*env)->GetArrayLength(env, input);
    if (size <= 0) {
        return JNI_FALSE;
    }
    void* data = malloc((size_t) size);
    if (!data) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, input, 0, size, data);
    bool restored = !(*env)->ExceptionCheck(env)
            && mgba_session_restore_savedata(session, data, (size_t) size);
    free(data);
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    mgba_session_destroy(session_from_handle(handle));
}
