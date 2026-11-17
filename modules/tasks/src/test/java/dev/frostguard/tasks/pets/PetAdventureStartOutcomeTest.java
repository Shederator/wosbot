package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.schedule.TaskOutcome;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.StaminaService;

class PetAdventureStartOutcomeTest {

    @BeforeAll
    static void initializeWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void unconfirmedStartPreservesStaminaAndDefersTheObserveOnlyVisit() throws Exception {
        var profile = new AccountDescriptor(null, "Synthetic pet " + UUID.randomUUID(), "0", false, 100L, 30L);
        assertTrue(ProfileService.obtain().createAccount(profile));
        StaminaService stamina = StaminaService.getServices();
        stamina.setStamina(profile.getId(), 80);
        RawImageData frame = new RawImageData() {
            @Override
            public boolean isValid() {
                return false;
            }
        };
        frame.setFrameBytes(new byte[16]);
        frame.setFrameWidth(2);
        frame.setFrameHeight(2);
        frame.setColorDepth(32);
        var routine = new PetAdventureChestRoutine(profile, TpDailyTaskEnum.PET_ADVENTURE) {
            @Override
            RawImageData captureStartDiagnosticFrame() {
                return frame;
            }
        };
        var key = ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL;
        var config = ConfigService.obtain();
        String original = config.loadGlobalSettings().getOrDefault(key.name(), key.getDefaultValue());
        Path snapshotDirectory = WorkspacePaths.current().root().resolve("logs/snapshot/petadventurechest");
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        assertTrue(config.writeGlobalSetting(key, "true"));
        try {
            List<Path> before = snapshotFiles(snapshotDirectory);
            routine.recordUnconfirmedStart(now);
            assertEquals(TaskOutcome.UNKNOWN, routine.getExecutionOutcome());
            assertEquals(now.plusHours(2), routine.getScheduled());
            assertEquals(80, stamina.getCurrentStamina(profile.getId()));
            Path retained = snapshotFiles(snapshotDirectory).stream()
                    .filter(file -> !before.contains(file) && file.getFileName().toString().contains("start-outcome"))
                    .findFirst().orElseThrow();
            var image = javax.imageio.ImageIO.read(retained.toFile());
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());

            assertTrue(routine.deferAfterObservation(now.plusHours(2)));
            assertEquals(TaskOutcome.DEFERRED, routine.getExecutionOutcome());
            assertEquals(now.plusHours(4), routine.getScheduled());
            assertFalse(routine.deferAfterObservation(now.plusHours(4)), "observe-only applies to one visit");
        } finally {
            config.writeGlobalSetting(key, original);
        }
    }

    private static List<Path> snapshotFiles(Path directory) throws java.io.IOException {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.toList();
        }
    }

    @Test
    void rechecksAnUnconfirmedStartOnTheCompletionIntervalInsteadOfTheShortRetry() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);

        assertEquals(now.plusHours(2), PetAdventureChestRoutine.unverifiedStartRecheck(now));
    }

    @Test
    void treatsATimerUnderThePinAsAnOccupiedChest() {
        PointData chest = new PointData(180, 360);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertTrue(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }

    @Test
    void leavesAChestIdleWhenTheTimerBelongsToAnotherPin() {
        PointData chest = new PointData(600, 700);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertFalse(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }
}
