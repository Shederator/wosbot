package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelayedTaskScreenHooksTest {

    @Test
    void runDelegatesScreenEntryAndExitToOverridableHooks() {
        List<String> calls = new ArrayList<>();
        AccountDescriptor profile = new AccountDescriptor(990_001L, "Screen hooks", "0", true, 100L, 30L);
        DelayedTask task = new DelayedTask(profile, TpDailyTaskEnum.ALLIANCE_CHESTS) {
            @Override
            protected void enterStartScreen(boolean profileSwitchedOnEmulator) {
                calls.add("enter");
            }

            @Override
            protected void execute() {
                calls.add("execute");
            }

            @Override
            protected void leaveScreen() {
                calls.add("leave");
            }
        };

        task.run();

        assertEquals(List.of("enter", "execute", "leave"), calls,
                "a task that owns its screen interaction must not inherit blind navigation");
    }
}
