/* MIT-licensed product adapter. mGBA remains licensed under MPL-2.0. */
#include <jni.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/version.h>

#define VIDEO_WIDTH 240
#define VIDEO_HEIGHT 160
#define VIDEO_STRIDE 256
#define AUDIO_SAMPLE_RATE 48000
#define MAX_GBA_ROM_SIZE (32 * 1024 * 1024)

_Static_assert(sizeof(color_t) == 4, "Android adapter requires mGBA's 32-bit color format");

struct MgbaSession {
    struct mCore* core;
    void* romData;
    size_t romSize;
    color_t video[VIDEO_STRIDE * VIDEO_HEIGHT];
    bool loaded;
    int videoWidth;
    int videoHeight;
};

static struct MgbaSession* sessionFromHandle(jlong handle) {
    return (struct MgbaSession*) (uintptr_t) handle;
}

static void destroySession(struct MgbaSession* session) {
    if (!session) {
        return;
    }
    if (session->core) {
        mCoreConfigDeinit(&session->core->config);
        session->core->deinit(session->core);
    }
    free(session->romData);
    free(session);
}

JNIEXPORT jstring JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_version(JNIEnv* env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, projectVersion);
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbaCore(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;

    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core) {
        return JNI_FALSE;
    }
    if (!core->init(core)) {
        free(core);
        return JNI_FALSE;
    }

    core->deinit(core);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbCore(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    struct mCore* core = mCoreCreate(mPLATFORM_GB);
    if (!core) {
        return JNI_FALSE;
    }
    if (!core->init(core)) {
        free(core);
        return JNI_FALSE;
    }
    core->deinit(core);
    return JNI_TRUE;
}

static bool setupCore(struct MgbaSession* session, jint platform) {
    enum mPlatform mp = (platform == 1) ? mPLATFORM_GB : mPLATFORM_GBA;
    session->core = mCoreCreate(mp);
    if (!session->core || !session->core->init(session->core)) {
        if (session->core) {
            free(session->core);
            session->core = NULL;
        }
        return false;
    }

    mCoreInitConfig(session->core, NULL);
    struct mCoreOptions options = {
        .useBios = true,
        .fpsTarget = 60.0f,
        .sampleRate = AUDIO_SAMPLE_RATE,
        .volume = 0x100,
    };
    mCoreConfigLoadDefaults(&session->core->config, &options);
    // Super Game Boy borders are out of scope and, if left enabled, make the GB
    // core report a 256x224 SGB canvas (instead of 160x144) until a ROM is
    // loaded and borders are turned off. That 224-row canvas overflows the
    // fixed VIDEO_HEIGHT (160) video buffer, so disable borders up front.
    mCoreConfigSetIntValue(&session->core->config, "sgb.borders", 0);
    mCoreLoadConfig(session->core);

    session->core->setVideoBuffer(session->core, session->video, VIDEO_STRIDE);
    session->core->setAudioBufferSize(session->core, 2048);
    blip_set_rates(session->core->getAudioChannel(session->core, 0),
                   session->core->frequency(session->core), AUDIO_SAMPLE_RATE);
    blip_set_rates(session->core->getAudioChannel(session->core, 1),
                   session->core->frequency(session->core), AUDIO_SAMPLE_RATE);
    return true;
}

// Queries the core's post-load, post-reset video dimensions and rejects
// anything that would overflow the fixed video buffer. Must be called after
// loadROM()+reset() so the GB/GBC core has settled on its real dimensions
// (160x144 with SGB borders disabled) rather than the pre-load default.
static bool refreshDimensions(struct MgbaSession* session) {
    unsigned w = VIDEO_WIDTH;
    unsigned h = VIDEO_HEIGHT;
    session->core->desiredVideoDimensions(session->core, &w, &h);
    if ((int) w > VIDEO_STRIDE || (int) h > VIDEO_HEIGHT || w == 0 || h == 0) {
        return false;
    }
    session->videoWidth = (int) w;
    session->videoHeight = (int) h;
    return true;
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCreate(JNIEnv* env, jclass clazz, jint platform) {
    (void) env;
    (void) clazz;

    struct MgbaSession* session = calloc(1, sizeof(*session));
    if (!session) {
        return 0;
    }
    if (!setupCore(session, platform)) {
        free(session);
        return 0;
    }
    return (jlong) (uintptr_t) session;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadRom(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray rom) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->core || session->loaded || !rom) {
        return JNI_FALSE;
    }

    jsize romSize = (*env)->GetArrayLength(env, rom);
    if (romSize <= 0 || romSize > MAX_GBA_ROM_SIZE) {
        return JNI_FALSE;
    }

    void* romData = malloc((size_t) romSize);
    if (!romData) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, rom, 0, romSize, romData);
    if ((*env)->ExceptionCheck(env)) {
        free(romData);
        return JNI_FALSE;
    }

    struct VFile* romFile = VFileFromMemory(romData, (size_t) romSize);
    if (!romFile) {
        free(romData);
        return JNI_FALSE;
    }
    if (!session->core->loadROM(session->core, romFile)) {
        romFile->close(romFile);
        free(romData);
        return JNI_FALSE;
    }
    session->core->reset(session->core);
    if (!refreshDimensions(session)) {
        // Post-load dimensions would overflow the fixed video buffer; treat
        // this the same as a load failure rather than risk memory corruption.
        // The core already owns the VFile wrapping romData (loadROM()
        // succeeded), so we must NOT free it here or close that VFile
        // ourselves: doing so would leave the core holding a dangling
        // pointer, and a later nativeDestroy()->destroySession() would
        // touch freed memory when it deinits the core. Instead, retain the
        // memory in the session (loaded stays false) so destroySession()
        // deinits the core first (closing the VFile) and only then frees
        // session->romData, in the correct order.
        session->romData = romData;
        session->romSize = (size_t) romSize;
        return JNI_FALSE;
    }

    session->romData = romData;
    session->romSize = (size_t) romSize;
    session->loaded = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadRomFile(
        JNIEnv* env, jclass clazz, jlong handle, jstring path) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->core || session->loaded || !path) {
        return JNI_FALSE;
    }

    const char* filePath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!filePath) {
        return JNI_FALSE;
    }
    struct VFile* romFile = VFileOpen(filePath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, filePath);
    if (!romFile) {
        return JNI_FALSE;
    }

    off_t romSize = romFile->size(romFile);
    if (romSize <= 0 || romSize > MAX_GBA_ROM_SIZE) {
        romFile->close(romFile);
        return JNI_FALSE;
    }

    void* romData = malloc((size_t) romSize);
    if (!romData) {
        romFile->close(romFile);
        return JNI_FALSE;
    }
    size_t offset = 0;
    while (offset < (size_t) romSize) {
        ssize_t count = romFile->read(romFile, (uint8_t*) romData + offset,
                                      (size_t) romSize - offset);
        if (count <= 0) {
            romFile->close(romFile);
            free(romData);
            return JNI_FALSE;
        }
        offset += (size_t) count;
    }
    romFile->close(romFile);

    struct VFile* memoryFile = VFileFromMemory(romData, (size_t) romSize);
    if (!memoryFile || !session->core->loadROM(session->core, memoryFile)) {
        if (memoryFile) {
            memoryFile->close(memoryFile);
        }
        free(romData);
        return JNI_FALSE;
    }
    session->core->reset(session->core);
    if (!refreshDimensions(session)) {
        // Post-load dimensions would overflow the fixed video buffer; treat
        // this the same as a load failure rather than risk memory corruption.
        // The core already owns the VFile wrapping romData (loadROM()
        // succeeded), so we must NOT free it here or close that VFile
        // ourselves: doing so would leave the core holding a dangling
        // pointer, and a later nativeDestroy()->destroySession() would
        // touch freed memory when it deinits the core. Instead, retain the
        // memory in the session (loaded stays false) so destroySession()
        // deinits the core first (closing the VFile) and only then frees
        // session->romData, in the correct order.
        session->romData = romData;
        session->romSize = (size_t) romSize;
        return JNI_FALSE;
    }

    session->romData = romData;
    session->romSize = (size_t) romSize;
    session->loaded = true;
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRunFrame(
        JNIEnv* env, jclass clazz, jlong handle, jint keys, jintArray pixels, jshortArray audio) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded || !pixels || !audio) {
        return -1;
    }

    jsize pixelCapacity = (*env)->GetArrayLength(env, pixels);
    jsize audioCapacity = (*env)->GetArrayLength(env, audio);
    if (pixelCapacity < session->videoWidth * session->videoHeight || audioCapacity < 2) {
        return -1;
    }

    session->core->setKeys(session->core, (uint32_t) keys & 0x3FF);
    session->core->runFrame(session->core);

    jint* output = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!output) {
        return -1;
    }
    for (size_t y = 0; y < (size_t) session->videoHeight; ++y) {
        for (size_t x = 0; x < (size_t) session->videoWidth; ++x) {
            uint32_t native = session->video[y * VIDEO_STRIDE + x];
            output[y * session->videoWidth + x] = (jint) (0xFF000000U
                    | ((native & 0x000000FFU) << 16)
                    | (native & 0x0000FF00U)
                    | ((native & 0x00FF0000U) >> 16));
        }
    }
    (*env)->ReleaseIntArrayElements(env, pixels, output, 0);

    blip_t* left = session->core->getAudioChannel(session->core, 0);
    blip_t* right = session->core->getAudioChannel(session->core, 1);
    int available = blip_samples_avail(left);
    int frameCapacity = audioCapacity / 2;
    int framesToRead = available < frameCapacity ? available : frameCapacity;
    if (framesToRead <= 0) {
        return 0;
    }

    jshort* samples = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!samples) {
        return -1;
    }
    int produced = blip_read_samples(left, samples, framesToRead, true);
    int rightProduced = blip_read_samples(right, samples + 1, framesToRead, true);
    (*env)->ReleaseShortArrayElements(env, audio, samples, 0);
    return produced < rightProduced ? produced : rightProduced;
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeFrameCounter(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded) {
        return -1;
    }
    return (jlong) session->core->frameCounter(session->core);
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoWidth(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    return session ? session->videoWidth : 0;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoHeight(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    return session ? session->videoHeight : 0;
}

// Recolours the original Game Boy (DMG) output via mGBA's gb.pal[0..11] config
// (background, obj0, obj1 all use the same four shades). The four inputs are
// 0xRRGGBB (alpha ignored). No-op on GBA cores, which never read gb.pal; the
// caller only invokes this for DMG ROMs so GBC output is untouched.
JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSetDmgPalette(
        JNIEnv* env, jclass clazz, jlong handle, jint s0, jint s1, jint s2, jint s3) {
    (void) env;
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->core) {
        return;
    }
    jint shades[4] = { s0 & 0xFFFFFF, s1 & 0xFFFFFF, s2 & 0xFFFFFF, s3 & 0xFFFFFF };
    char key[16];
    for (int i = 0; i < 12; ++i) {
        snprintf(key, sizeof(key), "gb.pal[%d]", i);
        mCoreConfigSetIntValue(&session->core->config, key, shades[i % 4]);
    }
    mCoreLoadConfig(session->core);
}

JNIEXPORT jbyteArray JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSaveState(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded) {
        return NULL;
    }

    size_t stateSize = session->core->stateSize(session->core);
    void* state = malloc(stateSize);
    if (!state || !session->core->saveState(session->core, state)) {
        free(state);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) stateSize);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) stateSize, state);
    }
    free(state);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadState(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray state) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded || !state) {
        return JNI_FALSE;
    }

    jsize stateSize = (*env)->GetArrayLength(env, state);
    if ((size_t) stateSize != session->core->stateSize(session->core)) {
        return JNI_FALSE;
    }
    void* stateData = malloc((size_t) stateSize);
    if (!stateData) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, state, 0, stateSize, stateData);
    bool loaded = !(*env)->ExceptionCheck(env) && session->core->loadState(session->core, stateData);
    free(stateData);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeReset(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->core || !session->loaded) {
        return JNI_FALSE;
    }
    session->core->reset(session->core);
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCopySavedata(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded) {
        return NULL;
    }

    void* savedata = NULL;
    size_t size = session->core->savedataClone(session->core, &savedata);
    if (!size || !savedata) {
        return (*env)->NewByteArray(env, 0);
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, savedata);
    }
    free(savedata);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRestoreSavedata(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray data) {
    (void) clazz;
    struct MgbaSession* session = sessionFromHandle(handle);
    if (!session || !session->loaded || !data) {
        return JNI_FALSE;
    }

    jsize size = (*env)->GetArrayLength(env, data);
    if (size <= 0) {
        return JNI_FALSE;
    }
    void* savedata = malloc((size_t) size);
    if (!savedata) {
        return JNI_FALSE;
    }
    (*env)->GetByteArrayRegion(env, data, 0, size, savedata);
    bool restored = !(*env)->ExceptionCheck(env)
            && session->core->savedataRestore(session->core, savedata, (size_t) size, false);
    free(savedata);
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    destroySession(sessionFromHandle(handle));
}
