package dev.frostguard.vision.logging;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileContextLoggerCaptureTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesOnlyMatchingProfileOnExecutionThreadAndStopsAfterScope() throws InterruptedException {
        String previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AccountDescriptor selected = new AccountDescriptor(90001L, "Selected", "1", true, 1L, 30L);
            AccountDescriptor other = new AccountDescriptor(90002L, "Other", "2", true, 2L, 30L);
            ProfileContextLogger selectedLog = new ProfileContextLogger(getClass(), selected);
            ProfileContextLogger otherLog = new ProfileContextLogger(getClass(), other);
            List<String> captured = new ArrayList<>();

            try (ProfileContextLogger.CaptureScope capture = ProfileContextLogger.captureCurrentThread(
                    selected.getId(), captured::add)) {
                selectedLog.info("first graph line");
                otherLog.info("other profile line");
                Thread unrelatedThread = new Thread(() -> selectedLog.info("concurrent profile line"));
                unrelatedThread.start();
                unrelatedThread.join();
                selectedLog.debug("file-only debug line");
            }
            selectedLog.info("after graph line");

            assertEquals(2, captured.size());
            assertTrue(captured.get(0).contains("first graph line"));
            assertTrue(captured.get(1).contains("file-only debug line"));
        } finally {
            ProfileContextLogger.shutdown();
            if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
        }
    }

    @Test
    void failingObserverCannotInterruptLoggingAndExceptionalScopeStillCloses() {
        String previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
        try {
            AccountDescriptor profile = new AccountDescriptor(90004L, "Selected", "1", true, 1L, 30L);
            ProfileContextLogger log = new ProfileContextLogger(getClass(), profile);
            AtomicInteger calls = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> {
                try (ProfileContextLogger.CaptureScope capture = ProfileContextLogger.captureCurrentThread(
                        profile.getId(), line -> {
                            calls.incrementAndGet();
                            throw new IllegalArgumentException("UI closed");
                        })) {
                    assertDoesNotThrow(() -> log.info("execution continues"));
                    throw new IllegalStateException("graph failed");
                }
            });
            assertDoesNotThrow(() -> log.info("after failure"));
            assertEquals(1, calls.get());
        } finally {
            ProfileContextLogger.shutdown();
            if (previousWorkspace == null) System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            else System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
        }
    }
}
