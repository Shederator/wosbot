package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.frostguard.engine.error.BearSessionExecutionException;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BearTrapRoutineScreenOwnershipTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test
    void bearEntersAndLeavesWithoutLegacyNavigation() {
        RecordingBear bear = new RecordingBear(true);

        bear.enterStartScreen(true);
        bear.leaveScreen();

        assertEquals(List.of(), bear.navigation, "Bear owns every screen transition itself");
    }

    @Test
    void missingGameProcessIsAProtectedRestartNotAnInitializeHandoff() {
        RecordingBear bear = new RecordingBear(false);

        BearSessionExecutionException failure = assertThrows(
                BearSessionExecutionException.class, () -> bear.enterStartScreen(false));

        assertEquals(BearSessionExecutionException.RecoveryDirective.RESTART_APP,
                failure.recoveryDirective());
        assertEquals(List.of(), bear.navigation);
    }

    @Test
    void observeOnlyRefusesEveryDeviceInput() {
        RecordingBear bear = new RecordingBear(true);
        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, true);

        List<Runnable> inputs = List.of(
                () -> bear.tapInside(new PointData(10, 10), new PointData(10, 10)),
                () -> bear.tapInside(new AreaData(new PointData(1, 1), new PointData(2, 2))),
                () -> bear.tapInside(new ImageSearchResultData(true, new PointData(5, 5), 99.0)),
                () -> bear.swipe(new PointData(1, 1), new PointData(1, 200)),
                () -> bear.swipe(new PointData(1, 1), new PointData(1, 200), 300),
                () -> bear.tapInside(new PointData(10, 10), new PointData(10, 10), 2, 50),
                () -> bear.tapInside(new AreaData(new PointData(1, 1), new PointData(2, 2)), 2, 50),
                () -> bear.tapInside(new ImageSearchResultData(true, new PointData(5, 5), 99.0), 2, 50),
                () -> bear.tapNear(new PointData(5, 5)),
                () -> bear.tapNear(new PointData(5, 5), 3),
                () -> bear.tapNear(new PointData(5, 5), 3, 2, 50),
                bear::pressBack);
        for (Runnable input : inputs) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, input::run);
            assertTrue(refused.getMessage().contains("observe-only"),
                    "input must be refused by observe-only, not fail later: " + refused.getMessage());
        }
    }

    @Test
    void theSchedulersObserveOnlyFallbackAlsoRefusesInput() {
        RecordingBear bear = new RecordingBear(true);
        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_FALLBACK_STRING,
                Instant.now().plusSeconds(600).toString());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bear.tapInside(new PointData(10, 10), new PointData(10, 10)));
        assertTrue(refused.getMessage().contains("observe-only"), refused.getMessage());

        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_FALLBACK_STRING,
                Instant.now().minusSeconds(1).toString());
        assertTrue(bear.verifiesTerminalUi(true), "an expired fallback restores normal cleanup");
    }

    @Test
    void observeOnlyCleanupNeverVerifiesTheUiWithInput() {
        RecordingBear bear = new RecordingBear(true);
        assertTrue(bear.verifiesTerminalUi(true));
        assertTrue(!bear.verifiesTerminalUi(false));

        bear.getProfile().setConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_ONLY_BOOL, true);
        assertTrue(!bear.verifiesTerminalUi(true), "observe-only cleanup must not send input");
    }

    @Test
    void theProductionMatcherUsesTheEmulatorMatcherAtTheClassifierThreshold() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError alreadyLoaded) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
        BufferedImage template = ImageIO.read(BearTrapRoutineScreenOwnershipTest.class.getResource(
                TemplatesEnum.GAME_HOME_WORLD.resourcePath()));
        BufferedImage screen = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB);
        var graphics = screen.createGraphics();
        graphics.drawImage(template, 625, 1190, null);
        graphics.dispose();
        BearFrameClassifier.TemplateMatcher matcher = new RecordingBear(true).templateMatcher();

        assertTrue(matcher.found(rgba(screen), TemplatesEnum.GAME_HOME_WORLD, 90),
                "the shipped World template is found in its calibrated navigation region");
        BufferedImage misplaced = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB);
        graphics = misplaced.createGraphics();
        graphics.drawImage(template, 300, 900, null);
        graphics.dispose();
        assertTrue(!matcher.found(rgba(misplaced), TemplatesEnum.GAME_HOME_WORLD, 90),
                "an identical icon outside the navigation region cannot identify World");
        BufferedImage noise = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(7);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        assertTrue(!matcher.found(rgba(noise), TemplatesEnum.GAME_HOME_WORLD, 90),
                "an unrelated frame must not match the World root at the classifier threshold");
    }

    private static RawImageData rgba(BufferedImage image) {
        byte[] pixels = new byte[image.getWidth() * image.getHeight() * 4];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int offset = (y * image.getWidth() + x) * 4;
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 255;
            }
        }
        return RawImageData.capture(pixels, image.getWidth(), image.getHeight(), 32);
    }

    private static final class RecordingBear extends BearTrapRoutine {

        private final boolean gameRunning;
        private final List<LaunchPoint> navigation = new ArrayList<>();

        private RecordingBear(boolean gameRunning) {
            super(profile(), TpDailyTaskEnum.BEAR_TRAP);
            this.gameRunning = gameRunning;
            this.navigationHelper = new NavigationHelper(emuManager, "0", profile()) {
                @Override
                public void ensureCorrectScreenLocation(LaunchPoint target) {
                    navigation.add(target);
                }
            };
        }

        @Override
        protected boolean gameProcessRunning() {
            return gameRunning;
        }

        private static AccountDescriptor profile() {
            return new AccountDescriptor(990_002L, "Bear screen ownership", "0", true, 100L, 30L);
        }
    }
}
