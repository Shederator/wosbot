package dev.frostguard.engine.service;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.LogMessageData;
import dev.frostguard.engine.listener.LogListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingServiceObserverTest {

    @Test
    void additionalObserverReceivesLogsWithoutReplacingConsoleObserver() {
        LoggingService service = LoggingService.obtain();
        List<LogMessageData> consoleLogs = new ArrayList<>();
        List<LogMessageData> workbenchLogs = new ArrayList<>();
        LogListener workbench = workbenchLogs::add;
        service.attachObserver(consoleLogs::add);
        service.addObserver(workbench);

        try {
            service.emit(TpMessageSeverityEnum.INFO, "Task", "Profile", "message");

            assertEquals(1, consoleLogs.size());
            assertEquals(1, workbenchLogs.size());
        } finally {
            service.removeObserver(workbench);
            service.attachObserver(null);
        }
    }
}
