package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.frostguard.engine.error.BearSessionExecutionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BearPreparationSequenceTest {
    @Test
    void missingRecallAndAutoJoinEvidenceDoesNotSkipPetsOrNavigation() {
        List<String> completed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BearPreparationSequence.run(List.of(
                () -> { throw unavailable("disable-autojoin"); },
                () -> { throw unavailable("recall-troops"); },
                () -> completed.add("pets"),
                () -> completed.add("navigation")), missing::add);
        assertEquals(List.of("disable-autojoin", "recall-troops"), missing);
        assertEquals(List.of("pets", "navigation"), completed);
    }

    @Test
    void uncertainPetPostconditionDoesNotPermitFollowingNavigation() {
        var failure = new BearSessionExecutionException(
                BearSessionExecutionException.FailureKind.VISUAL_UNKNOWN,
                BearSessionExecutionException.RecoveryDirective.DEGRADED_WAIT,
                "1", "pet-battle-skills-postcondition-not-verified", "test", null);
        assertAborts(failure);
    }

    @Test
    void unrelatedConfigurationAndDeviceFailuresAreNotSkipped() {
        assertAborts(unavailable("start-own-rally"));
        assertAborts(new BearSessionExecutionException(
                BearSessionExecutionException.FailureKind.DEVICE_OFFLINE,
                BearSessionExecutionException.RecoveryDirective.REBIND_DEVICE,
                "1", "recall-troops", "test", null));
    }

    @Test
    void cancellationPropagatesImmediately() {
        var failure = new IllegalStateException("cancelled");
        assertAborts(failure);
    }

    private void assertAborts(RuntimeException failure) {
        List<String> called = new ArrayList<>();
        assertSame(failure, assertThrows(RuntimeException.class, () -> BearPreparationSequence.run(
                List.of(() -> { throw failure; }, () -> called.add("next")), called::add)));
        assertTrue(called.isEmpty());
    }

    private static BearSessionExecutionException unavailable(String operation) {
        return new BearSessionExecutionException(
                BearSessionExecutionException.FailureKind.FATAL_CONFIGURATION,
                BearSessionExecutionException.RecoveryDirective.OPERATOR_ACTION,
                "1", operation, "test", null);
    }
}
