package dev.frostguard.engine.emulator;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages on-demand {@code scrcpy} viewer processes for operators.
 * Enables live inspection and manual interaction with headless or background
 * emulator instances without altering the emulator's internal state.
 */
public class ScrcpyViewerLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(ScrcpyViewerLauncher.class);
    private static final int DEFAULT_VIEWER_WIDTH = 360;
    private static final int DEFAULT_VIEWER_HEIGHT = 640;

    private static final ScrcpyViewerLauncher INSTANCE = new ScrcpyViewerLauncher();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::stopAll, "scrcpy-viewer-cleanup"));
    }

    private final ConcurrentHashMap<String, Process> activeViewers = new ConcurrentHashMap<>();

    public static ScrcpyViewerLauncher getInstance() {
        return INSTANCE;
    }

    public static boolean isAvailable() {
        return findExecutable().isPresent();
    }

    /**
     * Resolves the {@code scrcpy} executable by querying configured override paths,
     * the system {@code PATH}, standard WinGet package directories, and local tools directories.
     */
    public static Optional<Path> findExecutable() {
        // 1. Configured custom path
        try {
            Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
            if (cfg != null) {
                String custom = cfg.get(ConfigurationKeyEnum.SCRCPY_PATH_STRING.name());
                if (custom != null && !custom.isBlank()) {
                    Path p = Path.of(custom.trim());
                    if (Files.isRegularFile(p)) {
                        return Optional.of(p.toAbsolutePath());
                    }
                }
            }
        } catch (Exception ignored) { }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exeName = isWindows ? "scrcpy.exe" : "scrcpy";

        // 2. Local tools directory
        Path localTool = Path.of("tools", "scrcpy", exeName);
        if (Files.isRegularFile(localTool)) {
            return Optional.of(localTool.toAbsolutePath());
        }

        // 3. Search system PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(PatternQuote(File.pathSeparator))) {
                if (entry.isBlank()) continue;
                String cleanEntry = entry.trim().replaceAll("^\"|\"$", "");
                try {
                    Path cand = Path.of(cleanEntry, exeName);
                    if (Files.isRegularFile(cand)) {
                        return Optional.of(cand.toAbsolutePath());
                    }
                } catch (Exception ignored) { }
            }
        }

        // 4. Standard Windows WinGet and ProgramFiles locations
        if (isWindows) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                Path wingetDir = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
                if (Files.isDirectory(wingetDir)) {
                    try (var stream = Files.walk(wingetDir, 4)) {
                        Optional<Path> found = stream
                                .filter(p -> p.getFileName().toString().equalsIgnoreCase("scrcpy.exe"))
                                .findFirst();
                        if (found.isPresent()) {
                            return found.map(Path::toAbsolutePath);
                        }
                    } catch (IOException ignored) { }
                }

                Path progDir = Path.of(localAppData, "Programs", "scrcpy", "scrcpy.exe");
                if (Files.isRegularFile(progDir)) {
                    return Optional.of(progDir.toAbsolutePath());
                }
            }

            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null && !programFiles.isBlank()) {
                Path pfDir = Path.of(programFiles, "scrcpy", "scrcpy.exe");
                if (Files.isRegularFile(pfDir)) {
                    return Optional.of(pfDir.toAbsolutePath());
                }
            }
        }

        return Optional.empty();
    }

    private static String PatternQuote(String s) {
        return java.util.regex.Pattern.quote(s);
    }

    public static List<String> buildViewerArguments(String executable, String serial, String title,
                                                    int width, int height) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-s");
        cmd.add(serial);
        cmd.add("--window-title=" + title);
        cmd.add("--no-audio");
        cmd.add("--window-width=" + width);
        cmd.add("--window-height=" + height);
        cmd.add("--stay-awake");
        return List.copyOf(cmd);
    }

    public Process launchViewer(String serial, String title) throws IOException {
        return launchViewer(serial, title, DEFAULT_VIEWER_WIDTH, DEFAULT_VIEWER_HEIGHT);
    }

    public synchronized Process launchViewer(String serial, String title, int width, int height) throws IOException {
        Process existing = activeViewers.get(serial);
        if (existing != null && existing.isAlive()) {
            LOG.info("Scrcpy viewer already running for {}", serial);
            return existing;
        }

        Path exe = findExecutable().orElseThrow(() ->
                new IllegalStateException("scrcpy executable not found on system"));

        List<String> cmd = buildViewerArguments(exe.toString(), serial, title, width, height);
        LOG.info("Launching scrcpy viewer for {}: {}", serial, String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();
        activeViewers.put(serial, process);

        // Clean up map when process exits naturally
        process.onExit().thenAccept(p -> activeViewers.remove(serial, p));
        return process;
    }

    public boolean isViewerRunning(String serial) {
        Process p = activeViewers.get(serial);
        return p != null && p.isAlive();
    }

    public synchronized void stopViewer(String serial) {
        Process p = activeViewers.remove(serial);
        if (p != null && p.isAlive()) {
            LOG.info("Stopping scrcpy viewer for {}", serial);
            p.destroy();
            try {
                if (!p.waitFor(1, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void stopAll() {
        for (String serial : List.copyOf(activeViewers.keySet())) {
            stopViewer(serial);
        }
    }
}
