package dev.frostguard.app.bootstrap;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import javafx.application.Platform;
import javafx.stage.Stage;

public class DarkTitleBar {

    public interface DwmApi extends StdCallLibrary {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class, W32APIOptions.DEFAULT_OPTIONS);
        int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
    }

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    public static void enableDarkTitleBar(Stage stage, String backupTitle) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }

        Thread darkThread = new Thread(() -> {
            try {
                // Allow OS window to manifest and initialize
                Thread.sleep(800);
            } catch (InterruptedException e) {
                return;
            }
            Platform.runLater(() -> {
                try {
                    String activeTitle = stage.getTitle() != null && !stage.getTitle().isEmpty() ? stage.getTitle() : backupTitle;
                    HWND hwnd = User32.INSTANCE.FindWindow(null, activeTitle);
                    if (hwnd != null) {
                        IntByReference darkOn = new IntByReference(1);
                        int result = DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkOn, 4);
                        if (result != 0) {
                            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, darkOn, 4);
                        }
                    }
                } catch (Throwable e) {
                    System.err.println("Failed to inject dark title bar logic: " + e.getMessage());
                }
            });
        });
        darkThread.setDaemon(true);
        darkThread.start();
    }
}
