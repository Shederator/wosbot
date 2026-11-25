package dev.frostguard.engine.emulator.capture.mumu;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import dev.frostguard.engine.emulator.capture.PixelNormalizer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Supervised native worker process that hosts {@code external_renderer_ipc.dll}.
 * Isolates the main Frostguard JVM from native crashes, deadlocks, and C-runtime stdout noise.
 */
public final class NemuHelperWorker {

    public static final byte CMD_CAPTURE = 1;
    public static final byte CMD_QUIT = 2;
    public static final byte CMD_TOUCH_SUPPORT = 3;
    public static final byte CMD_TOUCH = 4;
    public static final int ERR_INVALID_TOUCH = -4;

    public static final int ERR_CONNECT_FAILED = -1;
    public static final int ERR_INVALID_DIMENSIONS = -2;
    public static final int ERR_GEOMETRY_CHANGED = -3;

    public static final int MAX_DIMENSION = 7680;
    public static final long MAX_FRAME_BYTES = 128 * 1024 * 1024L;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: NemuHelperWorker <mumuRootPath> <instanceIndex>");
            System.exit(1);
        }

        String rootPath = args[0];
        int instanceIndex;
        try {
            instanceIndex = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid instance index: " + args[1]);
            System.exit(1);
            return;
        }

        Optional<MuMuNemuDiscovery.NemuPaths> pathsOpt = MuMuNemuDiscovery.discover(Path.of(rootPath));
        if (pathsOpt.isEmpty()) {
            System.err.println("MuMu Nemu IPC DLL could not be discovered under: " + rootPath);
            System.exit(2);
            return;
        }

        MuMuNemuDiscovery.NemuPaths paths = pathsOpt.get();
        StringBuilder searchPath = new StringBuilder();
        for (Path p : paths.searchPaths()) {
            if (!searchPath.isEmpty()) {
                searchPath.append(File.pathSeparator);
            }
            searchPath.append(p.toAbsolutePath());
        }
        System.setProperty("jna.library.path", searchPath.toString());

        NemuIpcLibrary lib;
        try {
            lib = Native.load(paths.dllPath().toAbsolutePath().toString(), NemuIpcLibrary.class);
        } catch (Throwable t) {
            System.err.println("Failed to load native DLL: " + t.getMessage());
            System.exit(3);
            return;
        }

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            // Signal the listening port to the parent process on stdout
            System.out.println("PORT " + server.getLocalPort());
            System.out.flush();

            server.setSoTimeout(15000); // 15s budget for parent connection
            try (Socket socket = server.accept();
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                socket.setTcpNoDelay(true);

                int handle = lib.nemu_connect(new WString(paths.root().toAbsolutePath().toString()), instanceIndex);
                if (handle == 0) {
                    out.writeInt(ERR_CONNECT_FAILED);
                    out.flush();
                    return;
                }

                try {
                    IntByReference widthRef = new IntByReference();
                    IntByReference heightRef = new IntByReference();
                    int rc = lib.nemu_capture_display(handle, 0, 0, widthRef, heightRef, (byte[]) null);
                    if (rc != 0 || widthRef.getValue() <= 0 || heightRef.getValue() <= 0) {
                        out.writeInt(ERR_INVALID_DIMENSIONS);
                        out.flush();
                        return;
                    }

                    int width = widthRef.getValue();
                    int height = heightRef.getValue();
                    long requiredBytes = (long) width * height * 4L;
                    if (width > MAX_DIMENSION || height > MAX_DIMENSION || requiredBytes > MAX_FRAME_BYTES) {
                        out.writeInt(ERR_INVALID_DIMENSIONS);
                        out.flush();
                        return;
                    }

                    int bufferSize = (int) requiredBytes;
                    byte[] pixelBuffer = new byte[bufferSize];

                    // Handshake success
                    out.writeInt(0);
                    out.writeInt(width);
                    out.writeInt(height);
                    out.flush();

                    // Main command loop
                    while (true) {
                        int cmd = in.read();
                        if (cmd == -1 || cmd == CMD_QUIT) {
                            break;
                        }
                        if (cmd == CMD_TOUCH_SUPPORT) {
                            boolean supported;
                            try {
                                NativeLibrary nativeLibrary = NativeLibrary.getInstance(paths.dllPath().toAbsolutePath().toString());
                                nativeLibrary.getFunction("nemu_input_event_touch_down");
                                nativeLibrary.getFunction("nemu_input_event_touch_up");
                                supported = width < height;
                            } catch (UnsatisfiedLinkError error) {
                                supported = false;
                            }
                            out.writeInt(supported ? 1 : 0);
                            out.flush();
                        } else if (cmd == CMD_TOUCH) {
                            int x = in.readInt();
                            int y = in.readInt();
                            int endX = in.readInt();
                            int endY = in.readInt();
                            int durationMs = in.readInt();
                            try {
                                rc = new NemuTouchGesture(lib, handle, width, height)
                                        .swipe(x, y, endX, endY, durationMs);
                            } catch (IllegalArgumentException invalid) {
                                rc = ERR_INVALID_TOUCH;
                            }
                            out.writeInt(rc);
                            out.flush();
                        } else if (cmd == CMD_CAPTURE) {
                            widthRef.setValue(width);
                            heightRef.setValue(height);
                            rc = lib.nemu_capture_display(handle, 0, bufferSize, widthRef, heightRef, pixelBuffer);
                            int capturedWidth = widthRef.getValue();
                            int capturedHeight = heightRef.getValue();

                            if (rc != 0) {
                                out.writeInt(rc);
                                out.writeInt(0);
                                out.flush();
                            } else if (capturedWidth != width || capturedHeight != height) {
                                // Geometry changed (e.g. device rotation between portrait and landscape)
                                // Stride does not match pixelBuffer layout. Do not normalize with old dimensions.
                                out.writeInt(ERR_GEOMETRY_CHANGED);
                                out.writeInt(0);
                                out.flush();
                                break;
                            } else {
                                PixelNormalizer.flipY(pixelBuffer, width, height, 4);
                                out.writeInt(0);
                                out.writeInt(bufferSize);
                                out.write(pixelBuffer, 0, bufferSize);
                                out.flush();
                            }
                        }
                    }
                } finally {
                    lib.nemu_disconnect(handle);
                }
            }
        } catch (Throwable t) {
            System.err.println("NemuHelperWorker error: " + t.getMessage());
            System.exit(4);
        }
    }
}
