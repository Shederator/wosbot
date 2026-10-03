package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.engine.schedule.BearFlagConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BearFlagConfigurationTest {

    @Test
    void theShippedDefaultsAreRefusedInsteadOfSilentlyDisablingJoins() {
        // Own rally and joins both default to formation 1.
        BearFlagConfiguration.Validation result = BearFlagConfiguration.validate(
                true, 1, true, BearTrapRoutine.decodeJoinFlags("1"));

        assertTrue(result.refusal().isPresent());
        assertTrue(result.refusal().orElseThrow().contains("formation 1"),
                "the refusal names the conflicting formation: " + result.refusal());
    }

    @Test
    void anEmptyOrUnreadableJoinListIsRefusedWhenJoiningIsEnabled() {
        assertTrue(BearFlagConfiguration.validate(
                false, 1, true, BearTrapRoutine.decodeJoinFlags("")).refusal().isPresent());
        assertTrue(BearFlagConfiguration.validate(
                false, 1, true, BearTrapRoutine.decodeJoinFlags("bad,13")).refusal().isPresent());
    }

    @Test
    void distinctOwnAndJoinFormationsAreAccepted() {
        BearFlagConfiguration.Validation result = BearFlagConfiguration.validate(
                true, 1, true, BearTrapRoutine.decodeJoinFlags("2,3,4"));

        assertTrue(result.refusal().isEmpty());
        assertEquals(List.of(2, 3, 4), result.joinFlags());
    }

    @Test
    void joinFlagsAreIrrelevantWhenJoiningIsDisabled() {
        assertTrue(BearFlagConfiguration.validate(
                true, 1, false, BearTrapRoutine.decodeJoinFlags("1")).refusal().isEmpty());
    }
}
