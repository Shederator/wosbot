package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class DeploymentHelperFrameBatchTest {

    @BeforeAll
    static void loadOpenCv() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void stableDeploymentPhasesCaptureOnceAndReuseEveryFrame() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        RawImageData deploymentFrame = rgbaFrame(image(
                "/deployment/intel-beast-formation-20260819.png"));
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setDisplayName("deployment-frame-test");
        TemplateSearchHelper templates = new TemplateSearchHelper(
                EmulatorController.getInstance(), "0", profile,
                () -> {
                    captures.incrementAndGet();
                    return deploymentFrame;
                });
        DeploymentHelper helper = new DeploymentHelper(
                null, "0", templates, null, null, profile);

        DeploymentFormationRead formation = helper.readFormationScreen();
        DeploymentPreflightRead preflight = helper.readPreflightScreen(
                DeploymentHelper.MAX_ATTACK_STAMINA_COST);
        DeploymentPostTapRead postTap = helper.readPostTapScreen();

        assertFalse(formation.marchQueueFull());
        assertTrue(formation.deployButton().isFound());
        assertEquals(15, preflight.deployment().travelTimeSeconds());
        assertEquals(9, preflight.deployment().staminaCost());
        assertFalse(preflight.noDeployableTroops());
        assertFalse(preflight.deployCostRed());
        assertTrue(preflight.deployButton().isFound());
        assertFalse(postTap.marchQueueFull());
        assertTrue(postTap.deployButton().isFound());

        int decisions = 10;
        assertEquals(3, captures.get(), "each stable phase needs exactly one fresh frame");
        assertTrue(captures.get() * 2 <= decisions,
                "the representative flow must cut full-screen captures by at least 50%");
    }

    @Test
    void eachPhaseStartsFromANewCaptureInsteadOfKeepingThePreviousFrame() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        RawImageData deploymentFrame = rgbaFrame(image(
                "/deployment/intel-beast-formation-20260819.png"));
        RawImageData blankFrame = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);
        AccountDescriptor profile = new AccountDescriptor(2L);
        profile.setDisplayName("deployment-freshness-test");
        TemplateSearchHelper templates = new TemplateSearchHelper(
                EmulatorController.getInstance(), "1", profile,
                () -> captures.getAndIncrement() == 0 ? deploymentFrame : blankFrame);
        DeploymentHelper helper = new DeploymentHelper(
                null, "1", templates, null, null, profile);

        DeploymentFormationRead beforeInput = helper.readFormationScreen();
        DeploymentPostTapRead afterInput = helper.readPostTapScreen();

        assertTrue(beforeInput.deployButton().isFound());
        assertFalse(afterInput.deployButton().isFound(),
                "the post-input phase must observe the newly captured screen");
        assertEquals(2, captures.get());
    }

    private BufferedImage image(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
