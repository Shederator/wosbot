package dev.frostguard.engine.emulator.capture.mumu;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Discovers MuMu Player installation paths and the {@code external_renderer_ipc.dll} library.
 */
public final class MuMuNemuDiscovery {

    public record NemuPaths(Path root, Path dllPath, List<Path> searchPaths) {
        public NemuPaths {
            searchPaths = List.copyOf(searchPaths);
        }
    }

    private static final List<String> RELATIVE_DLL_CANDIDATES = List.of(
            "nx_device/15.0/shell/sdk/external_renderer_ipc.dll",
            "nx_device/12.0/shell/sdk/external_renderer_ipc.dll",
            "nx_main/sdk/external_renderer_ipc.dll",
            "shell/sdk/external_renderer_ipc.dll"
    );

    private MuMuNemuDiscovery() {}

    /**
     * Attempts to discover the Nemu IPC library from the configured MuMu executable or directory path.
     *
     * @param configuredPath configured path to MuMu installation, nx_main directory, or MuMuManager executable
     * @return resolved paths if available on Windows, or empty
     */
    public static Optional<NemuPaths> discover(Path configuredPath) {
        if (!isWindows() || configuredPath == null) {
            return Optional.empty();
        }

        Path resolvedRoot = resolveRoot(configuredPath);
        if (resolvedRoot == null || !Files.isDirectory(resolvedRoot)) {
            return Optional.empty();
        }

        for (String candidate : RELATIVE_DLL_CANDIDATES) {
            Path dllCandidate = resolvedRoot.resolve(candidate.replace('/', File.separatorChar));
            if (Files.isRegularFile(dllCandidate)) {
                List<Path> searchDirs = new ArrayList<>();
                searchDirs.add(dllCandidate.getParent());
                Path nxMain = resolvedRoot.resolve("nx_main");
                if (Files.isDirectory(nxMain)) {
                    searchDirs.add(nxMain);
                }
                searchDirs.add(resolvedRoot);
                return Optional.of(new NemuPaths(resolvedRoot, dllCandidate, searchDirs));
            }
        }

        return Optional.empty();
    }

    private static Path resolveRoot(Path path) {
        Path current = path;
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        if (current == null) {
            return null;
        }

        // If path is inside nx_main or nx_device, step up to find root
        if (current.getFileName() != null && "nx_main".equalsIgnoreCase(current.getFileName().toString())) {
            current = current.getParent();
        } else if (current.getParent() != null && current.getParent().getFileName() != null
                && "nx_device".equalsIgnoreCase(current.getParent().getFileName().toString())) {
            current = current.getParent().getParent();
        }
        return current;
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows");
    }
}
