package dev.frostguard.app.bootstrap;

import com.sun.jna.platform.win32.WinDef.RECT;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsWindowManagerTest {

    @Test
    void enablesSnapStylesAndRemovesPopupWindowMode() {
        int originalStyle = 0x80000000 | 0x10000000;

        int nativeStyle = WindowsWindowManager.enableSnapStyles(originalStyle);

        assertTrue(WindowsWindowManager.hasSnapStyles(nativeStyle));
        assertTrue((nativeStyle & 0x10000000) != 0, "unrelated window styles should be preserved");
    }

    @Test
    void rejectsIncompleteNativeSnapStyle() {
        assertFalse(WindowsWindowManager.hasSnapStyles(0));
        assertFalse(WindowsWindowManager.hasSnapStyles(0x80000000));
    }

    @Test
    void detectsWindowsWithoutDependingOnPropertyCapitalization() {
        assertTrue(WindowsWindowManager.isWindows("Windows 11"));
        assertTrue(WindowsWindowManager.isWindows("WINDOWS"));
        assertFalse(WindowsWindowManager.isWindows("Linux"));
        assertFalse(WindowsWindowManager.isWindows(null));
    }

    @Test
    void mapsOnlyFreeTitleBarSpaceToNativeCaption() {
        RECT bounds = rectangle(-1920, 0, 0, 1200);

        assertTrue(WindowsWindowManager.isCaptionHit(-1800, 16, bounds));
        assertFalse(WindowsWindowManager.isCaptionHit(-20, 16, bounds),
                "window controls must remain clickable JavaFX client content");
        assertTrue(WindowsWindowManager.isTitleBarHit(-20, 16, bounds));
        assertFalse(WindowsWindowManager.isCaptionHit(-1800, 40, bounds));
    }

    @Test
    void usesJavaFxLogicalCoordinatesAcrossMonitorOrigins() {
        RECT bounds = rectangle(100, 200, 2020, 1400);

        assertTrue(WindowsWindowManager.isCaptionHit(500, 231, bounds));
        assertFalse(WindowsWindowManager.isCaptionHit(1900, 220, bounds));
        assertFalse(WindowsWindowManager.isCaptionHit(500, 232, bounds));
    }

    @Test
    void preservesNativeResizeHitTestResults() {
        assertTrue(WindowsWindowManager.isResizeHit(10));
        assertTrue(WindowsWindowManager.isResizeHit(17));
        assertFalse(WindowsWindowManager.isResizeHit(2));
        assertFalse(WindowsWindowManager.isResizeHit(9));
    }

    private RECT rectangle(int left, int top, int right, int bottom) {
        RECT rectangle = new RECT();
        rectangle.left = left;
        rectangle.top = top;
        rectangle.right = right;
        rectangle.bottom = bottom;
        return rectangle;
    }
}
