package dev.frostguard.tasks.shop;

import java.time.LocalDateTime;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * The top-right cart-icon Shop panel, being built out tab by tab
 * ("let's go through and tab by tab... putting options like when this shows up you
 * do X/Y/Z"). Custom Armament Chest is the first (default) tab -- a rotating,
 * periodic event that "might not exist for weeks at a time." Its free reward is a
 * plain "Claimable" badge on the chest icon (top-right of the banner), separate from
 * the paid $4.99/$9.99/etc chest packs below it -- this routine ONLY ever taps the
 * free Claimable badge, never a purchase button.
 *
 * <p>
 * <b>Live-verified 2026-08-13</b>: top-right cart icon -> Custom Armament Chest tab
 * (default/first tab) -> Claimable badge tap -> badge instantly replaced by a
 * countdown timer, no reward-reveal popup -- confirms it's a silent single-tap claim.
 *
 * <p>
 * Checked once a day by design's request, since the event itself may not be running
 * at all -- a miss here just means "not currently available," not a failure.
 */
public class CustomArmamentChestRoutine extends DelayedTask {

    private static final int IDLE_RECHECK_HOURS = 24;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public CustomArmamentChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    // Caught live -- "these things always need to be able to [work] even if
    // whatever screen I'm at." A single locatePattern attempt for the cart icon has no recovery if
    // some OTHER task left a popup/panel open (the same class of bug already fixed for Monument
    // tonight) -- it just silently gives up and waits a full day. Real fix: if the cart icon isn't
    // there on the first look, actively clear back to World (back-presses + the framework's own
    // ensureCorrectScreenLocation, which is exactly what every other task in this codebase already
    // leans on to get back to a known state) and retry a few times before actually giving up.
    private static final int MAX_NAV_RETRIES = 3;

    private ImageSearchResultData locateCartButtonRobust() {
        ImageSearchResultData cartBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (cartBtn.isFound()) {
            return cartBtn;
        }

        logInfo(logLine("Cart icon not visible on the first look -- clearing whatever's in the way "
                + "and retrying instead of assuming it's genuinely gone."));
        for (int attempt = 1; attempt <= MAX_NAV_RETRIES; attempt++) {
            pressBack();
            sleepTask(500);
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            cartBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (cartBtn.isFound()) {
                logInfo(logLine("Cart icon found after " + attempt + " recovery attempt(s)."));
                return cartBtn;
            }
        }
        return cartBtn;
    }

    @Override
    protected void execute() {
        ImageSearchResultData cartBtn = locateCartButtonRobust();
        if (!cartBtn.isFound()) {
            logInfo(logLine("Shop cart icon not found even after clearing/retrying. Rechecking in "
                    + IDLE_RECHECK_HOURS + " hours."));
            reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
            return;
        }
        tapNear(cartBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // Observed live: the chest icon itself bobs/sparkles continuously (confirmed
        // by hand -- template match score swung 0.76-0.85 across 6 rapid frames of the exact same
        // badge, never once clearing the 0.90 threshold, because the icon's position/particles never
        // hold still long enough for a single-frame template to land). Tried OCR against the
        // "Claimable" text next -- also unreliable in practice (misread as "laimable" with too tight
        // a crop, then as pure garbage "Feeeae" with a verified-correct wider crop -- Tesseract just
        // doesn't land cleanly on this bold outlined font over a busy photo background).
        //
        // The operator's own fix, proven the right call -- "let's start always looking for
        // the red dot exactly... claimable works too [as a target], but the red dot is the one to
        // lean on app-wide." The little solid-red notification dot on the tab icon itself never
        // animates and isn't a font-rendering problem -- straight pixel-color detection, no OCR, no
        // template match. OCR result is still logged alongside for diagnostics but no longer gates
        // the decision.
        saveDebugFrame("panel_open");
        BufferedImage frame = captureFrame();
        String rawOcrText = readClaimableRawText();
        logInfo(logLine("Raw OCR read from claimable-text region (diagnostic only): \"" + rawOcrText + "\""));
        boolean claimable = frame != null && isRedDotVisible(frame);
        logInfo(logLine("Red-dot check: " + (claimable ? "PRESENT" : "not present")));
        if (claimable) {
            logInfo(logLine("Claimable badge found (text-based, animation-proof). Claiming."));
            tapNear(CLAIMABLE_BADGE_TAP_POINT);
            sleepTask(ACTION_SETTLE_MS);
            StatisticsService.obtain().addToCounter(profile, "Custom Armament Chest Claimed", 1);
        } else {
            logInfo(logLine("Nothing claimable right now (event may not be running)."));
        }

        pressBack();

        logInfo(logLine("Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
        reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
    }

    // "Claimable" text region, live-calibrated -- sits directly under the bouncing
    // chest icon and does not itself move. Tap point targets the chest icon's resting center (well
    // within its bounce range either way, and tapping the badge area works regardless of exactly
    // where within it lands).
    // First crop (570,275)-(680,302) clipped the leading "C" -- OCR read "laimable"
    // instead of "Claimable" and the containsIgnoreCase("claim") check failed on a genuinely-present
    // badge. Re-measured directly off a saved live debug frame (shop-debug/claim_crop_check.png):
    // the real glyph box is roughly x=548-683, y=290-308. Widened with margin on every side.
    private static final PointData CLAIMABLE_TEXT_TL = new PointData(535, 282);
    private static final PointData CLAIMABLE_TEXT_BR = new PointData(695, 315);
    private static final PointData CLAIMABLE_BADGE_TAP_POINT = new PointData(620, 250);

    private static final OcrSettingsData CLAIMABLE_TEXT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    // Static red notification dot on the Custom Armament Chest tab icon itself --
    // live-measured bounding box (216-229, 104-118) via direct pixel scan of a saved debug frame,
    // widened a few px for margin. Solid, near-pure red (#e0202x-ish), never moves, never animates --
    // exactly the "always look for the red dot" target the request was for.
    private static final int RED_DOT_TL_X = 212;
    private static final int RED_DOT_TL_Y = 100;
    private static final int RED_DOT_BR_X = 233;
    private static final int RED_DOT_BR_Y = 122;
    private static final int RED_DOT_MIN_PIXELS = 15;

    private boolean isRedDotVisible(BufferedImage img) {
        int count = 0;
        int maxX = Math.min(RED_DOT_BR_X, img.getWidth());
        int maxY = Math.min(RED_DOT_BR_Y, img.getHeight());
        for (int y = RED_DOT_TL_Y; y < maxY; y++) {
            for (int x = RED_DOT_TL_X; x < maxX; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 180 && g < 80 && b < 80) {
                    count++;
                }
            }
        }
        return count >= RED_DOT_MIN_PIXELS;
    }

    private BufferedImage captureFrame() {
        try {
            RawImageData frame = emuManager.captureScreen(String.valueOf(EMULATOR_NUMBER));
            return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
        } catch (Exception e) {
            logWarning(logLine("Failed to capture frame for red-dot check: " + e.getMessage()));
            return null;
        }
    }

    private String readClaimableRawText() {
        return stringHelper.attemptRecognition(
                CLAIMABLE_TEXT_TL, CLAIMABLE_TEXT_BR,
                3, 200L, CLAIMABLE_TEXT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
    }

    // Modeled directly on DailyLabyrinthRoutine's saveLabyrinthFrame() -- saves the
    // ACTUAL live frame at the exact moment the claim decision is made, so a failed run leaves real
    // proof on disk instead of forcing a re-chase of whatever screen the emulator has since moved to.
    private void saveDebugFrame(String label) {
        try {
            RawImageData frame = emuManager.captureScreen(String.valueOf(EMULATOR_NUMBER));
            BufferedImage img = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
            File dir = new File(System.getProperty("user.dir"), "shop-debug");
            dir.mkdirs();
            File out = new File(dir, "customArmament_" + label + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", out);
            logInfo(logLine("Debug frame saved: " + out.getName()));
        } catch (Exception e) {
            logWarning(logLine("Failed to save debug frame (" + label + "): " + e.getMessage()));
        }
    }

    private String logLine(String note) {
        return "CustomArmamentChestRoutine | " + note;
    }
}
