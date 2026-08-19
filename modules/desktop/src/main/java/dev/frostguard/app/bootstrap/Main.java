package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.api.runtime.WorkspaceSession.WorkspaceInUseException;
import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.tasks.TaskRegistrations;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.PaddleModelDownloader;
import dev.frostguard.vision.ocr.PaddleOcrProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Main {
    private static volatile WorkspaceSession workspace;

    public static void main(String[] args) {
        StartupOptions options;
        WorkspacePaths paths;
        try {
            options = StartupOptions.parse(args);
            paths = options.resolveWorkspace();
            workspace = WorkspaceSession.open(paths);
        } catch (WorkspaceInUseException alreadyRunning) {
            reportWorkspaceInUse(alreadyRunning.paths(), hasHeadlessArgument(args));
            return;
        } catch (RuntimeException startupConfigurationFailure) {
            StartupFailureReporter.report("Frostguard could not start",
                    startupConfigurationFailure.getMessage(), hasHeadlessArgument(args));
            return;
        }

        configureLoggingNoise();
        Logger logger = LoggerFactory.getLogger(Main.class);
        try {
            if (options.nativeSmokeTest()) {
                runNativeSmokeTest();
                closeWorkspace();
                return;
            }

            logger.info("Initializing Frostguard with workspace {}", workspace.paths().root());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Closing down subsystems.");
                ApplicationLifecycle.runShutdownHook();
            }, "frostguard-shutdown"));

            if (options.headless()) {
                initializeRuntimeServices(true);
                logger.info("Headless application triggered.");
                HeadlessApp.start(args);
                Thread.currentThread().join();
            } else {
                FXApp.main(args);
            }
        } catch (Exception exception) {
            logger.error("Startup failure: ", exception);
            ApplicationLifecycle.exitNormally(1);
        }
    }

    private static void reportWorkspaceInUse(WorkspacePaths paths, boolean headless) {
        String workspaceName = paths.root().getFileName() == null
                ? paths.root().toString()
                : paths.root().getFileName().toString();
        String launcherName = paths.channel().productName() + ".exe";
        StartupFailureReporter.report(paths.channel().productName() + " is already running",
                paths.channel().productName() + " already owns workspace \"" + workspaceName + "\"."
                        + System.lineSeparator() + System.lineSeparator()
                        + "Close the running instance first, or use a separate workspace:"
                        + System.lineSeparator() + launcherName + " --workspace <name>",
                headless);
    }

    private static boolean hasHeadlessArgument(String[] args) {
        for (String argument : args) {
            if ("--headless".equalsIgnoreCase(argument)) {
                return true;
            }
        }
        return false;
    }

    private static void configureLoggingNoise() {
        System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
        java.util.logging.Logger.getLogger("javafx").setLevel(java.util.logging.Level.SEVERE);
        java.util.logging.Logger.getLogger("com.sun.javafx").setLevel(java.util.logging.Level.SEVERE);
        java.util.logging.Logger.getLogger("javax.swing").setLevel(java.util.logging.Level.SEVERE);
    }

    private static void runNativeSmokeTest() throws java.io.IOException {
        WorkspaceSession currentWorkspace = workspace;
        java.nio.file.Files.writeString(
                currentWorkspace.paths().cache().resolve("native-app-smoke.properties"),
                "channel=" + currentWorkspace.paths().channel().directoryName() + "\n"
                        + "applicationId="
                        + System.getProperty(WorkspacePaths.APPLICATION_ID_PROPERTY, "") + "\n"
                        + "workspace=" + currentWorkspace.paths().root() + "\n"
                        + "applicationDir=" + System.getProperty("user.dir") + "\n"
                        + "appLauncher=" + System.getProperty("jpackage.app-path", "") + "\n",
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Frostguard native launcher smoke test passed");
        System.out.println("channel=" + currentWorkspace.paths().channel().directoryName());
        System.out.println("workspace=" + currentWorkspace.paths().root());
        System.out.println("applicationDir=" + System.getProperty("user.dir"));
        System.out.println("pullRequestBuild="
                + System.getProperty("frostguard.update.pullRequestBuild", "false"));
    }

    static void initializeRuntimeServices(boolean headless) {
        ApplicationLifecycle.markRuntimeStarted();
        try {
            AnalyticsService.getInstance().initialize();
        } catch (Exception ignored) {
        }
        TaskRegistrations.initialize();
        initializePaddleIfRequested();
        try {
            AnalyticsService.getInstance().trackAppLaunched(headless);
        } catch (Exception ignored) {
        }
    }

    /**
     * Activates PaddleOCR when {@code -Dfrostguard.ocr.paddle=true} is set.
     *
     * <p>This is a developer-only activation path for Step 2 evaluation. Model
     * files are downloaded on first use to {@code <workspace>/cache/paddle/} —
     * the workspace cache directory is the correct mutable runtime location;
     * the application install directory is read-only at runtime.
     *
     * <p>The download and provider initialization run on a background daemon thread
     * so they do not block the JavaFX Application Thread or the headless startup
     * sequence. Tesseract remains active until the background thread succeeds.
     * If initialization fails, Tesseract continues to be used and the error is
     * logged at ERROR level.
     *
     * <p>Runtime provider selection via user-facing config is a Step 3 concern
     * and will be introduced only once Step 2 validation is complete.
     */
    private static void initializePaddleIfRequested() {
        if (!"true".equalsIgnoreCase(System.getProperty("frostguard.ocr.paddle"))) {
            return;
        }
        Logger log = LoggerFactory.getLogger(Main.class);
        WorkspaceSession currentWorkspace = workspace;
        if (currentWorkspace == null) {
            log.error("PaddleOCR requested but workspace is not open — skipping");
            return;
        }
        Path paddleDir = currentWorkspace.paths().cache().resolve("paddle");
        Thread bg = new Thread(() -> {
            try {
                PaddleModelDownloader.ensureModels(paddleDir);
                OcrEngine.setProvider(new PaddleOcrProvider(paddleDir));
                LoggerFactory.getLogger(Main.class).info("PaddleOCR provider ready");
            } catch (OcrException e) {
                LoggerFactory.getLogger(Main.class)
                        .error("PaddleOCR initialization failed — Tesseract remains active: {}",
                                e.getMessage());
            }
        }, "paddle-init");
        bg.setDaemon(true);
        bg.start();
    }

    static void closeWorkspace() {
        WorkspaceSession currentWorkspace = workspace;
        workspace = null;
        if (currentWorkspace != null) {
            currentWorkspace.close();
        }
    }
}
