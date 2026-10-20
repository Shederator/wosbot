package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.error.HomeNotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DelayedTaskPreparationTest {
    @Test
    void ordinaryTaskStillPreparesBeforeExecuting() {
        var task = new TestTask();
        task.executeWithPreparation(() -> task.steps.add("prepare"));
        assertEquals(List.of("prepare", "execute"), task.steps);
    }

    @Test
    void ordinaryPreparationFailureDoesNotExecuteOrGetSwallowed() {
        var task = new TestTask();
        assertThrows(HomeNotFoundException.class, () -> task.executeWithPreparation(() -> {
            throw new HomeNotFoundException("startup");
        }));
        assertTrue(task.steps.isEmpty());
    }

    private static class TestTask extends DelayedTask {
        final List<String> steps = new ArrayList<>();
        TestTask() { super(profile(), TpDailyTaskEnum.BEAR_TRAP); }
        private static AccountDescriptor profile() {
            var profile = new AccountDescriptor(1L);
            profile.setEmulatorNumber("test");
            return profile;
        }
        @Override protected void execute() { steps.add("execute"); }
    }
}
