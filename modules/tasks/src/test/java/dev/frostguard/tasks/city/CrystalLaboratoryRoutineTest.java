package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.runtime.WorkspacePaths;

class CrystalLaboratoryRoutineTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 9, 0);
    private static final LocalDateTime DAILY_RESET = LocalDateTime.of(2026, 9, 13, 0, 0);

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void retriesScreenValidationWhenTheFirstFrameMisses() {
        TestRoutine routine = new TestRoutine(false, true);

        assertTrue(routine.reachCrystalLaboratory());
        assertEquals(1, routine.sidebarNavigationAttempts);
        assertEquals(1, routine.crystalEntryAttempts);
        assertEquals(2, routine.screenValidationAttempts);
        assertEquals(0, routine.recoveryAttempts);
    }

    @Test
    void recoversAndRetriesSidebarNavigationAfterPersistentValidationMisses() {
        TestRoutine routine = new TestRoutine(false, false, false, false, false, false);

        assertFalse(routine.reachCrystalLaboratory());
        assertEquals(2, routine.sidebarNavigationAttempts);
        assertEquals(2, routine.crystalEntryAttempts);
        assertEquals(6, routine.screenValidationAttempts);
        assertEquals(1, routine.recoveryAttempts);
    }

    @Test
    void retriesWithoutValidationWhenTheCrystalBuildingCannotBeOpened() {
        TestRoutine routine = new TestRoutine();
        routine.crystalEntrySucceeds = false;

        assertFalse(routine.reachCrystalLaboratory());
        assertEquals(2, routine.sidebarNavigationAttempts);
        assertEquals(2, routine.crystalEntryAttempts);
        assertEquals(0, routine.screenValidationAttempts);
        assertEquals(1, routine.recoveryAttempts);
    }

    @Test
    void executeBacksOffConsecutiveFailuresAndResetsAfterSuccess() {
        TestRoutine routine = new TestRoutine();
        routine.navigationSucceeds = false;

        routine.execute();
        routine.execute();
        routine.execute();

        assertEquals(List.of(NOW.plusMinutes(5), NOW.plusMinutes(30), DAILY_RESET),
                routine.scheduledTimes);

        routine.navigationSucceeds = true;
        routine.execute();
        routine.navigationSucceeds = false;
        routine.execute();

        assertEquals(NOW.plusMinutes(5), routine.scheduledTimes.get(4));
    }

    @Test
    void reportsZeroCompletedAndSafetyLimitedClaimOutcomesAccurately() {
        TestRoutine routine = new TestRoutine();

        routine.claimResult = new CrystalClaimLoop.Result(0, 3, false);
        routine.redeemAllCrystals();
        routine.claimResult = new CrystalClaimLoop.Result(2, 3, false);
        routine.redeemAllCrystals();
        routine.claimResult = new CrystalClaimLoop.Result(25, 0, true);
        routine.redeemAllCrystals();

        assertTrue(routine.infoMessages.stream()
                .anyMatch(message -> message.contains("Zero crystals available after 3 checks.")));
        assertTrue(routine.infoMessages.stream()
                .anyMatch(message -> message.contains("completed after 2 claim(s) and 3 final misses.")));
        assertTrue(routine.warningMessages.stream()
                .anyMatch(message -> message.contains("stopped at the safety limit after 25 claim(s).")));
    }

    @Test
    void purchasesDiscountedRfcWhenTheOfferAndRefineButtonArePresent() {
        TestRoutine routine = new TestRoutine();
        routine.discountedOfferFound = true;
        routine.refineButtonFound = true;

        routine.purchaseDiscountedRFCFlow();

        assertEquals(1, routine.discountedOfferSearches);
        assertEquals(1, routine.refineButtonSearches);
        assertEquals(1, routine.discountedRfcTaps);
        assertTrue(routine.infoMessages.stream()
                .anyMatch(message -> message.contains("Discounted RFC purchased finished cleanly.")));
    }

    private static final class TestRoutine extends CrystalLaboratoryRoutine {
        private final Queue<Boolean> validationResults;
        private final List<LocalDateTime> scheduledTimes = new ArrayList<>();
        private final List<String> infoMessages = new ArrayList<>();
        private final List<String> warningMessages = new ArrayList<>();
        private boolean navigationSucceeds = true;
        private boolean crystalEntrySucceeds = true;
        private int sidebarNavigationAttempts;
        private int crystalEntryAttempts;
        private int screenValidationAttempts;
        private int recoveryAttempts;
        private boolean discountedOfferFound;
        private boolean refineButtonFound;
        private int discountedOfferSearches;
        private int refineButtonSearches;
        private int discountedRfcTaps;
        private CrystalClaimLoop.Result claimResult = new CrystalClaimLoop.Result(0, 3, false);

        private TestRoutine(Boolean... validationResults) {
            super(new AccountDescriptor(1L, "Test", "1", true, 1L, 30L),
                    TpDailyTaskEnum.CRYSTAL_LABORATORY);
            this.validationResults = new ArrayDeque<>(Arrays.asList(validationResults));
        }

        @Override
        protected void loadConfiguration() {
            // Defaults keep optional RFC purchasing disabled.
        }

        @Override
        boolean navigateToCrystalLaboratoryViaSidebar() {
            sidebarNavigationAttempts++;
            return navigationSucceeds;
        }

        @Override
        boolean openCrystalLaboratoryFromCity() {
            crystalEntryAttempts++;
            return crystalEntrySucceeds;
        }

        @Override
        boolean isCrystalLabInterfaceVisible() {
            screenValidationAttempts++;
            return validationResults.isEmpty() || validationResults.remove();
        }

        @Override
        void recoverCrystalLaboratoryNavigation() {
            recoveryAttempts++;
        }

        @Override
        LocalDateTime currentTime() {
            return NOW;
        }

        @Override
        LocalDateTime dailyResetTime() {
            return DAILY_RESET;
        }

        @Override
        CrystalClaimLoop.Result performCrystalClaimLoop() {
            return claimResult;
        }

        @Override
        ImageSearchResultData locateDailyDiscountedRfc() {
            discountedOfferSearches++;
            return imageSearchResult(discountedOfferFound);
        }

        @Override
        ImageSearchResultData locateRfcRefineButton() {
            refineButtonSearches++;
            return imageSearchResult(refineButtonFound);
        }

        @Override
        void tapDiscountedRfc(ImageSearchResultData refineResult) {
            discountedRfcTaps++;
        }

        @Override
        protected void sleepTask(long millis) {
            // Keep bounded retry verification deterministic and fast.
        }

        @Override
        public void reschedule(LocalDateTime rescheduledTime) {
            scheduledTimes.add(rescheduledTime);
        }

        @Override
        public void logInfo(String message) {
            infoMessages.add(message);
        }

        @Override
        public void logWarning(String message) {
            warningMessages.add(message);
        }

        private ImageSearchResultData imageSearchResult(boolean found) {
            return new ImageSearchResultData(
                    found,
                    found ? new PointData(360, 650) : null,
                    found ? 100.0 : 0.0);
        }
    }
}
