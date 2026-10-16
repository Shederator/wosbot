package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;

class ScheduleServiceTelemetryTest {

    @Test
    void telemetryIsAnAlwaysOnBuiltInTask() {
        assertEquals(List.of(TpDailyTaskEnum.TELEMETRY_SNAPSHOT), ScheduleService.alwaysOnTaskTypes());
    }
}
