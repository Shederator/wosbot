package dev.frostguard.tasks.heroes;

import dev.frostguard.engine.schedule.LaunchPoint;


import dev.frostguard.engine.config.PriorityConfigResolver;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.ExpertSkillItemEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.*;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Task for training expert skills based on priority configuration
 * Handles training for Cyrille, Agnes, Holger, and Romulus skills
 */
public class ExpertSkillTrainingRoutine extends DelayedTask {

    public ExpertSkillTrainingRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {

        // Get priority list configuration
        List<PriorityItemData> enabledPriorities = PriorityConfigResolver.activeRankings(
                profile,
                ConfigurationKeyEnum.EXPERT_SKILL_TRAINING_PRIORITIES_STRING);

        if (enabledPriorities.isEmpty()) {
            logWarning("No enabled priorities found for expert skill training. Disabling task.");
            setRecurring(false);
            return;
        }

        // Navigate to experts screen
        marchHelper.openLeftMenuCitySection(true);

        int maxScrollAttempts = 5;
        ImageSearchResultData trainingExpertButton = null;
        for (int attempt = 1; attempt <= maxScrollAttempts; attempt++) {
            swipe(new PointData(255, 477), new PointData(255, 400));
            sleepTask(500);
            trainingExpertButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.LEFT_MENU_EXPERT_TRAINING_BUTTON,
                    SearchConfig.builder().build());
            if (trainingExpertButton.isFound()) {
                break;
            }

        }
        if (!trainingExpertButton.isFound()) {
            logInfo("No training expert found, ending task.");
            reschedule(LocalDateTime.now().plusMinutes(10));
            return;
        }
        tapInside(trainingExpertButton);
        sleepTask(2000);

        ImageSearchResultData speedUpButton = templateSearchHelper.locatePattern(
                TemplatesEnum.EXPERT_TRAINING_SPEEDUP_ICON,
                SearchConfig.builder().build());
        if (speedUpButton.isFound()) {
            // if im here means that there's a skill being trained, get training time and
            // reschedule
            tapInside(speedUpButton.getPoint(), speedUpButton.getPoint(), 1, 500);
            Duration trainingTime = durationHelper.attemptRecognition(
                    new PointData(292, 284),
                    new PointData(432, 314),
                    5,
                    300,
                    null,
                    GameTimeUtils::isAcceptedFormat,
                    GameTimeUtils::parseDuration);
            if (trainingTime == null) {
                return;
            }
            logInfo("A skill is currently being trained. Rescheduling task to run after training completes in "
                    + trainingTime.toMinutes() + " minutes.");
            reschedule(LocalDateTime.now().plus(trainingTime));
            return;
        }
        // scroll down to normalize position
        for (int i = 0; i < 2; i++) {
            emuManager.swipeScreen(EMULATOR_NUMBER, new PointData(358, 1000), new PointData(258, 100));
        }

        // enter on cyrille
        tapInside(new PointData(151, 414), new PointData(227, 465), 3, 1000);

        // map the available experts to not over loop on experts that we don't have
        HashMap<EXPERTS, Boolean> expertAvailabilityMap = new HashMap<>();
        List<EXPERTS> expertsOrderList = new ArrayList<>(); // To keep track of the order of detected experts
        // search all expert badges to know which experts we have, we must search 1 by 1
        // then cling on change expert button (right arrow)

        TemplatesEnum[] expertBadges = {
                TemplatesEnum.EXPERT_TRAINING_CYRILLE_BADGE,
                TemplatesEnum.EXPERT_TRAINING_AGNES_BADGE,
                TemplatesEnum.EXPERT_TRAINING_HOLGER_BADGE,
                TemplatesEnum.EXPERT_TRAINING_ROMULUS_BADGE,
                TemplatesEnum.EXPERT_TRAINING_BALDUR_BADGE,
                TemplatesEnum.EXPERT_TRAINING_FABIAN_BADGE
        };

        for (int i = 0; i < 10; i++) {
            Optional<BadgeSearchResult> foundResult = Arrays.stream(expertBadges)
                    .parallel()
                    .map(expertBadge -> {
                        ImageSearchResultData badge = templateSearchHelper.locatePattern(
                                expertBadge,
                                SearchConfig.builder()
                                        .withThreshold(90)
                                        .withMaxAttempts(2)
                                        .build());
                        EXPERTS expert = getExpertFromTemplate(expertBadge);
                        return new BadgeSearchResult(badge, expert, expertBadge);
                    })
                    .filter(result -> result.badge.isFound())
                    .findAny();

            if (foundResult.isPresent()) {
                BadgeSearchResult result = foundResult.get();
                boolean alreadyAvailable = expertAvailabilityMap.getOrDefault(result.expert, false);

                if (alreadyAvailable) {
                    break;
                }
                expertsOrderList.add(result.expert);
                expertAvailabilityMap.put(result.expert, true);
            } else {
                Arrays.stream(expertBadges)
                        .map(this::getExpertFromTemplate)
                        .forEach(expert -> expertAvailabilityMap.putIfAbsent(expert, false));
            }
            tapNear(new PointData(671, 650)); // right arrow to change expert
            sleepTask(300);
        }

        long availableCount = expertAvailabilityMap.values().stream().filter(Boolean::booleanValue).count();
        if (availableCount == 0) {
            logInfo("No experts found, scheduling to check again in 10 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(10));
            return;
        }

        // print expert availability map
        for (TemplatesEnum expertBadge : expertBadges) {
            EXPERTS expert = getExpertFromTemplate(expertBadge);
            if (expertAvailabilityMap.getOrDefault(expert, false)) {
                logInfo(expert + " is available.");
            } else {
                logInfo(expert + " is not available. Related skills will be skipped.");
            }
        }

        // switch to skills tab
        tapInside(new PointData(500, 1232), new PointData(570, 1251), 1, 500);

        // 3. Iterate through priorities and train skills
        EXPERTS currentExpert = null; // Track the current expert to avoid unnecessary navigation

        for (PriorityItemData priorityItem : enabledPriorities) {
            // check if the expert related is available
            ExpertSkillItemEnum skillItem = ExpertSkillItemEnum.valueOf(priorityItem.getIdentifier().toUpperCase());
            EXPERTS expert = getExpertFromEnum(skillItem);

            if (!expertAvailabilityMap.getOrDefault(expert, false)) {
                logInfo("Skipping " + priorityItem.getName() + " because " + expert + " is not available.");
                continue;
            }
            logInfo("Training skill: " + priorityItem.getName() + " for expert: " + expert);

            // Only navigate to expert if it's different from the current one
            if (currentExpert == null || !currentExpert.equals(expert)) {
                logInfo("Navigating to expert: " + expert);

                // Detect current expert by checking which badge is visible
                if (currentExpert == null) {
                    currentExpert = detectCurrentExpert();
                    if (currentExpert != null) {
                        logInfo("Detected current expert: " + currentExpert);
                    } else {
                        logWarning("Could not detect current expert. Assuming first expert in detected order.");
                        currentExpert = expertsOrderList.isEmpty() ? EXPERTS.CYRILLE : expertsOrderList.get(0);
                    }
                }

                // Calculate how many taps are needed using the dynamically detected order
                int tapsNeeded = calculateTapsToReach(currentExpert, expert, expertsOrderList);

                if (tapsNeeded > 0) {
                    logInfo("Moving from " + currentExpert + " to " + expert + " - tapping " + tapsNeeded + " time(s)");

                    for (int i = 0; i < tapsNeeded; i++) {
                        tapNear(new PointData(671, 650)); // right arrow to change expert
                        sleepTask(300);
                    }
                } else if (tapsNeeded == 0) {
                    logInfo("Already at expert: " + expert);
                }

                // Verify we're on the correct expert by checking the badge
                TemplatesEnum expertBadgeTemplate = getExpertTemplate(skillItem);
                ImageSearchResultData expertBadgeResult = templateSearchHelper.locatePattern(
                        expertBadgeTemplate,
                        SearchConfig.builder()
                                .withThreshold(90)
                                .withMaxAttempts(2)
                                .build());

                if (!expertBadgeResult.isFound()) {
                    logWarning("Could not verify navigation to expert: " + expert
                            + ". Badge not found. Skipping skill: " + priorityItem.getName());
                    continue;
                }

                currentExpert = expert; // Update the current expert
            } else {
                logInfo("Already on expert " + expert + " page, skipping navigation.");
            }

            AreaData skillArea = getSkillArea(skillItem);
            tapInside(skillArea.topLeft(), skillArea.bottomRight(), 1, 300);

            // check if skill is maxed or locked
            ImageSearchResultData learnResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON,
                    SearchConfig.builder()
                            .withThreshold(90)
                            .withMaxAttempts(3)
                            .build());

            if (!learnResult.isFound()) {
                logInfo("Skill " + priorityItem.getName() + " is either maxed or locked. Skipping.");
                continue;
            }

            tapInside(learnResult);
            sleepTask(500);

            // check if the skill have pending points to learn, lets search the learn button
            // again
            learnResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.EXPERT_TRAINING_LEARN_BUTTON,
                    SearchConfig.builder()
                            .withThreshold(90)
                            .withMaxAttempts(3)
                            .build());
            if (learnResult.isFound()) {
                logInfo("Skill " + priorityItem.getName() + " has no available skill points to learn. Skipping.");
                tapInside(new PointData(360, 33), new PointData(374, 44), 3, 300);
                continue;
            }

            // we must try with the times in decreasing order to not waste time 23h -> 10h
            // -> 2h -> 10m (if 10 also fails, use it as last resort)
            List<LearningTime> timesDescending = List.of(
                    LearningTime.TIME_23_00_00,
                    LearningTime.TIME_10_00_00,
                    LearningTime.TIME_02_00_00,
                    LearningTime.TIME_00_10_00);

            for (LearningTime learningTime : timesDescending) {
                AreaData timeCheckboxArea = getLearningTimeCheckbox(learningTime);
                tapInside(timeCheckboxArea.topLeft(), timeCheckboxArea.bottomRight(), 1, 300);
                tapInside(new PointData(474, 888), new PointData(579, 910), 1, 400);
                // check if the pop-up disappeared, that mean it was successful

                ImageSearchResultData badgeResult = templateSearchHelper.locatePattern(
                        getExpertTemplate(skillItem),
                        SearchConfig.builder()
                                .withThreshold(90)
                                .withMaxAttempts(3)
                                .build());

                if (badgeResult.isFound()) {
                    logInfo("Successfully started training for skill: " + priorityItem.getName() + " with duration: "
                            + learningTime.label());
                    this.reschedule(LocalDateTime.now().plus(learningTime.duration())); // add 1 minute buffer
                    pressBack();
                    pressBack();
                    return;
                } else {
                    tapInside(new PointData(284, 329), new PointData(452, 359), 1, 300);
                    if (learningTime.equals(LearningTime.TIME_00_10_00)) {
                        logInfo("All time options failed, but skill training did not start. Forcing 10 minutes option as last resort.");
                        tapInside(timeCheckboxArea.topLeft(), timeCheckboxArea.bottomRight(), 1, 400);
                        tapInside(new PointData(454, 777), new PointData(573, 800), 1, 400);
                        return;
                    }
                }
            }
        }
    }

    private EXPERTS getExpertFromEnum(ExpertSkillItemEnum expertSkillItem) {
        return switch (expertSkillItem) {
            case AGNES_SLOT_1, AGNES_SLOT_2, AGNES_SLOT_3, AGNES_SLOT_4 -> EXPERTS.AGNES;
            case CYRILLE_SLOT_1, CYRILLE_SLOT_2, CYRILLE_SLOT_3, CYRILLE_SLOT_4 -> EXPERTS.CYRILLE;
            case HOLGER_SLOT_1, HOLGER_SLOT_2, HOLGER_SLOT_3, HOLGER_SLOT_4 -> EXPERTS.HOLGER;
            case ROMULUS_SLOT_1, ROMULUS_SLOT_2, ROMULUS_SLOT_3, ROMULUS_SLOT_4 -> EXPERTS.ROMULUS;
            case BALDUR_SLOT_1, BALDUR_SLOT_2, BALDUR_SLOT_3, BALDUR_SLOT_4 -> EXPERTS.BALDUR;
            case FABIAN_SLOT_1, FABIAN_SLOT_2, FABIAN_SLOT_3, FABIAN_SLOT_4 -> EXPERTS.FABIAN;
        };
    }

    public TemplatesEnum getExpertTemplate(ExpertSkillItemEnum expertSkillItem) {

        return switch (expertSkillItem) {
            case AGNES_SLOT_1, AGNES_SLOT_2, AGNES_SLOT_3, AGNES_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_AGNES_BADGE;
            case CYRILLE_SLOT_1, CYRILLE_SLOT_2, CYRILLE_SLOT_3, CYRILLE_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_CYRILLE_BADGE;
            case HOLGER_SLOT_1, HOLGER_SLOT_2, HOLGER_SLOT_3, HOLGER_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_HOLGER_BADGE;
            case ROMULUS_SLOT_1, ROMULUS_SLOT_2, ROMULUS_SLOT_3, ROMULUS_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_ROMULUS_BADGE;
            case BALDUR_SLOT_1, BALDUR_SLOT_2, BALDUR_SLOT_3, BALDUR_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_BALDUR_BADGE;
            case FABIAN_SLOT_1, FABIAN_SLOT_2, FABIAN_SLOT_3, FABIAN_SLOT_4 ->
                TemplatesEnum.EXPERT_TRAINING_FABIAN_BADGE;
        };

    }

    private AreaData getSkillArea(ExpertSkillItemEnum skillItem) {
        return switch (skillItem) {
            case CYRILLE_SLOT_1, AGNES_SLOT_1, HOLGER_SLOT_1, ROMULUS_SLOT_1, BALDUR_SLOT_1, FABIAN_SLOT_1 ->
                new AreaData(new PointData(62, 1032), new PointData(132, 1102));
            case CYRILLE_SLOT_2, AGNES_SLOT_2, HOLGER_SLOT_2, ROMULUS_SLOT_2, BALDUR_SLOT_2, FABIAN_SLOT_2 ->
                new AreaData(new PointData(237, 1032), new PointData(307, 1102));
            case CYRILLE_SLOT_3, AGNES_SLOT_3, HOLGER_SLOT_3, ROMULUS_SLOT_3, BALDUR_SLOT_3, FABIAN_SLOT_3 ->
                new AreaData(new PointData(412, 1032), new PointData(482, 1102));
            case CYRILLE_SLOT_4, AGNES_SLOT_4, HOLGER_SLOT_4, ROMULUS_SLOT_4, BALDUR_SLOT_4, FABIAN_SLOT_4 ->
                new AreaData(new PointData(587, 1032), new PointData(657, 1102));
        };
    }

    private EXPERTS getExpertFromTemplate(TemplatesEnum template) {

        return switch (template) {
            case EXPERT_TRAINING_AGNES_BADGE -> EXPERTS.AGNES;
            case EXPERT_TRAINING_CYRILLE_BADGE -> EXPERTS.CYRILLE;
            case EXPERT_TRAINING_HOLGER_BADGE -> EXPERTS.HOLGER;
            case EXPERT_TRAINING_ROMULUS_BADGE -> EXPERTS.ROMULUS;
            case EXPERT_TRAINING_BALDUR_BADGE -> EXPERTS.BALDUR;
            case EXPERT_TRAINING_FABIAN_BADGE -> EXPERTS.FABIAN;
            default -> null;
        };
    }

    /**
     * Detect which expert is currently visible by searching for their badge
     */
    private EXPERTS detectCurrentExpert() {
        TemplatesEnum[] expertBadges = {
                TemplatesEnum.EXPERT_TRAINING_CYRILLE_BADGE,
                TemplatesEnum.EXPERT_TRAINING_AGNES_BADGE,
                TemplatesEnum.EXPERT_TRAINING_ROMULUS_BADGE,
                TemplatesEnum.EXPERT_TRAINING_HOLGER_BADGE,
                TemplatesEnum.EXPERT_TRAINING_BALDUR_BADGE,
                TemplatesEnum.EXPERT_TRAINING_FABIAN_BADGE
        };

        for (TemplatesEnum badge : expertBadges) {
            ImageSearchResultData result = templateSearchHelper.locatePattern(
                    badge,
                    SearchConfig.builder()
                            .withThreshold(90)
                            .withMaxAttempts(1)
                            .build());
            if (result.isFound()) {
                return getExpertFromTemplate(badge);
            }
        }

        return null; // Could not detect current expert
    }

    /**
     * Calculate how many taps are needed to reach the target expert
     * considering only the available experts (carousel is dynamic)
     * Example: If only CYRILLE, AGNES, ROMULUS are available (HOLGER is missing):
     * - From CYRILLE to AGNES = 1 tap
     * - From AGNES to ROMULUS = 1 tap
     * - From ROMULUS to CYRILLE = 1 tap (wraps around, skipping HOLGER)
     */
    private int calculateTapsToReach(EXPERTS current, EXPERTS target, List<EXPERTS> availableExperts) {
        if (current.equals(target)) {
            return 0;
        }

        int currentIndex = availableExperts.indexOf(current);
        int targetIndex = availableExperts.indexOf(target);

        if (currentIndex == -1 || targetIndex == -1) {
            logWarning("Expert not found in available list. Current: " + current + ", Target: " + target);
            return -1;
        }

        // Calculate distance in the circular list
        int size = availableExperts.size();
        int distance;

        if (targetIndex > currentIndex) {
            distance = targetIndex - currentIndex;
        } else {
            // Wrap around
            distance = (size - currentIndex) + targetIndex;
        }

        return distance;
    }

    public record LearningTime(String label, Duration duration) {

        public static final LearningTime TIME_00_10_00 = new LearningTime("00:10:00", Duration.ofMinutes(10));
        public static final LearningTime TIME_02_00_00 = new LearningTime("02:00:00", Duration.ofHours(2));
        public static final LearningTime TIME_10_00_00 = new LearningTime("10:00:00", Duration.ofHours(10));
        public static final LearningTime TIME_23_00_00 = new LearningTime("23:00:00", Duration.ofHours(23));

    }

    private AreaData getLearningTimeCheckbox(LearningTime time) {
        if (time.equals(LearningTime.TIME_00_10_00)) {
            return new AreaData(new PointData(90, 686), new PointData(114, 711));
        } else if (time.equals(LearningTime.TIME_02_00_00)) {
            return new AreaData(new PointData(373, 688), new PointData(402, 713));
        } else if (time.equals(LearningTime.TIME_10_00_00)) {
            return new AreaData(new PointData(90, 766), new PointData(116, 792));
        } else if (time.equals(LearningTime.TIME_23_00_00)) {
            return new AreaData(new PointData(373, 765), new PointData(403, 792));
        }
        throw new IllegalArgumentException("Unknown learning time: " + time);
    }

    private enum EXPERTS {
        CYRILLE(1),
        AGNES(2),
        HOLGER(3),
        ROMULUS(4),
        BALDUR(5),
        FABIAN(6);

        EXPERTS(int position) {
            // position not used but kept for potential future use
        }
    }

    record BadgeSearchResult(ImageSearchResultData badge, EXPERTS expert, TemplatesEnum template) {
    }

}
