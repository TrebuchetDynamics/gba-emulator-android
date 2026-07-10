#include <mgba/core/core.h>
#include <mgba/core/version.h>

#include <stdio.h>
#include <stdlib.h>

int main(void) {
    struct mCore* core = mCoreCreate(mPLATFORM_GBA);
    if (!core) {
        fputs("mCoreCreate returned null\n", stderr);
        return EXIT_FAILURE;
    }
    if (!core->init(core)) {
        free(core);
        fputs("GBA core initialization failed\n", stderr);
        return EXIT_FAILURE;
    }

    unsigned width = 0;
    unsigned height = 0;
    core->desiredVideoDimensions(core, &width, &height);
    core->deinit(core);

    if (width != 240 || height != 160) {
        fprintf(stderr, "unexpected GBA dimensions: %ux%u\n", width, height);
        return EXIT_FAILURE;
    }

    printf("mGBA %s core initialized at %ux%u\n", projectVersion, width, height);
    return EXIT_SUCCESS;
}
