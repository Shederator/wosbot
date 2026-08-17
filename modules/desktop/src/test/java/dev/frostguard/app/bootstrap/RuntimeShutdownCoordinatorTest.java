package dev.frostguard.app.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeShutdownCoordinatorTest {
    @Test
    void runsEveryStepInOrder() throws Exception {
        List<String> calls = new ArrayList<>();
        RuntimeShutdownCoordinator coordinator = new RuntimeShutdownCoordinator(List.of(
                new RuntimeShutdownCoordinator.Step("scheduler", () -> calls.add("scheduler")),
                new RuntimeShutdownCoordinator.Step("database", () -> calls.add("database"))));

        coordinator.shutdown();

        assertEquals(List.of("scheduler", "database"), calls);
    }

    @Test
    void reportsFailuresAfterAttemptingRemainingSteps() {
        List<String> calls = new ArrayList<>();
        RuntimeShutdownCoordinator coordinator = new RuntimeShutdownCoordinator(List.of(
                new RuntimeShutdownCoordinator.Step("scheduler", () -> {
                    calls.add("scheduler");
                    throw new IllegalStateException("busy");
                }),
                new RuntimeShutdownCoordinator.Step("database", () -> calls.add("database"))));

        RuntimeShutdownCoordinator.ShutdownException exception = assertThrows(
                RuntimeShutdownCoordinator.ShutdownException.class, coordinator::shutdown);

        assertEquals(List.of("scheduler", "database"), calls);
        assertEquals(List.of("scheduler: busy"), exception.failures());
    }
}
