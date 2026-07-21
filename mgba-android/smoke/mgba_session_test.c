#include "mgba_session.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
        ++failures; \
    } \
} while (0)

static void* read_file(const char* path, size_t* size) {
    FILE* file = fopen(path, "rb");
    if (!file || fseek(file, 0, SEEK_END) != 0) {
        if (file) fclose(file);
        return NULL;
    }
    long length = ftell(file);
    if (length <= 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        return NULL;
    }
    void* data = malloc((size_t) length);
    if (!data || fread(data, 1, (size_t) length, file) != (size_t) length) {
        free(data);
        fclose(file);
        return NULL;
    }
    fclose(file);
    *size = (size_t) length;
    return data;
}

static void test_repeated_lifecycle(void) {
    for (int i = 0; i < 100; ++i) {
        MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
        CHECK(session != NULL);
        CHECK(!mgba_session_is_loaded(session));
        mgba_session_destroy(session);
    }
    mgba_session_destroy(NULL);
    CHECK(mgba_session_create((MgbaPlatform) 99) == NULL);
}

static void test_memory_load_and_second_load(const char* gba_path) {
    size_t size = 0;
    void* data = read_file(gba_path, &size);
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    CHECK(data != NULL);
    CHECK(session != NULL);
    CHECK(mgba_session_load_rom_owned(session, data, size));
    CHECK(mgba_session_is_loaded(session));
    CHECK(mgba_session_video_width(session) == 240);
    CHECK(mgba_session_video_height(session) == 160);

    data = read_file(gba_path, &size);
    CHECK(data != NULL);
    CHECK(!mgba_session_load_rom_owned(session, data, size));
    mgba_session_destroy(session);
}

static void test_failed_load_can_retry(const char* gba_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    void* invalid = malloc(1);
    CHECK(session != NULL);
    CHECK(invalid != NULL);
    CHECK(!mgba_session_load_rom_owned(session, invalid, 0));
    CHECK(!mgba_session_is_loaded(session));
    CHECK(mgba_session_load_rom_file(session, gba_path));
    CHECK(mgba_session_video_width(session) == 240);
    CHECK(mgba_session_video_height(session) == 160);
    mgba_session_destroy(session);
}

static void test_game_boy_dimensions(const char* gb_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GB);
    CHECK(session != NULL);
    CHECK(mgba_session_load_rom_file(session, gb_path));
    CHECK(mgba_session_video_width(session) == 160);
    CHECK(mgba_session_video_height(session) == 144);
    mgba_session_destroy(session);
}

static void test_empty_truncated_and_oversized_files(void) {
    const char* empty_path = "mgba-session-empty.rom";
    const char* oversized_path = "mgba-session-oversized.rom";
    FILE* file = fopen(empty_path, "wb");
    CHECK(file != NULL);
    if (file) fclose(file);

    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    CHECK(session != NULL);
    CHECK(!mgba_session_load_rom_file(session, empty_path));
    CHECK(!mgba_session_is_loaded(session));
    CHECK(remove(empty_path) == 0);

    file = fopen(oversized_path, "wb");
    CHECK(file != NULL);
    if (file) {
        CHECK(fseek(file, 32L * 1024L * 1024L, SEEK_SET) == 0);
        CHECK(fputc(0, file) != EOF);
        CHECK(fclose(file) == 0);
    }
    CHECK(!mgba_session_load_rom_file(session, oversized_path));
    CHECK(!mgba_session_is_loaded(session));
    CHECK(remove(oversized_path) == 0);
    mgba_session_destroy(session);
}

static void test_frame_validation_and_output(const char* gba_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    int32_t pixels[240 * 160] = {0};
    int16_t audio[4096] = {0};
    uint64_t before = 0;
    uint64_t after = 0;
    CHECK(session != NULL);
    CHECK(mgba_session_load_rom_file(session, gba_path));
    CHECK(mgba_session_frame_counter(session, &before));
    CHECK(mgba_session_run_frame(session, 0, pixels, 240 * 160 - 1,
                                 audio, 4096) == -1);
    CHECK(mgba_session_frame_counter(session, &after));
    CHECK(after == before);
    CHECK(mgba_session_run_frame(session, 0, pixels, 240 * 160,
                                 audio, 1) == -1);
    CHECK(mgba_session_frame_counter(session, &after));
    CHECK(after == before);

    int total_audio_frames = 0;
    for (int i = 0; i < 10; ++i) {
        int audio_frames = mgba_session_run_frame(session, 0, pixels,
                                                   240 * 160, audio, 4096);
        CHECK(audio_frames >= 0);
        if (audio_frames > 0) {
            total_audio_frames += audio_frames;
        }
    }
    CHECK(total_audio_frames > 0);
    CHECK(mgba_session_frame_counter(session, &after));
    CHECK(after == before + 10);
    bool non_black = false;
    for (size_t i = 0; i < 240U * 160U; ++i) {
        if (pixels[i] != (int32_t) 0xFF000000U) {
            non_black = true;
            break;
        }
    }
    CHECK(non_black);
    mgba_session_destroy(session);
}

static uint32_t native_to_argb(uint32_t native) {
    return 0xFF000000U
            | ((native & 0x000000FFU) << 16)
            | (native & 0x0000FF00U)
            | ((native & 0x00FF0000U) >> 16);
}

static void test_direct_frame_path(const char* rom_path,
                                   MgbaPlatform platform,
                                   int expected_width,
                                   int expected_height) {
    MgbaSession* direct = mgba_session_create(platform);
    MgbaSession* legacy = mgba_session_create(platform);
    size_t frame_bytes = 1;
    CHECK(direct != NULL);
    CHECK(legacy != NULL);
    CHECK(mgba_session_video_buffer(direct, &frame_bytes) == NULL);
    CHECK(frame_bytes == 0);
    CHECK(mgba_session_load_rom_file(direct, rom_path));
    CHECK(mgba_session_load_rom_file(legacy, rom_path));
    CHECK(mgba_session_video_width(direct) == expected_width);
    CHECK(mgba_session_video_height(direct) == expected_height);

    size_t frame_pixels = (size_t) expected_width * (size_t) expected_height;
    const uint32_t* native_pixels = mgba_session_video_buffer(direct, &frame_bytes);
    int32_t* argb_pixels = calloc(frame_pixels, sizeof(*argb_pixels));
    int16_t direct_audio[4096] = {0};
    int16_t legacy_audio[4096] = {0};
    CHECK(native_pixels != NULL);
    CHECK(frame_bytes == frame_pixels * sizeof(*native_pixels));
    CHECK(argb_pixels != NULL);

    uint64_t before = 0;
    uint64_t after = 0;
    CHECK(mgba_session_frame_counter(direct, &before));
    CHECK(mgba_session_run_frame_direct(direct, 0, direct_audio, 1) == -1);
    CHECK(mgba_session_frame_counter(direct, &after));
    CHECK(after == before);

    int direct_frames = mgba_session_run_frame_direct(
            direct, 0, direct_audio, sizeof(direct_audio) / sizeof(direct_audio[0]));
    int legacy_frames = mgba_session_run_frame(
            legacy, 0, argb_pixels, frame_pixels,
            legacy_audio, sizeof(legacy_audio) / sizeof(legacy_audio[0]));
    CHECK(direct_frames >= 0);
    CHECK(legacy_frames >= 0);
    if (native_pixels && argb_pixels) {
        for (size_t i = 0; i < frame_pixels; ++i) {
            CHECK((native_pixels[i] & 0xFF000000U) == 0xFF000000U);
            CHECK((uint32_t) argb_pixels[i] == native_to_argb(native_pixels[i]));
        }
    }

    free(argb_pixels);
    mgba_session_destroy(legacy);
    mgba_session_destroy(direct);
}

static void test_state_and_reset(const char* gba_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    int32_t pixels[240 * 160] = {0};
    int16_t audio[4096] = {0};
    CHECK(session != NULL);
    CHECK(mgba_session_load_rom_file(session, gba_path));
    for (int i = 0; i < 10; ++i) {
        CHECK(mgba_session_run_frame(session, 0, pixels, 240 * 160,
                                     audio, 4096) >= 0);
    }
    size_t state_size = mgba_session_state_size(session);
    void* state = malloc(state_size);
    uint64_t saved_frame = 0;
    uint64_t advanced_frame = 0;
    CHECK(state_size > 0);
    CHECK(state != NULL);
    CHECK(mgba_session_save_state(session, state, state_size));
    CHECK(!mgba_session_save_state(session, state, state_size - 1));
    CHECK(mgba_session_frame_counter(session, &saved_frame));
    CHECK(mgba_session_run_frame(session, 1, pixels, 240 * 160,
                                 audio, 4096) >= 0);
    CHECK(mgba_session_frame_counter(session, &advanced_frame));
    CHECK(advanced_frame > saved_frame);
    CHECK(mgba_session_load_state(session, state, state_size));
    CHECK(mgba_session_frame_counter(session, &advanced_frame));
    CHECK(advanced_frame == saved_frame);
    CHECK(mgba_session_reset(session));
    CHECK(mgba_session_frame_counter(session, &advanced_frame));
    CHECK(advanced_frame < saved_frame);
    free(state);
    mgba_session_destroy(session);
}

static void test_savedata_round_trip(const char* sram_path) {
    MgbaSession* first = mgba_session_create(MGBA_PLATFORM_GBA);
    MgbaSession* second = mgba_session_create(MGBA_PLATFORM_GBA);
    int32_t pixels[240 * 160] = {0};
    int16_t audio[4096] = {0};
    void* savedata = NULL;
    size_t savedata_size = 0;
    CHECK(first != NULL);
    CHECK(second != NULL);
    CHECK(mgba_session_load_rom_file(first, sram_path));
    for (int i = 0; i < 10; ++i) {
        CHECK(mgba_session_run_frame(first, 0, pixels, 240 * 160,
                                     audio, 4096) >= 0);
    }
    CHECK(mgba_session_copy_savedata(first, &savedata, &savedata_size));
    CHECK(savedata != NULL);
    CHECK(savedata_size > 0);
    CHECK(mgba_session_load_rom_file(second, sram_path));
    CHECK(mgba_session_restore_savedata(second, savedata, savedata_size));
    void* restored = NULL;
    size_t restored_size = 0;
    CHECK(mgba_session_copy_savedata(second, &restored, &restored_size));
    CHECK(restored_size == savedata_size);
    CHECK(restored != NULL);
    CHECK(memcmp(restored, savedata, savedata_size) == 0);
    free(restored);
    free(savedata);
    mgba_session_destroy(second);
    mgba_session_destroy(first);
}

static void test_dmg_palette(const char* gb_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GB);
    CHECK(session != NULL);
    CHECK(mgba_session_load_rom_file(session, gb_path));
    CHECK(mgba_session_set_dmg_palette(session,
          0xFFFFFFU, 0xA9A9A9U, 0x545454U, 0x000000U));
    mgba_session_destroy(session);
}

int main(int argc, char** argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s hello.gba hello.gb sram.gba\n", argv[0]);
        return EXIT_FAILURE;
    }
    test_repeated_lifecycle();
    test_memory_load_and_second_load(argv[1]);
    test_failed_load_can_retry(argv[1]);
    test_game_boy_dimensions(argv[2]);
    test_empty_truncated_and_oversized_files();
    test_frame_validation_and_output(argv[1]);
    test_direct_frame_path(argv[1], MGBA_PLATFORM_GBA, 240, 160);
    test_direct_frame_path(argv[2], MGBA_PLATFORM_GB, 160, 144);
    test_state_and_reset(argv[1]);
    test_savedata_round_trip(argv[3]);
    test_dmg_palette(argv[2]);
    if (failures) {
        fprintf(stderr, "%d session test checks failed\n", failures);
        return EXIT_FAILURE;
    }
    puts("mGBA session lifecycle and ROM tests passed");
    return EXIT_SUCCESS;
}
