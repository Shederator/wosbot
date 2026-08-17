package dev.frostguard.app.bootstrap;

import dev.frostguard.app.panel.misc.GiftCodeAutomationService;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TelegramBotService;
import dev.frostguard.vision.logging.ProfileContextLogger;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApplicationLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationLifecycle.class);
    private static final AtomicBoolean SHUTDOWN_ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean RUNTIME_STARTED = new AtomicBoolean();
    private static final RuntimeShutdownCoordinator COORDINATOR = new RuntimeShutdownCoordinator(List.of(
            runtimeStep("scheduler", () -> ScheduleService.obtain().haltEngine()),
            runtimeStep("gift code automation",
                    () -> GiftCodeAutomationService.getInstance().shutdown()),
            runtimeStep("Telegram command server",
                    () -> TelegramBotService.getInstance().stop()),
            runtimeStep("custom task loader", () -> CustomTaskService.getInstance().shutdown()),
            runtimeStep("analytics", () -> AnalyticsService.getInstance().trackAppShutdown()),
            new RuntimeShutdownCoordinator.Step("persistence", DataStore::closeIfInitialized),
            runtimeStep("ADB", ApplicationLifecycle::terminateAdbProcess),
            runtimeStep("profile logging", ProfileContextLogger::shutdown),
            new RuntimeShutdownCoordinator.Step("workspace", Main::closeWorkspace)
    ));

    private ApplicationLifecycle() {
    }

    static void markRuntimeStarted() {
        RUNTIME_STARTED.set(true);
    }

    private static RuntimeShutdownCoordinator.Step runtimeStep(
            String name, RuntimeShutdownCoordinator.ShutdownAction action) {
        return new RuntimeShutdownCoordinator.Step(name, () -> {
            if (RUNTIME_STARTED.get()) {
                action.run();
            }
        });
    }

    public static void stopForUpdate() throws LifecycleException {
        if (!SHUTDOWN_ACTIVE.compareAndSet(false, true)) {
            throw new LifecycleException("Another shutdown is already active");
        }
        try {
            COORDINATOR.shutdown();
        } catch (RuntimeShutdownCoordinator.ShutdownException exception) {
            LOG.error("Runtime shutdown for update was incomplete; cancelling installation and exiting: {}",
                    exception.getMessage());
            throw new LifecycleException(exception.getMessage(), exception);
        }
    }

    public static void exitNormally(int status) {
        if (SHUTDOWN_ACTIVE.compareAndSet(false, true)) {
            try {
                COORDINATOR.shutdown();
            } catch (RuntimeShutdownCoordinator.ShutdownException exception) {
                LOG.warn("Runtime shutdown completed with errors: {}", exception.getMessage());
            }
        }
        Platform.exit();
        System.exit(status);
    }

    public static void exitAfterUpdateHandoff() {
        Platform.exit();
        System.exit(0);
    }

    public static void exitAfterCancelledUpdate() {
        Platform.exit();
        System.exit(1);
    }

    static void runShutdownHook() {
        if (!SHUTDOWN_ACTIVE.compareAndSet(false, true)) {
            return;
        }
        try {
            COORDINATOR.shutdown();
        } catch (RuntimeShutdownCoordinator.ShutdownException exception) {
            LOG.warn("Shutdown hook completed with errors: {}", exception.getMessage());
        }
    }

    private static void terminateAdbProcess() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return;
        }
        new ProcessBuilder("taskkill", "/F", "/IM", "adb.exe").start();
        LOG.info("adb.exe shutdown requested");
    }

    public static final class LifecycleException extends Exception {
        LifecycleException(String message) {
            super(message);
        }

        LifecycleException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
