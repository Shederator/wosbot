package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.error.TaskPreemptedException;
import dev.frostguard.engine.schedule.LaunchPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationRecoveryCheckpointTest {
    @Test
    void preemptedRecoveryYieldsBeforeTouchingTheEmulator() {
        var navigation = new NavigationHelper(null, "test",
                new AccountDescriptor(999L, "Synthetic recovery", "test", true, 0L, 0L));
        var preemption = TaskPreemptedException.because("higher priority work");
        assertSame(preemption, assertThrows(TaskPreemptedException.class,
                () -> navigation.ensureCorrectScreenLocation(LaunchPoint.ANY, () -> { throw preemption; })));
    }
}
