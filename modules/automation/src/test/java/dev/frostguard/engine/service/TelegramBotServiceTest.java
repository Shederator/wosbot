package dev.frostguard.engine.service;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBotServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void standardQueueExcludesCustomTaskPlaceholder() {
        assertFalse(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.CUSTOM_TASK));
        assertTrue(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.INITIALIZE));
    }

    @Test
    void logDownloadUsesTheSelectedWorkspace() {
        String previous = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        try {
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
            assertEquals(tempDir.resolve("logs/frostguard.log").toAbsolutePath(),
                    TelegramBotService.currentLogPath());
        } finally {
            if (previous == null) {
                System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            } else {
                System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previous);
            }
        }
    }
}
