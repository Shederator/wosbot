package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Drives the real session driver with scripted frames and screens; no device is involved. */
class BearSessionDriverTest {

    private static final RawImageData FRAME = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);
    private static final Set<TemplatesEnum> UNKNOWN = EnumSet.noneOf(TemplatesEnum.class);
    private static final Set<TemplatesEnum> WORLD = EnumSet.of(TemplatesEnum.GAME_HOME_WORLD);
    private static final Set<TemplatesEnum> WORLD_WITH_BEAR = EnumSet.of(
            TemplatesEnum.GAME_HOME_WORLD, TemplatesEnum.BEAR_HUNT_IS_RUNNING);

    @BeforeAll
    static void initialize() throws IOException {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError alreadyLoaded) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void activeRecoveryClosesEachRecordedPetPanelWithoutUsingSkills() throws Exception {
        for (String name : new String[]{"pet-panel", "pet-selected", "pet-active"}) {
            Set<TemplatesEnum> signals = EnumSet.of(TemplatesEnum.BEAR_PET_TITLE);
            if (!name.equals("pet-panel")) signals.add(TemplatesEnum.BEAR_PET_BATTLE_NAME);
            if (name.equals("pet-selected")) signals.add(TemplatesEnum.BEAR_PET_QUICK_USE);
            if (name.equals("pet-active")) signals.add(TemplatesEnum.BEAR_PET_ACTIVE);
            ScriptedBear bear = new ScriptedBear(signals);
            bear.captured = BearLiveFrameReplayTest.frame(name);
            var driver = bear.driver();
            bear.becomeAfterNextClassification(WORLD_WITH_BEAR);
            assertTrue(driver.recover(BearSessionCoordinator.State.OWN_RALLY_REQUIRED), name);
            assertEquals(1, bear.inputs, name);
            assertEquals(0, bear.backs, name);
        }
    }

    @Test
    void activeRecoveryCancelsPetConfirmationInsteadOfSendingUse() throws Exception {
        ScriptedBear bear = new ScriptedBear(EnumSet.of(
                TemplatesEnum.BEAR_PET_CONFIRMATION, TemplatesEnum.BEAR_PET_CONFIRM_USE));
        bear.captured = BearLiveFrameReplayTest.frame("pet-confirmation");
        var driver = bear.driver();
        bear.becomeAfterNextClassification(EnumSet.of(TemplatesEnum.BEAR_PET_TITLE,
                TemplatesEnum.BEAR_PET_BATTLE_NAME, TemplatesEnum.BEAR_PET_QUICK_USE));
        assertTrue(driver.recover(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        assertEquals(1, bear.inputs);
        assertTrue(bear.lastTap.getPoint().getX() < 350, "recovery must target Cancel, not Use");
    }

    @Test
    void configuredTrapOnCooldownRefusesOwnRallyWithoutInput() throws Exception {
        ScriptedBear bear = new ScriptedBear(EnumSet.of(TemplatesEnum.BEAR_TERRITORY_TITLE,
                TemplatesEnum.BEAR_SPECIAL_TAB, TemplatesEnum.BEAR_TRAP_2_TITLE,
                TemplatesEnum.BEAR_TRAP_COOLDOWN));
        // Normal execution loads this from the profile before constructing the driver.
        var configuredTrap = BearTrapRoutine.class.getDeclaredField("trapNumber");
        configuredTrap.setAccessible(true);
        configuredTrap.setInt(bear, 2);
        var failure = assertThrows(BearSessionExecutionException.class, () -> bear.driver().startOwnRally(1));
        assertEquals("configured-trap-2-on-cooldown", failure.operation());
        assertEquals(0, bear.inputs);
        assertEquals(0, bear.backs);
    }

    @Test
    void observeOnlyCleanupSendsNoInputWhileNormalCleanupVerifiesTheUi() {
        ScriptedBear observing = new ScriptedBear(UNKNOWN);
        observing.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, true);
        BearTrapRoutine.LiveBearSessionDriver observer = observing.driver();

        assertDoesNotThrow(() -> observer.cleanup(BearSessionCoordinator.ExitReason.EVENT_ENDED, true),
                "observe-only cleanup must not try to verify the UI with input");
        assertEquals(0, observing.inputs, "observe-only cleanup sent input");

        ScriptedBear playing = new ScriptedBear(UNKNOWN);
        BearTrapRoutine.LiveBearSessionDriver player = playing.driver();
        assertThrows(BearSessionExecutionException.class,
                () -> player.cleanup(BearSessionCoordinator.ExitReason.EVENT_ENDED, true),
                "a normal session must verify the terminal UI before resuming normal work");
    }

    @Test
    void aCancelledObserveOnlySessionStopsInsteadOfHoldingTheWorker() {
        ScriptedBear bear = new ScriptedBear(WORLD_WITH_BEAR);
        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, true);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();
        bear.cancelled = true;

        long started = System.nanoTime();
        BearSessionCoordinator.ExitReason exit = driver.observeOnlyUntil(Instant.now().plusSeconds(5));

        assertEquals(BearSessionCoordinator.ExitReason.CANCELLED, exit);
        assertTrue((System.nanoTime() - started) / 1_000_000L < 3_000,
                "a revoked observe-only session must not run until the event ends");
    }

    @Test
    void aCancelledPreparationWaitStopsInsteadOfSpinningUntilActivation() {
        ScriptedBear bear = new ScriptedBear(WORLD);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();
        bear.cancelled = true;

        long started = System.nanoTime();
        StopExecutionException stop = assertThrows(StopExecutionException.class,
                () -> driver.waitForActivation(Instant.now().plusSeconds(5)));
        assertTrue(stop.isCancellation());
        assertTrue((System.nanoTime() - started) / 1_000_000L < 3_000,
                "a cancelled session must not wait for activation");
    }

    @Test
    void anUnknownScreenGetsOneBackThenARestart() {
        ScriptedBear bear = new ScriptedBear(UNKNOWN);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();

        assertFalse(driver.recover(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        assertEquals(1, bear.backs, "the first unknown recovery sends exactly one Back");

        BearSessionExecutionException restart = assertThrows(BearSessionExecutionException.class,
                () -> driver.recover(BearSessionCoordinator.State.OWN_RALLY_REQUIRED));
        assertEquals(BearSessionExecutionException.RecoveryDirective.RESTART_APP, restart.recoveryDirective());
        assertEquals(1, bear.backs, "the second unknown recovery restarts instead of pressing Back again");
    }

    @Test
    void aClassifiedScreenResetsTheUnknownEscalation() {
        ScriptedBear bear = new ScriptedBear(UNKNOWN);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();

        driver.recover(BearSessionCoordinator.State.FILL_JOIN_SLOTS);
        bear.visible = WORLD;
        assertTrue(driver.recover(BearSessionCoordinator.State.FILL_JOIN_SLOTS));
        bear.visible = UNKNOWN;

        assertFalse(driver.recover(BearSessionCoordinator.State.FILL_JOIN_SLOTS),
                "after a classified screen the next unknown screen starts with Back again");
        assertEquals(2, bear.backs);
    }

    @Test
    void aScreenThatBecomesClassifiedDuringTheWaitAlsoResetsTheEscalation() {
        ScriptedBear bear = new ScriptedBear(UNKNOWN);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();

        // Distinct goals keep the per-goal strike budget out of this escalation check.
        assertFalse(driver.recover(BearSessionCoordinator.State.FILL_JOIN_SLOTS));
        assertEquals(1, bear.backs);
        bear.becomeAfterNextClassification(WORLD);
        assertFalse(driver.recover(BearSessionCoordinator.State.WAIT_FOR_NEXT_USEFUL_DEADLINE),
                "the unknown screen resolved during the bounded wait");
        assertEquals(1, bear.backs, "a screen that resolves on its own needs no input");
        bear.visible = UNKNOWN;

        assertFalse(driver.recover(BearSessionCoordinator.State.OWN_RALLY_REQUIRED),
                "after the screen resolved, a new unknown screen starts with Back, not a restart");
        assertEquals(2, bear.backs);
    }

    @Test
    void recoveryStrikesAreCountedPerGoalAndResetOnlyByThatGoal() {
        ScriptedBear bear = new ScriptedBear(WORLD);
        BearTrapRoutine.LiveBearSessionDriver driver = bear.driver();
        BearSessionCoordinator.State ownRally = BearSessionCoordinator.State.OWN_RALLY_REQUIRED;

        assertFalse(driver.recover(ownRally));
        assertFalse(driver.recover(ownRally));
        bear.visible = WORLD_WITH_BEAR;
        assertTrue(driver.recover(ownRally), "the World with the Bear icon is a valid own-rally destination");
        bear.visible = WORLD;
        assertFalse(driver.recover(ownRally));
        assertFalse(driver.recover(ownRally), "a successful recovery cleared the earlier own-rally strikes");

        assertThrows(BearSessionExecutionException.class, () -> driver.recover(ownRally),
                "the third consecutive own-rally failure exhausts its budget");
        assertEquals(0, bear.inputs, "classified recoveries here send no input");
    }

    private static final class ScriptedBear extends BearTrapRoutine {

        private volatile Set<TemplatesEnum> visible;
        private volatile boolean cancelled;
        private volatile Set<TemplatesEnum> pending;
        private int classifications;
        private int switchAfter = Integer.MAX_VALUE;
        private int backs;
        private int inputs;
        private RawImageData captured = FRAME;
        private ImageSearchResultData lastTap;

        private ScriptedBear(Set<TemplatesEnum> visible) {
            super(new AccountDescriptor(990_003L, "Bear driver", "0", true, 100L, 30L),
                    TpDailyTaskEnum.BEAR_TRAP);
            this.visible = visible;
        }

        BearTrapRoutine.LiveBearSessionDriver driver() {
            return new LiveBearSessionDriver(Instant.now().plusSeconds(1_200), null);
        }

        @Override
        protected void checkPreemption() {
            if (cancelled) {
                throw new StopExecutionException("operator revoked Bear", StopExecutionException.Reason.USER_CANCELLED);
            }
        }

        @Override
        BearRealtimeFrameSource createRealtimeFrameSource(BearCaptureRecorder capture) {
            AtomicLong sequence = new AtomicLong();
            return new BearRealtimeFrameSource(() -> new BearRealtimeFrameSource.StreamHandle() {
                @Override
                public void start() {
                }

                @Override
                public AndroidFrameStream.Frame latestAfter(long after) {
                    return new AndroidFrameStream.Frame(captured, sequence.incrementAndGet(), System.nanoTime());
                }

                @Override
                public String failure() {
                    return null;
                }

                @Override
                public void close() {
                }
            }, () -> false);
        }

        /** The current screen stays for one more frame, then becomes {@code next}. */
        void becomeAfterNextClassification(Set<TemplatesEnum> next) {
            pending = next;
            switchAfter = classifications + 1;
        }

        @Override
        BearFrameClassifier.TemplateMatcher templateMatcher() {
            return (frame, template, threshold) -> {
                // Every classification checks the reconnect dialog first.
                if (template == TemplatesEnum.GAME_HOME_RECONNECT && ++classifications > switchAfter) {
                    visible = pending;
                    switchAfter = Integer.MAX_VALUE;
                }
                return visible.contains(template);
            };
        }

        @Override
        void executeObservedInput(Runnable authorize, Runnable input) {
            authorize.run();
            input.run();
        }

        @Override
        public void pressBack() {
            if (getProfile().getConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, Boolean.class) == Boolean.TRUE) {
                inputs++;
            }
            backs++;
        }

        @Override
        public void tapInside(PointData corner1, PointData corner2) {
            inputs++;
        }

        @Override
        public void tapInside(AreaData area) {
            inputs++;
        }

        @Override
        public boolean tapInside(ImageSearchResultData result) {
            lastTap = result;
            inputs++;
            return true;
        }
    }
}
