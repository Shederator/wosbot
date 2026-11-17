package dev.frostguard.engine.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.TaskPreemptedException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

public class CharacterSwitchHelper {

    public static final int CHARACTER_SWITCH_RELOAD_DELAY_MS = 5000;
    private static final Pattern ALLIANCE_TAG_PREFIX = Pattern.compile("^\\[[A-Za-z0-9]{3}\\]\\s*");
    private static final Pattern ID_DIGITS = Pattern.compile("[0-9]+");

    public enum MatchStatus { MATCH, MISMATCH, UNKNOWN }

    record MatchResult(MatchStatus status, String detail) {
        boolean ok() {
            return status == MatchStatus.MATCH;
        }
    }

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

    static MatchResult evaluateMatch(String wantId, String liveId, String wantName, String liveName) {
        if (!blank(wantId)) {
            if (blank(liveId) || !ID_DIGITS.matcher(liveId.strip()).matches()) {
                return new MatchResult(MatchStatus.UNKNOWN, "Configured ID could not be read (OCR blank or invalid)");
            }
            if (!wantId.strip().equals(liveId.strip())) {
                return new MatchResult(MatchStatus.MISMATCH, "ID mismatch");
            }
            if (!blank(wantName) && !blank(liveName) && !nameMatch(liveName, wantName)) {
                return new MatchResult(MatchStatus.MATCH, "Name differs, accepted via immutable ID");
            }
            return new MatchResult(MatchStatus.MATCH, "ID verified OK");
        }
        if (blank(wantName)) {
            return new MatchResult(MatchStatus.MATCH, "No character criteria configured");
        }
        if (normalizedName(liveName).isEmpty() || normalizedName(wantName).isEmpty()) {
            return new MatchResult(MatchStatus.UNKNOWN, "Configured name could not be verified (OCR blank or invalid name)");
        }
        if (!nameMatch(liveName, wantName)) {
            return new MatchResult(MatchStatus.MISMATCH, "Name mismatch");
        }
        return new MatchResult(MatchStatus.MATCH, "Name verified OK");
    }

    public boolean verifyCurrentCharacter(AccountDescriptor profile) {
        return inspectCurrentCharacter(profile) == MatchStatus.MATCH;
    }

    /**
     * Opens Chief Profile and leaves it available on a verified mismatch for roster navigation.
     */
    public MatchStatus inspectCurrentCharacter(AccountDescriptor profile) {
        String wantName = profile.getCharacterName();
        String wantId = profile.getCharacterId();
        if (blank(wantName) && blank(wantId)) {
            return MatchStatus.MATCH;
        }

        taps.tapInside(CommonGameAreas.PROFILE_AVATAR, 1, 500);
        if (!profileVisible()) {
            log.warn("Character identity unknown: Chief Profile could not be verified");
            return MatchStatus.UNKNOWN;
        }

        String liveId = blank(wantId) ? null : ocrRead(CommonGameAreas.CHARACTER_ID_OCR_AREA, idOcrCfg());
        String liveName = blank(wantId) ? ocrRead(CommonGameAreas.CHARACTER_NAME_OCR_AREA, nameOcrCfg()) : null;
        MatchResult result = evaluateMatch(wantId, liveId, wantName, liveName);
        if (result.ok()) {
            log.info("Char verified OK: " + result.detail());
            emu.pressBack(dev);
        } else {
            log.warn("Character verification " + result.status() + ": " + result.detail());
        }
        return result.status();
    }

    public boolean switchToCharacter(AccountDescriptor profile) {
        String target = profile.getCharacterName();
        if (normalizedName(target).isEmpty()) {
            log.warn("No usable character name is configured for roster lookup");
            return false;
        }
        if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON, CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA)) {
            log.warn("Settings button not found in Chief Profile");
            return false;
        }
        if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON_AREA)) {
            log.warn("Switch Character button not found");
            return false;
        }
        sleep(2000);

        for (int pass = 0; pass < 3; pass++) {
            List<ImageSearchResultData> matches = findInRoster(target);
            if (matches.isEmpty()) {
                if (pass < 2) {
                    scrollDown();
                }
                continue;
            }
            if (matches.size() > 1) {
                log.warn("Multiple visible roster entries match the configured name");
                return false;
            }
            ImageSearchResultData hit = matches.getFirst();
            if (checkmarkAt(hit.getPoint())) {
                return leaveVerifiedRoster();
            }
            if (taps.tapInside(hit, 1, 500) && confirmSwitch(target)) {
                return true;
            }
            cancelSwitch();
            return false;
        }
        log.warn("Character not found after three roster scans");
        return false;
    }

    private boolean leaveVerifiedRoster() {
        emu.pressBack(dev);
        sleep(500);
        if (!findTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON_AREA).isFound()) {
            return false;
        }
        emu.pressBack(dev);
        sleep(500);
        if (!profileVisible()) {
            return false;
        }
        emu.pressBack(dev);
        return true;
    }

    private boolean profileVisible() {
        return findTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA).isFound();
    }

    private List<ImageSearchResultData> findInRoster(String targetName) {
        TemplateSearchHelper.SearchConfig cfg = TemplateSearchHelper.SearchConfig.builder()
                .withArea(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA)
                .withMaxAttempts(3).withThreshold(90).withMaxResults(5).build();
        List<ImageSearchResultData> hits = new ArrayList<>(tpl.locateAllPatterns(
                TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_ACTIVE, cfg));
        hits.addAll(tpl.locateAllPatterns(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_INACTIVE, cfg));
        List<ImageSearchResultData> matches = new ArrayList<>();
        for (ImageSearchResultData hit : hits) {
            PointData point = hit.getPoint();
            String name = ocr.attemptRecognition(
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_START,
                            point.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y),
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_END,
                            point.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y),
                    2, 200, nameOcrCfg(), text -> !blank(text), String::strip);
            if (nameMatch(name, targetName)) {
                matches.add(hit);
            }
        }
        return matches;
    }

    private boolean checkmarkAt(PointData point) {
        int x = point.getX() + 264;
        int y = point.getY() - 40;
        return tpl.locatePattern(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_ACTIVE_CHECKMARK,
                TemplateSearchHelper.SearchConfig.builder()
                        .withArea(new AreaData(new PointData(x, y), new PointData(x + 60, y + 60)))
                        .withMaxAttempts(1).withThreshold(90).build()).isFound();
    }

    private boolean confirmSwitch(String expectedName) {
        String dialogName = ocrRead(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA,
                nameOcrCfg());
        if (!nameMatch(dialogName, expectedName)) {
            log.warn("Confirmation dialog name was unreadable or did not match");
            return false;
        }
        return tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA);
    }

    private void cancelSwitch() {
        tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CANCEL_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA);
    }

    private boolean tapTemplate(TemplatesEnum template, AreaData area) {
        return taps.tapInside(findTemplate(template, area), 1, 500);
    }

    private ImageSearchResultData findTemplate(TemplatesEnum template, AreaData area) {
        return tpl.locatePattern(template, TemplateSearchHelper.SearchConfig.builder()
                .withArea(area).withMaxAttempts(3).withDelay(500).withThreshold(80).build());
    }

    static boolean nameMatch(String ocrText, String expected) {
        String actual = normalizedName(ocrText);
        return !actual.isEmpty() && actual.equalsIgnoreCase(normalizedName(expected));
    }

    private static String normalizedName(String name) {
        if (blank(name)) {
            return "";
        }
        String base = ALLIANCE_TAG_PREFIX.matcher(name.strip()).replaceFirst("").strip().replaceAll("\\s+", " ");
        return base.codePoints().anyMatch(Character::isLetterOrDigit) ? base : "";
    }

    private String ocrRead(AreaData area, OcrSettingsData cfg) {
        return ocr.attemptRecognition(area, 3, 300L, cfg, text -> !blank(text), String::strip);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void scrollDown() {
        emu.swipeScreen(dev, new PointData(360, 800), new PointData(360, 400));
        sleep(1000);
    }

    private OcrSettingsData idOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("0123456789")
                .build();
    }

    private OcrSettingsData nameOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789[]()-_.'# ")
                .build();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw TaskPreemptedException.because("Character navigation interrupted");
        }
    }
}
