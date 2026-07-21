# mGBA JNI Adapter Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate Garnacha Boy's mGBA session ownership from JNI conversion and validate the product-owned native lifecycle under ASan and UBSan without changing the Java API.

**Architecture:** Add one opaque pure-C `MgbaSession` module that owns mGBA, ROM memory, frame buffers, states, and savedata. Reduce `mgba_android.c` to JNI resource conversion and result mapping; link the same session module into a framework-free host test and the Android shared library.

**Tech Stack:** C11, mGBA 0.10.5 C API, JNI, CMake/Ninja/CTest, AddressSanitizer, UndefinedBehaviorSanitizer, Android Gradle Plugin/JUnit instrumentation.

## Global Constraints

- Keep every existing Java native method name and Java-visible exception category unchanged.
- Keep `vendor/mgba` at 0.10.5 and do not modify files under `vendor/mgba/`.
- Add no dependency, C++ conversion, logging framework, allocator shim, or generic backend interface.
- Do not modify legacy root `src/main.c`; its cleanup is a separate approved follow-on design.
- Keep the existing 32 MiB ROM ceiling, 48 kHz stereo audio, 256-pixel native stride, and SGB borders disabled.
- Apply `-Wall -Wextra -Werror` only to product-owned C, never vendored mGBA.
- Sanitizers are host-test-only behind `GARNACHA_SANITIZERS=ON`; release and Android binaries do not gain sanitizer flags.
- Preserve unrelated dirty-worktree changes. Before every commit, verify that only the task's named files are staged.
- Use only the repository's MIT-licensed `hello.gba`, `hello.gb`, and `sram.gba` test assets.

## File Map

- Create `mgba-android/core/src/main/cpp/mgba_session.h`: opaque pure-C session API and ownership contract.
- Create `mgba-android/core/src/main/cpp/mgba_session.c`: mGBA lifecycle, ROM ownership, frame/audio conversion, state, reset, palette, and savedata.
- Modify `mgba-android/core/src/main/cpp/mgba_android.c`: JNI-only translation layer.
- Create `mgba-android/smoke/mgba_session_test.c`: framework-free host regression test.
- Modify `mgba-android/smoke/CMakeLists.txt`: GB-enabled host session target, strict warnings, sanitizer option, and CTest registration.
- Modify `mgba-android/core/CMakeLists.txt`: compile both product C files with strict warnings.
- Modify `mgba-android/README.md`: document sanitizer validation command.

---

### Task 1: Extract lifecycle and transactional ROM loading

**Files:**
- Create: `mgba-android/core/src/main/cpp/mgba_session.h`
- Create: `mgba-android/core/src/main/cpp/mgba_session.c`
- Create: `mgba-android/smoke/mgba_session_test.c`
- Modify: `mgba-android/smoke/CMakeLists.txt`

**Interfaces:**
- Consumes: mGBA `mCore`, `VFile`, config, GB, and GBA APIs.
- Produces:
  - `MgbaSession* mgba_session_create(MgbaPlatform platform)`
  - `void mgba_session_destroy(MgbaSession* session)`
  - `bool mgba_session_load_rom_owned(MgbaSession* session, void* data, size_t size)`
  - `bool mgba_session_load_rom_file(MgbaSession* session, const char* path)`
  - `bool mgba_session_is_loaded(const MgbaSession* session)`
  - `int mgba_session_video_width(const MgbaSession* session)`
  - `int mgba_session_video_height(const MgbaSession* session)`

- [ ] **Step 1: Write the complete public C header**

Create `mgba-android/core/src/main/cpp/mgba_session.h`:

```c
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

int mgba_session_run_frame(MgbaSession* session,
                           uint32_t keys,
                           uint32_t* argb_pixels,
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
```

- [ ] **Step 2: Write the failing lifecycle/ROM host test**

Create `mgba-android/smoke/mgba_session_test.c` with only the first task's tests:

```c
#include "mgba_session.h"

#include <stdio.h>
#include <stdlib.h>

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
    if (failures) {
        fprintf(stderr, "%d session test checks failed\n", failures);
        return EXIT_FAILURE;
    }
    puts("mGBA session lifecycle and ROM tests passed");
    return EXIT_SUCCESS;
}
```

- [ ] **Step 3: Add the failing host target**

Replace `mgba-android/smoke/CMakeLists.txt` with:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(mgba_core_smoke LANGUAGES C)

set(MGBA_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../vendor/mgba")
set(MGBA_SESSION_DIR "${CMAKE_CURRENT_LIST_DIR}/../core/src/main/cpp")
set(TEST_ASSET_DIR "${CMAKE_CURRENT_LIST_DIR}/../core/src/androidTest/assets")
set(LIBMGBA_ONLY ON CACHE BOOL "Build only libmGBA" FORCE)
set(M_CORE_GBA ON CACHE BOOL "Build the Game Boy Advance core" FORCE)
set(M_CORE_GB ON CACHE BOOL "Build the Game Boy core" FORCE)
set(BUILD_LTO OFF CACHE BOOL "Keep the smoke build deterministic" FORCE)
add_subdirectory("${MGBA_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/mgba" EXCLUDE_FROM_ALL)

function(garnacha_strict_c target)
    if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target} PRIVATE -Wall -Wextra -Werror)
    endif()
endfunction()

add_executable(mgba-core-smoke mgba_core_smoke.c)
target_link_libraries(mgba-core-smoke PRIVATE mgba)

add_executable(mgba-core-benchmark mgba_core_benchmark.c)
target_link_libraries(mgba-core-benchmark PRIVATE mgba)

add_library(garnacha-mgba-session STATIC "${MGBA_SESSION_DIR}/mgba_session.c")
target_include_directories(garnacha-mgba-session PUBLIC "${MGBA_SESSION_DIR}")
target_link_libraries(garnacha-mgba-session PRIVATE mgba)
garnacha_strict_c(garnacha-mgba-session)

add_executable(mgba-session-test mgba_session_test.c)
target_link_libraries(mgba-session-test PRIVATE garnacha-mgba-session mgba)
garnacha_strict_c(mgba-session-test)

enable_testing()
add_test(NAME mgba-core-smoke COMMAND mgba-core-smoke)
add_test(NAME mgba-session-test COMMAND mgba-session-test
    "${TEST_ASSET_DIR}/hello.gba"
    "${TEST_ASSET_DIR}/hello.gb"
    "${TEST_ASSET_DIR}/sram.gba")
```

- [ ] **Step 4: Run the test to verify it fails before the session implementation exists**

Run:

```sh
rm -rf build/mgba-smoke
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/mgba-smoke
```

Expected: CMake generation fails because `mgba_session.c` does not exist yet, proving the new host target requires the extracted boundary.

- [ ] **Step 5: Implement lifecycle and ROM loading**

Create `mgba-android/core/src/main/cpp/mgba_session.c`:

```c
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
    size_t rom_size;
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
    session->rom_size = 0;
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
        recover_unloaded_core(session);
        return false;
    }
    session->rom_data = data;
    session->rom_size = size;
    session->core->reset(session->core);
    if (!refresh_dimensions(session)) {
        recover_unloaded_core(session);
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
```

- [ ] **Step 6: Build and run the lifecycle/ROM tests**

Run:

```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
```

Expected: `mgba-core-smoke` and `mgba-session-test` pass; output includes `mGBA session lifecycle and ROM tests passed`.

- [ ] **Step 7: Commit only Task 1 files**

```sh
git add mgba-android/core/src/main/cpp/mgba_session.h \
  mgba-android/core/src/main/cpp/mgba_session.c \
  mgba-android/smoke/mgba_session_test.c \
  mgba-android/smoke/CMakeLists.txt
git diff --cached --name-only
git commit -m "refactor(core): extract mGBA session lifecycle"
```

Expected staged list: exactly the four files above.

---

### Task 2: Move frame, state, reset, palette, and savedata behavior into the session

**Files:**
- Modify: `mgba-android/core/src/main/cpp/mgba_session.c`
- Modify: `mgba-android/smoke/mgba_session_test.c`

**Interfaces:**
- Consumes: Task 1's opaque `MgbaSession` lifecycle and ROM ownership.
- Produces: all remaining functions already declared in `mgba_session.h`.

- [ ] **Step 1: Add failing session-operation tests**

Add `#include <string.h>` to the test file's top include block, then insert these functions before `main` in `mgba-android/smoke/mgba_session_test.c`:

```c
static void test_frame_validation_and_output(const char* gba_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    uint32_t pixels[240 * 160] = {0};
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

    int audio_frames = mgba_session_run_frame(session, 0, pixels,
                                               240 * 160, audio, 4096);
    CHECK(audio_frames >= 0);
    CHECK(mgba_session_frame_counter(session, &after));
    CHECK(after == before + 1);
    bool non_black = false;
    for (size_t i = 0; i < 240U * 160U; ++i) {
        if (pixels[i] != 0xFF000000U) {
            non_black = true;
            break;
        }
    }
    CHECK(non_black);
    mgba_session_destroy(session);
}

static void test_state_and_reset(const char* gba_path) {
    MgbaSession* session = mgba_session_create(MGBA_PLATFORM_GBA);
    uint32_t pixels[240 * 160] = {0};
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
    uint32_t pixels[240 * 160] = {0};
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
```

Add these calls after the existing test calls in `main`:

```c
    test_frame_validation_and_output(argv[1]);
    test_state_and_reset(argv[1]);
    test_savedata_round_trip(argv[3]);
    test_dmg_palette(argv[2]);
```

- [ ] **Step 2: Run the new tests to verify they fail at link time**

Run:

```sh
cmake --build build/mgba-smoke
```

Expected: undefined references to `mgba_session_run_frame`, `mgba_session_save_state`, and the other newly exercised operations.

- [ ] **Step 3: Implement the remaining session operations**

Append to `mgba-android/core/src/main/cpp/mgba_session.c`:

```c
int mgba_session_run_frame(MgbaSession* session,
                           uint32_t keys,
                           uint32_t* argb_pixels,
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
    session->core->setKeys(session->core, keys & 0x3FFU);
    session->core->runFrame(session->core);
    for (size_t y = 0; y < (size_t) session->video_height; ++y) {
        for (size_t x = 0; x < (size_t) session->video_width; ++x) {
            uint32_t native = session->video[y * VIDEO_STRIDE + x];
            argb_pixels[y * (size_t) session->video_width + x] = 0xFF000000U
                    | ((native & 0x000000FFU) << 16)
                    | (native & 0x0000FF00U)
                    | ((native & 0x00FF0000U) >> 16);
        }
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
    return session && session->loaded && data && size
            && session->core->savedataRestore(session->core, data, size, false);
}
```

- [ ] **Step 4: Run all host tests**

Run:

```sh
cmake --build build/mgba-smoke
ctest --test-dir build/mgba-smoke --output-on-failure
```

Expected: both CTest tests pass and the session test prints `mGBA session lifecycle and ROM tests passed`.

- [ ] **Step 5: Commit Task 2**

```sh
git add mgba-android/core/src/main/cpp/mgba_session.c \
  mgba-android/smoke/mgba_session_test.c
git diff --cached --name-only
git commit -m "test(core): cover native session operations"
```

Expected staged list: exactly the two files above.

---

### Task 3: Replace mGBA logic in the JNI file with session delegation

**Files:**
- Modify: `mgba-android/core/src/main/cpp/mgba_android.c`
- Modify: `mgba-android/core/CMakeLists.txt`

**Interfaces:**
- Consumes: the complete `mgba_session.h` API from Tasks 1–2.
- Produces: the unchanged JNI symbols declared by `MgbaSession.java` and `MgbaCore.java`.

- [ ] **Step 1: Replace `mgba_android.c` with a thin JNI wrapper**

Rewrite `mgba-android/core/src/main/cpp/mgba_android.c` so it contains no `struct MgbaSession` definition and no direct `mCore` operations. Use this complete structure and preserve every JNI symbol exactly:

```c
/* MIT-licensed JNI adapter. mGBA remains licensed under MPL-2.0. */
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
    if (!session) return JNI_FALSE;
    mgba_session_destroy(session);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbaCore(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    return can_create(MGBA_PLATFORM_GBA);
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaCore_canCreateGbCore(JNIEnv* env, jclass clazz) {
    (void) env; (void) clazz;
    return can_create(MGBA_PLATFORM_GB);
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeCreate(
        JNIEnv* env, jclass clazz, jint platform) {
    (void) env; (void) clazz;
    MgbaPlatform native_platform = platform == 1 ? MGBA_PLATFORM_GB : MGBA_PLATFORM_GBA;
    return (jlong) (uintptr_t) mgba_session_create(native_platform);
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadRom(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray rom) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !rom) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, rom);
    if (size <= 0 || size > MAX_ROM_SIZE) return JNI_FALSE;
    void* data = malloc((size_t) size);
    if (!data) return JNI_FALSE;
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
    if (!session || !path) return JNI_FALSE;
    const char* native_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (!native_path) return JNI_FALSE;
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
    if (!session || !pixels || !audio) return -1;
    jsize pixel_capacity = (*env)->GetArrayLength(env, pixels);
    jsize audio_capacity = (*env)->GetArrayLength(env, audio);
    jint* pixel_data = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!pixel_data) return -1;
    jshort* audio_data = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!audio_data) {
        (*env)->ReleaseIntArrayElements(env, pixels, pixel_data, JNI_ABORT);
        return -1;
    }
    int produced = mgba_session_run_frame(session, (uint32_t) keys,
            (uint32_t*) pixel_data, (size_t) pixel_capacity,
            (int16_t*) audio_data, (size_t) audio_capacity);
    jint release_mode = produced >= 0 ? 0 : JNI_ABORT;
    (*env)->ReleaseShortArrayElements(env, audio, audio_data, release_mode);
    (*env)->ReleaseIntArrayElements(env, pixels, pixel_data, release_mode);
    return produced;
}

JNIEXPORT jlong JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeFrameCounter(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    uint64_t counter = 0;
    return mgba_session_frame_counter(session_from_handle(handle), &counter)
            ? (jlong) counter : -1;
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoWidth(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return mgba_session_video_width(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeVideoHeight(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return mgba_session_video_height(session_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSetDmgPalette(
        JNIEnv* env, jclass clazz, jlong handle, jint s0, jint s1, jint s2, jint s3) {
    (void) env; (void) clazz;
    mgba_session_set_dmg_palette(session_from_handle(handle),
            (uint32_t) s0, (uint32_t) s1, (uint32_t) s2, (uint32_t) s3);
}

JNIEXPORT jbyteArray JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeSaveState(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    size_t size = mgba_session_state_size(session);
    if (!size || size > INT_MAX) return NULL;
    void* state = malloc(size);
    if (!state) return NULL;
    if (!mgba_session_save_state(session, state, size)) {
        free(state);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, state);
        if ((*env)->ExceptionCheck(env)) result = NULL;
    }
    free(state);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeLoadState(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray state) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !state) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, state);
    if (size <= 0) return JNI_FALSE;
    void* data = malloc((size_t) size);
    if (!data) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, state, 0, size, data);
    bool loaded = !(*env)->ExceptionCheck(env)
            && mgba_session_load_state(session, data, (size_t) size);
    free(data);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeReset(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
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
        if ((*env)->ExceptionCheck(env)) result = NULL;
    }
    free(data);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeRestoreSavedata(
        JNIEnv* env, jclass clazz, jlong handle, jbyteArray input) {
    (void) clazz;
    MgbaSession* session = session_from_handle(handle);
    if (!session || !input) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, input);
    if (size <= 0) return JNI_FALSE;
    void* data = malloc((size_t) size);
    if (!data) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, input, 0, size, data);
    bool restored = !(*env)->ExceptionCheck(env)
            && mgba_session_restore_savedata(session, data, (size_t) size);
    free(data);
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_trebuchetdynamics_emulator_mgba_MgbaSession_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    mgba_session_destroy(session_from_handle(handle));
}
```

- [ ] **Step 2: Compile both product C files into the Android library with strict warnings**

Replace the final library block in `mgba-android/core/CMakeLists.txt`:

```cmake
add_library(mgba-android SHARED
    src/main/cpp/mgba_android.c
    src/main/cpp/mgba_session.c)
target_include_directories(mgba-android PRIVATE src/main/cpp)
target_link_libraries(mgba-android PRIVATE mgba android log)
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(mgba-android PRIVATE -Wall -Wextra -Werror)
endif()
```

- [ ] **Step 3: Build Android native targets and instrumentation APK**

Run:

```sh
tools/android_project/gradlew -p mgba-android clean \
  :core:assembleDebug :core:assembleDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`; no warning from either product-owned C file and both AAR/test APK outputs exist.

- [ ] **Step 4: Run Android instrumentation**

Run with a booted emulator or connected device selected through the environment:

```sh
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the target emulator or device serial}"
tools/android_project/gradlew -p mgba-android :core:connectedDebugAndroidTest
```

Expected: all existing `MgbaCoreInstrumentedTest` methods pass, exercising every migrated JNI operation through real Java/native linkage.

- [ ] **Step 5: Commit Task 3**

```sh
git add mgba-android/core/src/main/cpp/mgba_android.c \
  mgba-android/core/CMakeLists.txt
git diff --cached --name-only
git commit -m "refactor(core): isolate JNI from mGBA session state"
```

Expected staged list: exactly the two files above.

---

### Task 4: Add sanitizer validation and document the native gate

**Files:**
- Modify: `mgba-android/smoke/CMakeLists.txt`
- Modify: `mgba-android/README.md`

**Interfaces:**
- Consumes: `garnacha-mgba-session` and `mgba-session-test` from Task 1.
- Produces: `GARNACHA_SANITIZERS` CMake option and documented validation command.

- [ ] **Step 1: Confirm the sanitizer option does not exist**

Run:

```sh
rm -rf build/mgba-sanitize-check
cmake -S mgba-android/smoke -B build/mgba-sanitize-check \
  -G Ninja -DGARNACHA_SANITIZERS=ON
grep -q -- '-fsanitize=address' build/mgba-sanitize-check/build.ninja
```

Expected: grep exits `1` because the requested sanitizer flags are absent before implementation.

- [ ] **Step 2: Add host-only sanitizer flags**

Insert after `garnacha_strict_c` in `mgba-android/smoke/CMakeLists.txt`:

```cmake
option(GARNACHA_SANITIZERS "Enable ASan and UBSan for product host tests" OFF)

function(garnacha_sanitize target)
    if(GARNACHA_SANITIZERS AND CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(${target} PRIVATE
            -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(${target} PRIVATE -fsanitize=address,undefined)
    endif()
endfunction()
```

After creating `garnacha-mgba-session`, add:

```cmake
garnacha_sanitize(garnacha-mgba-session)
```

After creating `mgba-session-test`, add:

```cmake
garnacha_sanitize(mgba-session-test)
```

Do not apply the helper to `mgba`, `mgba-core-smoke`, or `mgba-core-benchmark`.

- [ ] **Step 3: Run a clean sanitizer build and tests**

Run:

```sh
rm -rf build/mgba-smoke
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-smoke
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-smoke --output-on-failure
```

Expected: two passing tests, zero sanitizer diagnostics, and zero product-owned C warnings.

- [ ] **Step 4: Run the existing throughput executable without making a speed claim**

Run:

```sh
build/mgba-smoke/mgba-core-benchmark \
  mgba-android/core/src/androidTest/assets/hello.gba 30000
```

Expected: process exits zero and reports a positive frames-per-second result. Record the output in the execution notes; do not add a performance threshold to this hardening slice.

- [ ] **Step 5: Document the sanitizer gate**

Add under `## Build and test` in `mgba-android/README.md` after the existing CTest commands:

````markdown
For product-owned native lifecycle checks under AddressSanitizer and
UndefinedBehaviorSanitizer:

```sh
cmake -S mgba-android/smoke -B build/mgba-smoke -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DGARNACHA_SANITIZERS=ON
cmake --build build/mgba-smoke
ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
ctest --test-dir build/mgba-smoke --output-on-failure
```

Sanitizer flags apply only to the product session test, not vendored mGBA or
Android release artifacts.
````

- [ ] **Step 6: Run the complete repository-relevant validation gate**

Run:

```sh
tools/android_project/gradlew -p mgba-android clean lintDebug \
  :app:testDebugUnitTest :app:assembleBenchmark \
  :core:assembleBenchmark :core:assembleDebugAndroidTest

git diff --check
```

Expected: Gradle `BUILD SUCCESSFUL`, lint has zero errors, unit tests pass, benchmark APK/AAR and instrumentation APK are produced, and `git diff --check` exits zero.

- [ ] **Step 7: Commit Task 4**

```sh
git add mgba-android/smoke/CMakeLists.txt mgba-android/README.md
git diff --cached --name-only
git commit -m "test(core): run session checks under sanitizers"
```

Expected staged list: exactly the two files above.

---

## Final Review Checklist

- [ ] `mgba_android.c` contains JNI conversion only and no direct `mCore` access.
- [ ] `mgba_session.c` is the only product file that owns `mCore` and ROM backing memory.
- [ ] `git diff -- vendor/mgba` is empty.
- [ ] Java native declarations and JNI symbol names are unchanged.
- [ ] Host lifecycle, GB/GBA dimensions, failed-load retry, short buffers, frames, state, reset, palette, and savedata tests pass.
- [ ] ASan and UBSan report no findings.
- [ ] Product-owned C compiles under `-Wall -Wextra -Werror`.
- [ ] Existing Android instrumentation passes on the selected emulator/device.
- [ ] Android lint, unit tests, benchmark build, AAR build, and instrumentation APK build pass.
- [ ] The benchmark executable still runs; no unsupported performance claim is made.
- [ ] Only intended files appear in the four implementation commits.
