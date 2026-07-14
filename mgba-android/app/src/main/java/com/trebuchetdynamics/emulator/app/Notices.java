package com.trebuchetdynamics.emulator.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the packaged open-source notices. Pure java.io so it is unit-testable. */
final class Notices {
    private Notices() {
    }

    static String load(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        if (out.size() == 0) {
            throw new IOException("Notices are empty — the licence obligation is unmet");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
