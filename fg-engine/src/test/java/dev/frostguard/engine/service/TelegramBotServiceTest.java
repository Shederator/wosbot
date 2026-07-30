package dev.frostguard.engine.service;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBotServiceTest {

    @Test
    void standardQueueExcludesCustomTaskPlaceholder() {
        assertFalse(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.CUSTOM_TASK));
        assertTrue(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.INITIALIZE));
    }

    @Test
    void logDownloadUsesConfiguredFrostguardLogName() {
        assertEquals("log/frostguard.log", TelegramBotService.CURRENT_LOG_PATH);
    }
}
