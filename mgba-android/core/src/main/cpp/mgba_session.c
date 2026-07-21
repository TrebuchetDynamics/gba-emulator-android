#include "mgba_session.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>

#define VIDEO_WIDTH 240
#define VIDEO_HEIGHT 160
#define VIDEO_STRIDE 256
#define AUDIO_SAMPLE_RATE 48000
#define MAX_ROM_SIZE (32U * 1024U * 1024U)

_Static_assert(sizeof(color_t) == 4, "Android adapter requires mGBA's 32-bit color format");

struct MgbaSession {
    struct mCore* core;
    void* rom_data;
    color_t video[VIDEO_STRIDE * VIDEO_HEIGHT];
    MgbaPlatform platform;
    bool core_initialized;
    bool config_initialized;
    bool loaded;
    int video_width;
    int video_height;
};

static void release_core(MgbaSession* session) {
    if (session->core) {
        if (session->config_initialized) {
            mCoreConfigDeinit(&session->core->config);
        }
        if (session->core_initialized) {
            session->core->deinit(session->core);
        } else {
            free(session->core);
        }
    }
    free(session->rom_data);
    session->core = NULL;
    session->rom_data = NULL;
    session->core_initialized = false;
    session->config_initialized = false;
    session->loaded = false;
    session->video_width = 0;
    session->video_height = 0;
}

static bool setup_core(MgbaSession* session) {
    enum mPlatform platform = session->platform == MGBA_PLATFORM_GB
            ? mPLATFORM_GB : mPLATFORM_GBA;
    session->core = mCoreCreate(platform);
    if (!session->core) {
        return false;
    }
    if (!session->core->init(session->core)) {
        free(session->core);
        session->core = NULL;
        return false;
    }
    session->core_initialized = true;
    mCoreInitConfig(session->core, NULL);
    session->config_initialized = true;
    struct mCoreOptions options = {
        .useBios = true,
        .fpsTarget = 60.0f,
        .sampleRate = AUDIO_SAMPLE_RATE,
        .volume = 0x100,
    };
    mCoreConfigLoadDefaults(&session->core->config, &options);
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

static bool recover_unloaded_core(MgbaSession* session) {
    release_core(session);
    return setup_core(session);
}

static bool refresh_dimensions(MgbaSession* session) {
    unsigned width = VIDEO_WIDTH;
    unsigned height = VIDEO_HEIGHT;
    session->core->desiredVideoDimensions(session->core, &width, &height);
    if (!width || !height || width > VIDEO_STRIDE || height > VIDEO_HEIGHT) {
        return false;
    }
    session->video_width = (int) width;
    session->video_height = (int) height;
    session->core->setVideoBuffer(session->core, session->video, width);
    return true;
}

MgbaSession* mgba_session_create(MgbaPlatform platform) {
    if (platform != MGBA_PLATFORM_GBA && platform != MGBA_PLATFORM_GB) {
        return NULL;
    }
    MgbaSession* session = calloc(1, sizeof(*session));
    if (!session) {
        return NULL;
    }
    session->platform = platform;
    if (!setup_core(session)) {
        release_core(session);
        free(session);
        return NULL;
    }
    return session;
}

void mgba_session_destroy(MgbaSession* session) {
    if (!session) {
        return;
    }
    release_core(session);
    free(session);
}

bool mgba_session_load_rom_owned(MgbaSession* session, void* data, size_t size) {
    if (!session || !session->core || session->loaded || !data
            || !size || size > MAX_ROM_SIZE) {
        free(data);
        return false;
    }
    struct VFile* file = VFileFromMemory(data, size);
    if (!file) {
        free(data);
        return false;
    }
    if (!session->core->loadROM(session->core, file)) {
        file->close(file);
        free(data);
        (void) recover_unloaded_core(session);
        return false;
    }
    session->rom_data = data;
    session->core->reset(session->core);
    if (!refresh_dimensions(session)) {
        (void) recover_unloaded_core(session);
        return false;
    }
    session->loaded = true;
    return true;
}

bool mgba_session_load_rom_file(MgbaSession* session, const char* path) {
    if (!session || !session->core || session->loaded || !path) {
        return false;
    }
    struct VFile* file = VFileOpen(path, O_RDONLY);
    if (!file) {
        return false;
    }
    off_t file_size = file->size(file);
    if (file_size <= 0 || (uint64_t) file_size > MAX_ROM_SIZE) {
        file->close(file);
        return false;
    }
    void* data = malloc((size_t) file_size);
    if (!data) {
        file->close(file);
        return false;
    }
    size_t offset = 0;
    while (offset < (size_t) file_size) {
        ssize_t count = file->read(file, (uint8_t*) data + offset,
                                   (size_t) file_size - offset);
        if (count <= 0) {
            file->close(file);
            free(data);
            return false;
        }
        offset += (size_t) count;
    }
    file->close(file);
    return mgba_session_load_rom_owned(session, data, (size_t) file_size);
}

bool mgba_session_is_loaded(const MgbaSession* session) {
    return session && session->loaded;
}

int mgba_session_video_width(const MgbaSession* session) {
    return session ? session->video_width : 0;
}

int mgba_session_video_height(const MgbaSession* session) {
    return session ? session->video_height : 0;
}

const void* mgba_session_video_buffer(const MgbaSession* session,
                                      size_t* byte_count) {
    if (byte_count) {
        *byte_count = 0;
    }
    if (!session || !session->loaded || !byte_count) {
        return NULL;
    }
    *byte_count = (size_t) session->video_width
            * (size_t) session->video_height * sizeof(session->video[0]);
    return session->video;
}

int mgba_session_run_frame_direct(MgbaSession* session,
                                  uint32_t keys,
                                  int16_t* stereo_audio,
                                  size_t audio_sample_capacity) {
    if (!session || !session->loaded || !stereo_audio
            || audio_sample_capacity < 2) {
        return -1;
    }
    session->core->setKeys(session->core, keys & 0x3FFU);
    session->core->runFrame(session->core);
    size_t frame_pixels = (size_t) session->video_width
            * (size_t) session->video_height;
    for (size_t i = 0; i < frame_pixels; ++i) {
        session->video[i] |= 0xFF000000U;
    }

    blip_t* left = session->core->getAudioChannel(session->core, 0);
    blip_t* right = session->core->getAudioChannel(session->core, 1);
    int available = blip_samples_avail(left);
    if (available <= 0) {
        return 0;
    }
    size_t frame_capacity = audio_sample_capacity / 2;
    int frames_to_read = available;
    if (frame_capacity < (size_t) frames_to_read) {
        frames_to_read = (int) frame_capacity;
    }
    int left_frames = blip_read_samples(left, stereo_audio, frames_to_read, true);
    int right_frames = blip_read_samples(right, stereo_audio + 1, frames_to_read, true);
    return left_frames < right_frames ? left_frames : right_frames;
}

int mgba_session_run_frame(MgbaSession* session,
                           uint32_t keys,
                           int32_t* argb_pixels,
                           size_t pixel_capacity,
                           int16_t* stereo_audio,
                           size_t audio_sample_capacity) {
    if (!session || !session->loaded || !argb_pixels || !stereo_audio) {
        return -1;
    }
    size_t required_pixels = (size_t) session->video_width
            * (size_t) session->video_height;
    if (pixel_capacity < required_pixels || audio_sample_capacity < 2) {
        return -1;
    }
    int produced = mgba_session_run_frame_direct(
            session, keys, stereo_audio, audio_sample_capacity);
    if (produced < 0) {
        return produced;
    }
    for (size_t i = 0; i < required_pixels; ++i) {
        uint32_t native = session->video[i];
        argb_pixels[i] = (int32_t) (0xFF000000U
                | ((native & 0x000000FFU) << 16)
                | (native & 0x0000FF00U)
                | ((native & 0x00FF0000U) >> 16));
    }
    return produced;
}

bool mgba_session_frame_counter(const MgbaSession* session, uint64_t* frame_counter) {
    if (!session || !session->loaded || !frame_counter) {
        return false;
    }
    *frame_counter = session->core->frameCounter(session->core);
    return true;
}

bool mgba_session_set_dmg_palette(MgbaSession* session,
                                  uint32_t s0,
                                  uint32_t s1,
                                  uint32_t s2,
                                  uint32_t s3) {
    if (!session || !session->core) {
        return false;
    }
    uint32_t shades[4] = {s0 & 0xFFFFFFU, s1 & 0xFFFFFFU,
                          s2 & 0xFFFFFFU, s3 & 0xFFFFFFU};
    char key[16];
    for (int i = 0; i < 12; ++i) {
        int written = snprintf(key, sizeof(key), "gb.pal[%d]", i);
        if (written <= 0 || (size_t) written >= sizeof(key)) {
            return false;
        }
        mCoreConfigSetIntValue(&session->core->config, key, (int) shades[i % 4]);
    }
    mCoreLoadConfig(session->core);
    return true;
}

size_t mgba_session_state_size(const MgbaSession* session) {
    return session && session->loaded ? session->core->stateSize(session->core) : 0;
}

bool mgba_session_save_state(MgbaSession* session, void* output, size_t output_size) {
    size_t required = mgba_session_state_size(session);
    return required && output && output_size == required
            && session->core->saveState(session->core, output);
}

bool mgba_session_load_state(MgbaSession* session, const void* state, size_t state_size) {
    size_t required = mgba_session_state_size(session);
    return required && state && state_size == required
            && session->core->loadState(session->core, state);
}

bool mgba_session_reset(MgbaSession* session) {
    if (!session || !session->loaded) {
        return false;
    }
    session->core->reset(session->core);
    return true;
}

bool mgba_session_copy_savedata(MgbaSession* session, void** output, size_t* output_size) {
    if (!session || !session->loaded || !output || !output_size) {
        return false;
    }
    *output = NULL;
    *output_size = 0;
    size_t size = session->core->savedataClone(session->core, output);
    if (size && !*output) {
        return false;
    }
    if (!size) {
        free(*output);
        *output = NULL;
        return true;
    }
    *output_size = size;
    return true;
}

bool mgba_session_restore_savedata(MgbaSession* session, const void* data, size_t size) {
    if (!session || !session->loaded || !data || !size) {
        return false;
    }
    struct VFile* save = VFileMemChunk(data, size);
    if (!save) {
        return false;
    }
    if (!session->core->loadSave(session->core, save)) {
        save->close(save);
        return false;
    }
    return true;
}
