package dev.frostguard.tasks;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.schedule.TaskRegistration;
import dev.frostguard.tasks.economy.NomadicMerchantRoutine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRegistrationsTest {

    private static final Set<TpDailyTaskEnum> EXCLUDED = Set.of(
            TpDailyTaskEnum.CUSTOM_TASK,
            TpDailyTaskEnum.GAME_ANALYTICS_LABYRINTH,
            TpDailyTaskEnum.GAME_ANALYTICS_POWER,
            TpDailyTaskEnum.INITIALIZE,
            TpDailyTaskEnum.SKIP_TUTORIAL,
            TpDailyTaskEnum.CREATE_CHARACTER,
            TpDailyTaskEnum.DUMMY_TASK,
            TpDailyTaskEnum.TEST_HOOK_LOOP);

    @BeforeAll
    static void initializeRegistry() throws IOException {
        Files.createDirectories(Path.of("target", "test-workspace"));
        TaskRegistrations.initialize();
    }

    @Test
    void workbenchCatalogContainsOperationalTasksOnly() {
        List<TaskRegistration> registrations = DelayedTaskRegistry.getWorkbenchTasks();
        Set<TpDailyTaskEnum> registeredTypes = registrations.stream()
                .map(TaskRegistration::taskType)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(TpDailyTaskEnum.values().length - EXCLUDED.size(), registrations.size());
        assertEquals(registrations.size(), registeredTypes.size());
        assertTrue(registeredTypes.contains(TpDailyTaskEnum.GATHER_RESOURCES));
        assertTrue(registeredTypes.contains(TpDailyTaskEnum.GATHER_BOOST));
        assertTrue(EXCLUDED.stream().noneMatch(registeredTypes::contains));
    }

    @Test
    void nomadicMerchantIsTheOnlyInitialStepAwareTask() {
        List<TaskRegistration> stepAware = DelayedTaskRegistry.getWorkbenchTasks().stream()
                .filter(registration -> registration.controlledExecutionCapability()
                        == ControlledExecutionCapability.STEP_AWARE)
                .toList();

        assertEquals(1, stepAware.size());
        assertEquals(TpDailyTaskEnum.NOMADIC_MERCHANT, stepAware.getFirst().taskType());
        assertFalse(DelayedTaskRegistry.getWorkbenchTasks().isEmpty());
    }

    @Test
    void schedulerFactoryStillCreatesNomadicMerchant() {
        AccountDescriptor profile = new AccountDescriptor(
                9_090_090L, "Workbench", "0", true, 1L, 30L);

        DelayedTask task = DelayedTaskRegistry.create(TpDailyTaskEnum.NOMADIC_MERCHANT, profile);

        assertInstanceOf(NomadicMerchantRoutine.class, task);
    }
}
