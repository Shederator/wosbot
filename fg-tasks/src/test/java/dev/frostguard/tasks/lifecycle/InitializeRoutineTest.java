package dev.frostguard.tasks.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class InitializeRoutineTest {

    @Test
    void refreshesStaminaAfterCharacterVerificationBeforeMarchCapacityDetection() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setName("Test Profile");
        profile.setEmulatorNumber("1");

        TestableInitializeRoutine routine = new TestableInitializeRoutine(profile);
        routine.execute();

        assertTrue(routine.refreshInvoked, "Initialization should refresh stamina for the active profile");
        assertTrue(routine.refreshInvokedBeforeMarchDetection,
                "Initialization should refresh stamina before march-capacity detection runs");
    }

    private static final class TestableInitializeRoutine extends InitializeRoutine {
        private boolean refreshInvoked;
        private boolean refreshInvokedBeforeMarchDetection;

        private TestableInitializeRoutine(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        @Override
        protected void ensureEmulatorRunning() {
            // no-op for unit test
        }

        @Override
        protected void ensureGameInstalled() {
            // no-op for unit test
        }

        @Override
        protected void ensureGameRunning() {
            // no-op for unit test
        }

        @Override
        protected boolean waitForHomeScreen() {
            return true;
        }

        @Override
        protected boolean verifyAndSwitchCharacter() {
            return true;
        }

        @Override
        protected void detectAndPersistMarchCapacity() {
            refreshInvokedBeforeMarchDetection = refreshInvoked;
        }

        @Override
        protected void refreshStaminaForCurrentProfile() {
            refreshInvoked = true;
        }

        @Override
        protected void handleInitializationSuccess() {
            // no-op for unit test
        }
    }
}
