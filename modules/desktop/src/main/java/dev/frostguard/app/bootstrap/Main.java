package dev.frostguard.app.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.api.runtime.WorkspaceSession.WorkspaceInUseException;
import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.tasks.TaskRegistrations;

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
        try {
            AnalyticsService.getInstance().trackAppLaunched(headless);
        } catch (Exception ignored) {
        }
    }

    static void closeWorkspace() {
        WorkspaceSession currentWorkspace = workspace;
        workspace = null;
        if (currentWorkspace != null) {
            currentWorkspace.close();
        }
    }
}
