package dev.frostguard.app.bootstrap;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.HMONITOR;
import com.sun.jna.platform.win32.WinUser.MONITORINFO;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enables Windows' native move and snap handling without replacing Frostguard's custom title bar.
 */
public final class WindowsWindowManager {

    private static final Logger logger = LoggerFactory.getLogger(WindowsWindowManager.class);

    private static final int GWL_STYLE = -16;
    private static final int GWL_WNDPROC = -4;
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_CAPTION = 0x00C00000;
    private static final int WS_SYSMENU = 0x00080000;
    private static final int WS_THICKFRAME = 0x00040000;
    private static final int WS_MINIMIZEBOX = 0x00020000;
    private static final int WS_MAXIMIZEBOX = 0x00010000;
    private static final int REQUIRED_SNAP_STYLES = WS_CAPTION | WS_SYSMENU | WS_THICKFRAME
            | WS_MINIMIZEBOX | WS_MAXIMIZEBOX;

    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int HTCLIENT = 1;
    private static final int HTCAPTION = 2;
    private static final int HTLEFT = 10;
    private static final int HTBOTTOMRIGHT = 17;

    private static final int CUSTOM_TITLE_BAR_HEIGHT = 32;
    private static final int WINDOW_CONTROL_WIDTH = 46;
    private static final int WINDOW_CONTROL_COUNT = 3;

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int MONITOR_DEFAULTTONEAREST = 2;

    private final HWND window;
    private final int originalStyle;
    private final Pointer originalWindowProcedure;
    // JNA callbacks must remain strongly reachable for as long as native code can invoke them.
    private final WindowProc borderlessWindowProcedure;
    private boolean installed = true;

    private WindowsWindowManager(HWND window, int originalStyle, Pointer originalWindowProcedure,
                                 WindowProc borderlessWindowProcedure) {
        this.window = window;
        this.originalStyle = originalStyle;
        this.originalWindowProcedure = originalWindowProcedure;
        this.borderlessWindowProcedure = borderlessWindowProcedure;
    }

    public static Optional<WindowsWindowManager> install(Stage stage) {
        if (!isWindows(System.getProperty("os.name"))) {
            return Optional.empty();
        }

        try {
            HWND window = findCurrentProcessWindow(stage.getTitle());
            if (window == null) {
                logger.warn("Native Windows snapping unavailable: launcher window handle was not found");
                return Optional.empty();
            }

            int currentStyle = User32.INSTANCE.GetWindowLong(window, GWL_STYLE);
            int snapStyle = enableSnapStyles(currentStyle);
            Pointer originalProcedure = User32.INSTANCE.GetWindowLongPtr(window, GWL_WNDPROC).toPointer();
            BorderlessWindowProcedure borderlessProcedure =
                    new BorderlessWindowProcedure(originalProcedure);
            Native.setLastError(0);
            Pointer replacedProcedure = User32.INSTANCE.SetWindowLongPtr(window, GWL_WNDPROC,
                    CallbackReference.getFunctionPointer(borderlessProcedure));
            int procedureError = Native.getLastError();
            if (replacedProcedure == null && procedureError != 0) {
                logger.warn("Native Windows snapping unavailable: failed to install window procedure ({})",
                        procedureError);
                return Optional.empty();
            }

            WindowsWindowManager manager = new WindowsWindowManager(window, currentStyle,
                    originalProcedure, borderlessProcedure);
            if (snapStyle != currentStyle) {
                Native.setLastError(0);
                int previousStyle = User32.INSTANCE.SetWindowLong(window, GWL_STYLE, snapStyle);
                int error = Native.getLastError();
                if (previousStyle == 0 && error != 0) {
                    logger.warn("Native Windows snapping unavailable: failed to update window style ({})", error);
                    manager.uninstall();
                    return Optional.empty();
                }
                boolean refreshed = User32.INSTANCE.SetWindowPos(window, null, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                if (!refreshed) {
                    logger.warn("Native Windows snapping unavailable: failed to refresh the window frame ({})",
                            Native.getLastError());
                    manager.uninstall();
                    return Optional.empty();
                }
            }

            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> manager.uninstall());
            logger.info("Native Windows move and snap handling enabled");
            return Optional.of(manager);
        } catch (Throwable error) {
            logger.warn("Native Windows snapping unavailable; using JavaFX window handling", error);
            return Optional.empty();
        }
    }

    private void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        if (!User32.INSTANCE.IsWindow(window)) {
            return;
        }

        User32.INSTANCE.SetWindowLongPtr(window, GWL_WNDPROC, originalWindowProcedure);
        User32.INSTANCE.SetWindowLong(window, GWL_STYLE, originalStyle);
        User32.INSTANCE.SetWindowPos(window, null, 0, 0, 0, 0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    }

    static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    static int enableSnapStyles(int currentStyle) {
        return (currentStyle & ~WS_POPUP) | REQUIRED_SNAP_STYLES;
    }

    static boolean hasSnapStyles(int style) {
        return (style & REQUIRED_SNAP_STYLES) == REQUIRED_SNAP_STYLES && (style & WS_POPUP) == 0;
    }

    static boolean isCaptionHit(int screenX, int screenY, RECT windowBounds) {
        return isTitleBarHit(screenX, screenY, windowBounds)
                && !isWindowControlsHit(screenX, windowBounds);
    }

    static boolean isTitleBarHit(int screenX, int screenY, RECT windowBounds) {
        int titleBarBottom = windowBounds.top + CUSTOM_TITLE_BAR_HEIGHT;
        return screenX >= windowBounds.left && screenX < windowBounds.right
                && screenY >= windowBounds.top && screenY < titleBarBottom;
    }

    private static boolean isWindowControlsHit(int screenX, RECT windowBounds) {
        int controlsLeft = windowBounds.right - WINDOW_CONTROL_WIDTH * WINDOW_CONTROL_COUNT;
        return screenX >= controlsLeft;
    }

    static boolean isResizeHit(int hitTestResult) {
        return hitTestResult >= HTLEFT && hitTestResult <= HTBOTTOMRIGHT;
    }

    private static HWND findCurrentProcessWindow(String title) {
        int processId = Kernel32.INSTANCE.GetCurrentProcessId();
        AtomicReference<HWND> match = new AtomicReference<>();

        User32.INSTANCE.EnumWindows((candidate, data) -> {
            IntByReference candidateProcessId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(candidate, candidateProcessId);
            if (candidateProcessId.getValue() != processId || !User32.INSTANCE.IsWindowVisible(candidate)) {
                return true;
            }

            int titleLength = User32.INSTANCE.GetWindowTextLength(candidate);
            char[] titleBuffer = new char[titleLength + 1];
            User32.INSTANCE.GetWindowText(candidate, titleBuffer, titleBuffer.length);
            if (title.equals(Native.toString(titleBuffer))) {
                match.set(candidate);
                return false;
            }
            return true;
        }, Pointer.NULL);
        return match.get();
    }

    private interface User32Extensions extends StdCallLibrary {
        User32Extensions INSTANCE = Native.load("user32", User32Extensions.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean IsZoomed(HWND hwnd);

        boolean ClientToScreen(HWND hwnd, POINT point);
    }

    private static final class BorderlessWindowProcedure implements WindowProc {

        private final Pointer originalProcedure;

        private BorderlessWindowProcedure(Pointer originalProcedure) {
            this.originalProcedure = originalProcedure;
        }

        @Override
        public LRESULT callback(HWND hwnd, int message, WPARAM wParam, LPARAM lParam) {
            if (message == WM_NCCALCSIZE) {
                constrainMaximizedClientArea(hwnd, lParam);
                return new LRESULT(0);
            }
            if (message == WM_NCHITTEST) {
                LRESULT defaultHit = User32.INSTANCE.CallWindowProc(
                        originalProcedure, hwnd, message, wParam, lParam);
                if (isResizeHit(defaultHit.intValue())) {
                    return defaultHit;
                }

                int packedPoint = lParam.intValue();
                int screenX = (short) (packedPoint & 0xFFFF);
                int screenY = (short) ((packedPoint >>> 16) & 0xFFFF);
                RECT clientBounds = getClientBoundsOnScreen(hwnd);
                if (clientBounds != null) {
                    if (isCaptionHit(screenX, screenY, clientBounds)) {
                        return new LRESULT(HTCAPTION);
                    }
                    if (isTitleBarHit(screenX, screenY, clientBounds)) {
                        return new LRESULT(HTCLIENT);
                    }
                }
                return defaultHit;
            }
            return User32.INSTANCE.CallWindowProc(originalProcedure, hwnd, message, wParam, lParam);
        }

        private void constrainMaximizedClientArea(HWND hwnd, LPARAM lParam) {
            if (lParam.longValue() == 0 || !User32Extensions.INSTANCE.IsZoomed(hwnd)) {
                return;
            }

            RECT clientBounds = Structure.newInstance(RECT.class, new Pointer(lParam.longValue()));
            clientBounds.read();
            HMONITOR monitor = User32.INSTANCE.MonitorFromRect(clientBounds, MONITOR_DEFAULTTONEAREST);
            MONITORINFO monitorInfo = new MONITORINFO();
            if (monitor == null || !User32.INSTANCE.GetMonitorInfo(monitor, monitorInfo).booleanValue()) {
                return;
            }

            clientBounds.left = monitorInfo.rcWork.left;
            clientBounds.top = monitorInfo.rcWork.top;
            clientBounds.right = monitorInfo.rcWork.right;
            clientBounds.bottom = monitorInfo.rcWork.bottom;
            clientBounds.write();
        }

        private RECT getClientBoundsOnScreen(HWND hwnd) {
            RECT clientBounds = new RECT();
            POINT clientOrigin = new POINT();
            if (!User32.INSTANCE.GetClientRect(hwnd, clientBounds)
                    || !User32Extensions.INSTANCE.ClientToScreen(hwnd, clientOrigin)) {
                return null;
            }
            clientBounds.right += clientOrigin.x;
            clientBounds.bottom += clientOrigin.y;
            clientBounds.left = clientOrigin.x;
            clientBounds.top = clientOrigin.y;
            return clientBounds;
        }
    }
}
