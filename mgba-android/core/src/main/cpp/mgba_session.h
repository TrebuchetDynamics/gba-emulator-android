#ifndef GARNACHA_MGBA_SESSION_H
#define GARNACHA_MGBA_SESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef enum MgbaPlatform {
    MGBA_PLATFORM_GBA = 0,
    MGBA_PLATFORM_GB = 1,
} MgbaPlatform;

typedef struct MgbaSession MgbaSession;

MgbaSession* mgba_session_create(MgbaPlatform platform);
void mgba_session_destroy(MgbaSession* session);

/* Consumes a malloc-compatible allocation on both success and failure. */
bool mgba_session_load_rom_owned(MgbaSession* session, void* data, size_t size);
bool mgba_session_load_rom_file(MgbaSession* session, const char* path);
bool mgba_session_is_loaded(const MgbaSession* session);
int mgba_session_video_width(const MgbaSession* session);
int mgba_session_video_height(const MgbaSession* session);

/* Runs one frame without converting the session-owned native video buffer. */
int mgba_session_run_frame_direct(MgbaSession* session,
                                  uint32_t keys,
                                  int16_t* stereo_audio,
                                  size_t audio_sample_capacity);

/* Returned storage remains owned by session and is valid until destruction. */
const void* mgba_session_video_buffer(const MgbaSession* session,
                                      size_t* byte_count);

int mgba_session_run_frame(MgbaSession* session,
                           uint32_t keys,
                           int32_t* argb_pixels,
                           size_t pixel_capacity,
                           int16_t* stereo_audio,
                           size_t audio_sample_capacity);
bool mgba_session_frame_counter(const MgbaSession* session, uint64_t* frame_counter);
bool mgba_session_set_dmg_palette(MgbaSession* session,
                                  uint32_t s0,
                                  uint32_t s1,
                                  uint32_t s2,
                                  uint32_t s3);

size_t mgba_session_state_size(const MgbaSession* session);
bool mgba_session_save_state(MgbaSession* session, void* output, size_t output_size);
bool mgba_session_load_state(MgbaSession* session, const void* state, size_t state_size);
bool mgba_session_reset(MgbaSession* session);

/* On success, caller frees *output with free(); empty savedata is NULL/0. */
bool mgba_session_copy_savedata(MgbaSession* session, void** output, size_t* output_size);
bool mgba_session_restore_savedata(MgbaSession* session, const void* data, size_t size);

#endif
