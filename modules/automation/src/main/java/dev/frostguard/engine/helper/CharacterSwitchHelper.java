package dev.frostguard.engine.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

// Handles character verification and switching via the in-game profile menu.
public class CharacterSwitchHelper {

    public static final int CHARACTER_SWITCH_RELOAD_DELAY_MS = 5000;

    private final EmulatorController emu;
    private final String dev;
    private final TapInteractionService taps;
    private final ProfileContextLogger log;
    private final TemplateSearchHelper tpl;
    private final ResilientOcrExecutor<String> ocr;

    public CharacterSwitchHelper(EmulatorController emuManager, String emulatorNumber, AccountDescriptor profile) {
        this.emu = emuManager;
        this.dev = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.log = new ProfileContextLogger(CharacterSwitchHelper.class, profile);
        this.tpl = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
        this.ocr = new ResilientOcrExecutor<>(new BotOcrEngine(emuManager, emulatorNumber));
    }

    // --- Character verification ---

    public boolean verifyCurrentCharacter(AccountDescriptor p) {
        String wantName = p.getCharacterName(), wantId = p.getCharacterId();
        if (blank(wantName) && blank(wantId)) { log.debug("No character config, skipping"); return true; }
        log.info("Verifying character: name='" + wantName + "' id='" + wantId + "'");

        taps.tapInside(CommonGameAreas.PROFILE_AVATAR, 1, 500);

        String liveId = ocrRead(CommonGameAreas.CHARACTER_ID_OCR_AREA, idOcrCfg());
        String liveName = ocrRead(CommonGameAreas.CHARACTER_NAME_OCR_AREA, nameOcrCfg());
        log.debug("Live char: name='" + liveName + "' id='" + liveId + "'");

        boolean idOk = blank(wantId) || (!blank(liveId) && wantId.equals(liveId));
        boolean nameOk = blank(wantName) || (!blank(liveName) && nameMatch(liveName, wantName));
        if (!blank(wantId) && blank(liveId)) log.warn("ID configured but OCR failed");
        if (!blank(wantName) && blank(liveName)) log.warn("Name configured but OCR failed");

        if (idOk || nameOk) { log.info("Char verified OK"); emu.pressBack(dev); return true; }
        log.warn("Char mismatch"); return false;
    }

    // --- Character switching ---

    public boolean switchToCharacter(AccountDescriptor p) {
        String target = p.getCharacterName();
        if (blank(target)) { log.error("No character name configured"); return false; }
        log.info("Switching to '" + target + "' server=" + p.getCharacterServer());

        if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA)) {
            log.error("Settings button not found"); return false;
        }
        if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON, null)) {
            log.error("Switch Character button not found"); emu.pressBack(dev); return false;
        }
        sleep(2000);

        for (int pass = 0; pass < 3; pass++) {
            log.debug("Roster scan " + (pass + 1) + "/3");
            ImageSearchResultData hit = findInRoster(target);
            if (hit == null) { log.debug("Not visible, scrolling"); scrollDown(); continue; }

            // Check if already active via checkmark badge
            if (checkmarkAt(hit.getPoint())) {
                log.info("Already active — done");
                emu.pressBack(dev); sleep(500); return true;
            }

            // Tap and confirm
            taps.tapInside(hit, 1, 500); sleep(500);
            if (confirmSwitch(target)) { sleep(CHARACTER_SWITCH_RELOAD_DELAY_MS); log.info("Switch OK"); return true; }
            log.warn("Confirm failed, canceling");
            cancelSwitch();
        }
        // Changed by pernerch | Date: 2026-07-02 | Why: close emulator only as final fallback after repeated switch failure.
        log.error("Character not found after 3 passes — closing emulator");
        emu.closeEmulator(dev); return false;
    }

    // --- Roster scanning (merged active + inactive search) ---

    private ImageSearchResultData findInRoster(String targetName) {
        TemplateSearchHelper.SearchConfig cfg = TemplateSearchHelper.SearchConfig.builder()
                .withArea(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA)
                .withMaxAttempts(3).withThreshold(90).withMaxResults(5).build();

        List<ImageSearchResultData> hits = new ArrayList<>();
        addAll(hits, tpl.locateAllPatterns(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_ACTIVE, cfg));
        addAll(hits, tpl.locateAllPatterns(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_INACTIVE, cfg));
        if (hits.isEmpty()) return null;
        log.debug(hits.size() + " furnace indicators found");

        for (ImageSearchResultData h : hits) {
            PointData fp = h.getPoint();
            String name = ocr.attemptRecognition(
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_START,
                            fp.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y),
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_END,
                            fp.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y),
                    2, 200, nameOcrCfg(), t -> t != null && !t.trim().isEmpty(), String::trim);
            if (name != null && nameMatch(name, targetName)) { log.info("Found: '" + name + "'"); return h; }
        }
        return null;
    }

    private boolean checkmarkAt(PointData fp) {
        int cx = fp.getX() + 264, cy = fp.getY() - 40;
        return tpl.locatePattern(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_ACTIVE_CHECKMARK,
                TemplateSearchHelper.SearchConfig.builder()
                        .withArea(new AreaData(new PointData(cx, cy), new PointData(cx + 60, cy + 60)))
                        .withMaxAttempts(1).withThreshold(90).build()).isFound();
    }

    // --- Confirm / cancel dialog ---

    private boolean confirmSwitch(String expectedName) {
        String dialogName = ocr.attemptRecognition(
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA.topLeft(),
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA.bottomRight(),
                2, 200, nameOcrCfg(), t -> t != null && !t.trim().isEmpty(), String::trim);
        if (blank(dialogName) || !nameMatch(dialogName, expectedName)) {
            log.warn("Dialog name mismatch: got='" + dialogName + "' want='" + expectedName + "'");
            return false;
        }
        log.info("Dialog name OK: '" + dialogName + "'");
        return tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA);
    }

    private void cancelSwitch() {
        ImageSearchResultData btn = tpl.locatePattern(
                TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CANCEL_BUTTON,
                TemplateSearchHelper.SearchConfig.builder()
                        .withArea(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA)
                        .withMaxAttempts(3).withDelay(500).withThreshold(80).build());
        if (btn == null || !btn.isFound()) emu.pressBack(dev);
        else taps.tapInside(btn, 1, 500);
        sleep(500);
    }

    // --- Shared utilities ---

    private boolean tapTemplate(TemplatesEnum t, AreaData area) {
        TemplateSearchHelper.SearchConfig.Builder b = TemplateSearchHelper.SearchConfig.builder()
                .withMaxAttempts(3).withDelay(500).withThreshold(80);
        if (area != null) b.withArea(area);
        ImageSearchResultData hit = tpl.locatePattern(t, b.build());
        if (hit == null || !hit.isFound()) return false;
        taps.tapInside(hit, 1, 500); return true;
    }

    private boolean nameMatch(String ocrText, String expected) {
        if (blank(ocrText) || blank(expected)) return false;
        String a = ocrText.trim().replaceAll("\\s+", "");
        String b = expected.trim().replaceAll("\\s+", "");
        return Pattern.compile(Pattern.quote(b), Pattern.CASE_INSENSITIVE).matcher(a).find();
    }

    private String ocrRead(AreaData area, OcrSettingsData cfg) {
        return ocr.attemptRecognition(area, 3, 300L, cfg, t -> t != null && !t.trim().isEmpty(), String::trim);
    }

    private static boolean blank(String s) { return s == null || s.isEmpty(); }

    private void scrollDown() {
        emu.swipeScreen(dev, new PointData(360, 800), new PointData(360, 400));
        sleep(1000);
    }

    private static void addAll(List<ImageSearchResultData> dst, List<ImageSearchResultData> src) {
        if (src != null) dst.addAll(src);
    }

    private OcrSettingsData idOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("0123456789")
                .setRemoveBackground(true).build();
    }

    private OcrSettingsData nameOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ")
                .setRemoveBackground(true).build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
