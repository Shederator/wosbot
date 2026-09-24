package dev.frostguard.engine.diagnostics;

/**
 * Selects the desktop capture backend for the current session.
 * macOS uses the same {@link RobotDesktopCapture} path as Windows and X11 and is not tested.
 */
public final class DesktopFrames {

    private DesktopFrames() {
    }

    public static DesktopFrameSource platform() {
        return waylandSession(System.getenv("XDG_SESSION_TYPE"), System.getenv("WAYLAND_DISPLAY"))
                ? WaylandPortalScreenshot::capture
                : RobotDesktopCapture::capture;
    }

    static boolean waylandSession(String sessionType, String waylandDisplay) {
        if (sessionType != null && sessionType.equalsIgnoreCase("wayland")) {
            return true;
        }
        if (sessionType != null && sessionType.equalsIgnoreCase("x11")) {
            return false;
        }
        return waylandDisplay != null && !waylandDisplay.isBlank();
    }
}
