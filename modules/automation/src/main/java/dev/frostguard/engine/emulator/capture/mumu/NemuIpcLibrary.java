package dev.frostguard.engine.emulator.capture.mumu;

import com.sun.jna.Library;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA binding to MuMu's native {@code external_renderer_ipc.dll} interface.
 */
public interface NemuIpcLibrary extends Library {

    int nemu_input_event_touch_down(int handle, int displayId, int x, int y);

    int nemu_input_event_touch_up(int handle, int displayId);

    /**
     * Connects to the emulator instance's external renderer.
     *
     * @param installDir    full path to the MuMu installation root as a wide string
     * @param instanceIndex numeric zero-based index of the emulator instance
     * @return non-zero integer handle on success, or 0 on failure
     */
    int nemu_connect(WString installDir, int instanceIndex);

    /**
     * Disconnects from the external renderer and releases resources.
     *
     * @param handle connection handle returned by {@link #nemu_connect}
     */
    void nemu_disconnect(int handle);

    /**
     * Queries display dimensions or captures framebuffer pixels.
     *
     * @param handle     connection handle
     * @param displayId  target display ID (0 for primary display)
     * @param bufferSize size of output buffer in bytes, or 0 when querying dimensions
     * @param width      receives display width in pixels
     * @param height     receives display height in pixels
     * @param pixels     output pixel buffer (allocated by caller), or null/empty when querying
     * @return 0 on success, or non-zero error code
     */
    int nemu_capture_display(int handle, int displayId, int bufferSize,
                             IntByReference width, IntByReference height, byte[] pixels);

}
