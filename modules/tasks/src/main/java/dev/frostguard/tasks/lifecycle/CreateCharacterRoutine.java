package dev.frostguard.tasks.lifecycle;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.nav.CommonGameAreas;

/**
 * Task to create character based on state age.
 * 1. Profile -> Settings -> Character -> Create
 * 2. OCR Tips check
 * 3. OCR State Age -> Parse -> Compare -> Accept/Reschedule
 */
public class CreateCharacterRoutine extends DelayedTask {

    private static final int RESCHEDULE_DELAY_MINUTES = 5;

    // --- Coordinates ---
    private static final PointData PROFILE_AVATAR_TL = CommonGameAreas.PROFILE_AVATAR.topLeft();
    private static final PointData PROFILE_AVATAR_BR = CommonGameAreas.PROFILE_AVATAR.bottomRight();
    private static final PointData SETTINGS_BUTTON_TL = new PointData(582, 1197);
    private static final PointData SETTINGS_BUTTON_BR = new PointData(687, 1249);
    private static final PointData CHARACTER_BUTTON = new PointData(198, 339);
    private static final PointData CREATE_CHARACTER_BUTTON = new PointData(162, 312);
    private static final PointData TIPS_OCR_TL = new PointData(312, 411);
    private static final PointData TIPS_OCR_BR = new PointData(408, 469);
    private static final PointData AGE_OCR_TL = new PointData(88, 370);
    private static final PointData AGE_OCR_BR = new PointData(337, 400);
    private static final PointData ACCEPT_BUTTON = new PointData(340, 367);
    private static final PointData CONFIRM_BUTTON = new PointData(507, 780);
    private static final PointData CROSS_BUTTON = new PointData(631, 158);// FIXXX ITTT <----------

    // --- OCR ---
    private static final OcrSettingsData TIPS_OCR_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .stripBackground(true)
            .build();

    private static final OcrSettingsData AGE_OCR_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789() ")
            .stripBackground(true)
            .build();

    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d+)\\s*hour");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*minute");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(\\d+)\\s*second");

    public CreateCharacterRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {

        Integer maxAgeMinutes = profile.getConfig(
                ConfigurationKeyEnum.CREATE_CHARACTER_MAX_AGE_MINUTES_INT,
                Integer.class);

        if (maxAgeMinutes == null || maxAgeMinutes <= 0) {
            logWarning("Wait Max Age config");
            return;
        }

        logInfo("Max age: " + maxAgeMinutes);

        logInfo("Profile");
        tapInside(PROFILE_AVATAR_TL, PROFILE_AVATAR_BR);
        sleepTask(500);

        logInfo("Settings");
        tapInside(SETTINGS_BUTTON_TL, SETTINGS_BUTTON_BR);
        sleepTask(500);

        boolean Created = false;
        while(!Created) {

        logInfo("Character");
        tapNear(CHARACTER_BUTTON);
        sleepTask(500);

        logInfo("Create");
        tapNear(CREATE_CHARACTER_BUTTON);
        sleepTask(500);

        logInfo("Check Tips");
        String tipsText = readStringValue(TIPS_OCR_TL, TIPS_OCR_BR, TIPS_OCR_SETTINGS);

        if (tipsText != null && tipsText.toLowerCase().contains("tips")) {
            logInfo("Change Emulator");
            Created = true; // Ends the loop explicitly
            return;
        }

        logInfo("Check Age");
        sleepTask(5000);

        String ageText = readStringValue(AGE_OCR_TL, AGE_OCR_BR, AGE_OCR_SETTINGS);

        if (ageText == null || ageText.isEmpty()) {
            logWarning("OCR fail. Retry 5m");
            reschedule(LocalDateTime.now().plusMinutes(RESCHEDULE_DELAY_MINUTES));
            Created = true;
            return;
        }

        logInfo("OCR result: " + ageText);

        int ageTotalMinutes = parseAgeToMinutes(ageText);

        if (ageTotalMinutes < 0) {
            logWarning("Parse fail. Retry 5m");
            reschedule(LocalDateTime.now().plusMinutes(RESCHEDULE_DELAY_MINUTES));
            Created = true;
            return;
        }

        logInfo("Age: " + ageTotalMinutes + " min (max: " + maxAgeMinutes + ")");

        if (ageTotalMinutes <= maxAgeMinutes) {
            logInfo("Accepting...");
            tapNear(ACCEPT_BUTTON);
            sleepTask(300);
            tapNear(CONFIRM_BUTTON);
            sleepTask(100);
            Created = true;
            logInfo("Done");
            setRecurring(false); // Task will gracefully finish and not be rescheduled
            
            Boolean skipTutorial = profile.getConfig(
                    ConfigurationKeyEnum.CREATE_CHARACTER_SKIP_TUTORIAL_BOOL,
                    Boolean.class);
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
            if (queue != null) {
                if (Boolean.TRUE.equals(skipTutorial)) {
                    logInfo("Running Skip Tutorial Task...");
                    queue.runNow(TpDailyTaskEnum.SKIP_TUTORIAL, false);
                    
                    logInfo("Removing Create Character task...");
                    queue.dequeue(TpDailyTaskEnum.CREATE_CHARACTER);
                    
                    throw new dev.frostguard.engine.error.TaskPreemptedException("Switching to Skip Tutorial task immediately");
                }
                logInfo("Removing Create Character task...");
                queue.dequeue(TpDailyTaskEnum.CREATE_CHARACTER);
            }
            return;
        } else {
            logInfo("Age > max (" + ageTotalMinutes + "). Retry");
        }
        tapNear(CROSS_BUTTON);
        sleepTask(500);
        }
    }

    /** Parses age string into total minutes. */
    private int parseAgeToMinutes(String ageText) {
        int totalMinutes = 0;
        boolean foundAny = false;

        Matcher hoursMatcher = HOURS_PATTERN.matcher(ageText);
        if (hoursMatcher.find()) {
            totalMinutes += Integer.parseInt(hoursMatcher.group(1)) * 60;
            foundAny = true;
        }

        Matcher minutesMatcher = MINUTES_PATTERN.matcher(ageText);
        if (minutesMatcher.find()) {
            totalMinutes += Integer.parseInt(minutesMatcher.group(1));
            foundAny = true;
        }

        Matcher secondsMatcher = SECONDS_PATTERN.matcher(ageText);
        if (secondsMatcher.find()) {
            foundAny = true;
        }

        return foundAny ? totalMinutes : -1;
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return false;
    }
}
