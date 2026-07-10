#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define VIDEO_STRIDE 256
#define VIDEO_HEIGHT 160
#define AUDIO_SAMPLE_RATE 48000
#define AUDIO_CAPACITY 2048
#define WARMUP_FRAMES 100

static double monotonicSeconds(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec + value.tv_nsec / 1000000000.0;
}

static void drainAudio(struct mCore* core, int16_t* audio) {
    for (int channelIndex = 0; channelIndex < 2; ++channelIndex) {
        blip_t* channel = core->getAudioChannel(core, channelIndex);
        int available = blip_samples_avail(channel);
        if (available > AUDIO_CAPACITY) {
            available = AUDIO_CAPACITY;
        }
        if (available > 0) {
            blip_read_samples(channel, audio, available, false);
        }
    }
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s ROM_PATH [FRAME_COUNT]\n", argv[0]);
        return EXIT_FAILURE;
    }

    long frameCount = argc == 3 ? strtol(argv[2], NULL, 10) : 30000;
    if (frameCount <= 0 || frameCount > 1000000) {
        fputs("frame count must be between 1 and 1000000\n", stderr);
        return EXIT_FAILURE;
    }

    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core || !core->init(core)) {
        free(core);
        fputs("GBA core initialization failed\n", stderr);
        return EXIT_FAILURE;
    }

    mCoreInitConfig(core, NULL);
    struct mCoreOptions options = {
        .useBios = true,
        .fpsTarget = 60.0f,
        .sampleRate = AUDIO_SAMPLE_RATE,
        .volume = 0x100,
    };
    mCoreConfigLoadDefaults(&core->config, &options);
    mCoreLoadConfig(core);

    color_t* video = calloc(VIDEO_STRIDE * VIDEO_HEIGHT, sizeof(*video));
    int16_t* audio = calloc(AUDIO_CAPACITY, sizeof(*audio));
    if (!video || !audio) {
        fputs("benchmark buffer allocation failed\n", stderr);
        free(video);
        free(audio);
        mCoreConfigDeinit(&core->config);
        core->deinit(core);
        return EXIT_FAILURE;
    }

    core->setVideoBuffer(core, video, VIDEO_STRIDE);
    core->setAudioBufferSize(core, AUDIO_CAPACITY);
    if (!mCoreLoadFile(core, argv[1])) {
        fprintf(stderr, "mGBA rejected ROM: %s\n", argv[1]);
        free(video);
        free(audio);
        mCoreConfigDeinit(&core->config);
        core->deinit(core);
        return EXIT_FAILURE;
    }
    core->reset(core);

    for (int i = 0; i < WARMUP_FRAMES; ++i) {
        core->runFrame(core);
        drainAudio(core, audio);
    }

    double start = monotonicSeconds();
    for (long i = 0; i < frameCount; ++i) {
        core->runFrame(core);
        drainAudio(core, audio);
    }
    double elapsed = monotonicSeconds() - start;

    printf("frames=%ld seconds=%.6f us_per_frame=%.2f\n",
           frameCount, elapsed, elapsed * 1000000.0 / frameCount);

    free(video);
    free(audio);
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    return EXIT_SUCCESS;
}
