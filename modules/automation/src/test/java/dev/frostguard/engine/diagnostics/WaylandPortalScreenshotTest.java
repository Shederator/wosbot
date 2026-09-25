package dev.frostguard.engine.diagnostics;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylandPortalScreenshotTest {

    @Test
    void sessionTypeSelectsThePortalOnlyForWayland() {
        assertTrue(DesktopFrames.waylandSession("wayland", null));
        assertTrue(DesktopFrames.waylandSession(null, "wayland-0"));
        assertFalse(DesktopFrames.waylandSession("x11", "wayland-0"));
        assertFalse(DesktopFrames.waylandSession(null, null));
        assertFalse(DesktopFrames.waylandSession("tty", ""));
    }

    @Test
    void screenshotRequestDisablesTheInteractiveDialog() {
        List<String> command = WaylandPortalScreenshot.screenshotCommand("fgabc");

        assertTrue(command.contains("org.freedesktop.portal.Screenshot.Screenshot"));
        assertTrue(command.getLast().contains("'interactive': <false>"));
        assertTrue(command.getLast().contains("fgabc"));
        assertFalse(command.getLast().contains("true"));
    }

    @Test
    void successResponseDecodesThePortalFileUri() {
        String line = "/org/freedesktop/portal/desktop/request/1_2/fgabc: "
                + "org.freedesktop.portal.Request.Response (uint32 0, "
                + "{'uri': <'file:///tmp/portal%20shot.png'>})";

        assertEquals(Path.of("/tmp/portal shot.png"),
                WaylandPortalScreenshot.imageFromResponse(line, "fgabc").orElseThrow());
        assertFalse(WaylandPortalScreenshot.declined(line, "fgabc"));
    }

    @Test
    void deniedResponseProducesNoImage() {
        String line = "request/fgabc: org.freedesktop.portal.Request.Response (uint32 1, @a{sv} {})";

        assertTrue(WaylandPortalScreenshot.imageFromResponse(line, "fgabc").isEmpty());
        assertTrue(WaylandPortalScreenshot.declined(line, "fgabc"));
        assertFalse(WaylandPortalScreenshot.declined(
                "request/other: org.freedesktop.portal.Request.Response (uint32 1, @a{sv} {})",
                "fgabc"));
    }
}
