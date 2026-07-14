package com.trebuchetdynamics.emulator.app;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class NoticesTest {
    @Test
    public void readsTheNoticesStreamAsText() throws IOException {
        InputStream in = new ByteArrayInputStream(
                "# Title\n\nmGBA, MPL-2.0".getBytes(StandardCharsets.UTF_8));
        String text = Notices.load(in);
        assertTrue(text.contains("mGBA"));
        assertTrue(text.contains("MPL-2.0"));
    }

    @Test
    public void rejectsAnEmptyNoticesStream() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        // An empty notices file means the licence obligation is silently unmet;
        // fail loudly rather than shipping a blank screen.
        assertThrows(IOException.class, () -> Notices.load(in));
    }
}
