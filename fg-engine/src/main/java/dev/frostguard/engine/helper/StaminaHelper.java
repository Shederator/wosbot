package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

// Orchestrates stamina tracking: OCR reads, regen delay computation,
// availability gating, and travel time parsing.
public class StaminaHelper {

    @FunctionalInterface
    public interface RescheduleCallback {
        void reschedule(LocalDateTime time);
    }

    private final EmulatorController device;
    private final String deviceSlot;
    private final ResilientOcrExecutor<Integer> numberReader;
    private final ResilientOcrExecutor<Duration> durationReader;
    private final StaminaService persistence;
    private final Long accountKey;
    private final ProfileContextLogger trace;
    private final MarchHelper marchSupport;
    private final String accountLabel;
    private final LoggingService centralLog;

    public StaminaHelper(EmulatorController emuManager, String emulatorNumber,
                         ResilientOcrExecutor<Integer> integerHelper,
                         ResilientOcrExecutor<Duration> durationHelper,
                         AccountDescriptor profile, MarchHelper marchHelper) {
        this.device = emuManager;
        this.deviceSlot = emulatorNumber;
        this.numberReader = integerHelper;
        this.durationReader = durationHelper;
        this.persistence = StaminaService.getServices();
        this.accountKey = profile.getId();
        this.trace = new ProfileContextLogger(StaminaHelper.class, profile);
        this.marchSupport = marchHelper;
        this.accountLabel = profile.getName();
        this.centralLog = LoggingService.obtain();
    }

    // Opens avatar screen, reads stamina via OCR, persists, then navigates back.
    public void updateStaminaFromProfile() {
        emitDebug("Opening profile to read stamina");

        device.touchArea(deviceSlot,
                CommonGameAreas.PROFILE_AVATAR.topLeft(),
                CommonGameAreas.PROFILE_AVATAR.bottomRight(), 1, 500);
        device.touchArea(deviceSlot,
                CommonGameAreas.STAMINA_BUTTON.topLeft(),
                CommonGameAreas.STAMINA_BUTTON.bottomRight(), 1, 500);

        Integer reading = readStableStamina();

        if (reading == null) {
            emitWarn("OCR could not reach a stable stamina reading; keeping previous value");
        } else {
            emitInfo("Stamina read (consensus): " + reading);
            persistence.setStamina(accountKey, reading);
        }

        device.pressBack(deviceSlot);
        device.pressBack(deviceSlot);
    }

    // How many independent OCR samples to take, and how many must agree.
    private static final int STAMINA_SAMPLE_COUNT = 5;
    private static final int STAMINA_MIN_AGREEMENT = 2;
    private static final int STAMINA_SAMPLE_PAUSE_MS = 120;
    private static final int STAMINA_MIN_DENOMINATOR = 100;

    // Reads the stamina fraction across several frames and returns the value the
    // majority agree on (with a denominator/numerator sanity check), so a transient
    // mid-animation frame can't poison the model. Returns null when none agree.
    private Integer readStableStamina() {
        // only require a plausible max; stamina can overfill above it (e.g. 260/200)
        Predicate<String> saneFraction = text -> {
            Integer numerator = RegexNumberParser.numerator(text);
            Integer denominator = RegexNumberParser.denominator(text);
            return numerator != null && denominator != null
                    && denominator >= STAMINA_MIN_DENOMINATOR;
        };

        Map<Integer, Integer> tally = new HashMap<>();
        for (int sample = 0; sample < STAMINA_SAMPLE_COUNT; sample++) {
            Integer value = numberReader.attemptRecognition(
                    CommonGameAreas.STAMINA_OCR_AREA.topLeft(),
                    CommonGameAreas.STAMINA_OCR_AREA.bottomRight(),
                    1, 0L,
                    CommonOCRSettings.STAMINA_FRACTION_SETTINGS,
                    saneFraction,
                    RegexNumberParser::numerator);
            if (value != null) {
                tally.merge(value, 1, Integer::sum);
            }
            pauseBetweenSamples();
        }

        return tally.entrySet().stream()
                .filter(entry -> entry.getValue() >= STAMINA_MIN_AGREEMENT)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void pauseBetweenSamples() {
        try {
            Thread.sleep(STAMINA_SAMPLE_PAUSE_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // Reads stamina cost from the deployment confirmation screen.
    public Integer getSpentStamina() {
        Integer cost = numberReader.attemptRecognition(
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.topLeft(),
                CommonGameAreas.SPENT_STAMINA_OCR_AREA.bottomRight(),
                5, 200L,
                CommonOCRSettings.SPENT_STAMINA_SETTINGS,
                txt -> RegexNumberParser.conformsTo(txt, CommonOCRSettings.NUMBER_PATTERN),
                txt -> RegexNumberParser.extractByPattern(txt, CommonOCRSettings.NUMBER_PATTERN));

        emitDebug(cost != null ? "Deployment cost: " + cost : "Deployment cost OCR failed");
        return cost;
    }

    public void subtractStamina(Integer spent, boolean rally) {
        int deduction;
        if (spent != null) {
            deduction = spent;
        } else {
            deduction = rally ? 25 : 10;
        }
        emitDebug("Deducting " + deduction + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.subtractStamina(accountKey, deduction);
    }

    public void addStamina(Integer amount) {
        if (amount == null) return;
        emitDebug("Crediting " + amount + " (current " + persistence.getCurrentStamina(accountKey) + ")");
        persistence.addStamina(accountKey, amount);
    }

    public int getCurrentStamina() {
        return persistence.getCurrentStamina(accountKey);
    }

    // Computes minutes needed for stamina to regenerate from current to target.
    public int staminaRegenerationTime(int current, int target) {
        if (current >= target) return 0;
        int deficit = target - current;
        int waitMinutes = deficit * 5;
        emitDebug(deficit + " points deficit → " + waitMinutes + " min wait");
        return waitMinutes;
    }

    // Validates stamina and optionally march slots; reschedules on failure.
    // If verifyMarches is true, also checks march availability.
    public boolean checkStaminaAndMarchesOrReschedule(
            int min, int refresh, RescheduleCallback cb) {
        return verifyReadiness(min, refresh, cb, true);
    }

    public boolean checkStaminaOrReschedule(
            int min, int refresh, RescheduleCallback cb) {
        return verifyReadiness(min, refresh, cb, false);
    }

    private boolean verifyReadiness(int min, int refresh,
                                    RescheduleCallback cb, boolean verifyMarches) {
        int level = persistence.getCurrentStamina(accountKey);
        emitInfo("Stamina check: " + level);

        if (level < min) {
            int regenMinutes = staminaRegenerationTime(level, refresh);
            LocalDateTime retry = LocalDateTime.now().plusMinutes(regenMinutes);
            cb.reschedule(retry);
            emitWarn("Insufficient (" + level + "/" + min + ") — retry " +
                    GameTimeUtils.formatCountdown(retry));
            return false;
        }

        if (verifyMarches && !marchSupport.checkMarchesAvailable()) {
            cb.reschedule(LocalDateTime.now().plusMinutes(1));
            emitWarn("No march slots — retry in 1 min");
            return false;
        }

        return true;
    }

    // Reads travel-time from the deployment screen; returns seconds or 0 on failure.
    public long parseTravelTime() {
        Duration parsed = durationReader.attemptRecognition(
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.topLeft(),
                CommonGameAreas.TRAVEL_TIME_OCR_AREA.bottomRight(),
                3, 200L,
                CommonOCRSettings.TRAVEL_TIME_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);

        if (parsed == null) {
            emitWarn("Travel time OCR failed");
            return 0;
        }
        long seconds = parsed.getSeconds();
        emitDebug("Travel estimate: " + seconds + "s");
        return seconds;
    }

    // ── logging shortcuts ────────────────────────────────────────────

    private void emitInfo(String msg) {
        String full = accountLabel + " - " + msg;
        trace.info(full);
        centralLog.emit(TpMessageSeverityEnum.INFO, "StaminaHelper", accountLabel, msg);
    }

    private void emitWarn(String msg) {
        String full = accountLabel + " - " + msg;
        trace.warn(full);
        centralLog.emit(TpMessageSeverityEnum.WARNING, "StaminaHelper", accountLabel, msg);
    }

    private void emitDebug(String msg) {
        String full = accountLabel + " - " + msg;
        trace.debug(full);
        centralLog.emit(TpMessageSeverityEnum.DEBUG, "StaminaHelper", accountLabel, msg);
    }
}
