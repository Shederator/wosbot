package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.tasks.diagnostics.TaskControlSignals;

/**
 * Task responsible for claiming rewards from the Storehouse.
 * 
 * <p>
 * This task:
 * <ul>
 * <li>Navigates to the Storehouse via Research Center</li>
 * <li>Claims daily chest rewards (available every few hours)</li>
 * <li>Claims a visible stamina can on the same visit</li>
 * <li>Reads the on-building countdown via OCR to schedule the next chest visit</li>
 * </ul>
 * 
 * <p>
 * <b>Reward Types:</b>
 * <ul>
 * <li>Chest: General resources, multiple claims per day</li>
 * <li>Stamina: 120 base stamina + bonus from Agnes expert</li>
 * </ul>
 */
public class StorehouseChestRoutine extends DelayedTask {

    // ========== Navigation Coordinates ==========
    private static final AreaData STOREHOUSE_VISIBLE_BUILDING_AREA = new AreaData(
            new PointData(105, 530), new PointData(125, 550));
    private static final AreaData STOREHOUSE_TITLE_AREA = new AreaData(
            new PointData(245, 515), new PointData(505, 575));
    static final AreaData CHEST_REWARD_OVERLAY_CLOSE_AREA = AreaData.of(565, 530, 715, 705);
    private static final int STOREHOUSE_SELECTION_SETTLE_MILLIS = 2_200;
    private static final int STOREHOUSE_DESELECTION_SETTLE_MILLIS = 800;

    // ========== Stamina Reward Coordinates ==========
    private static final PointData STAMINA_AMOUNT_TOP_LEFT = new PointData(436, 632);
    private static final PointData STAMINA_AMOUNT_BOTTOM_RIGHT = new PointData(487, 657);
    private static final AreaData STAMINA_REWARD_TITLE_AREA = new AreaData(
            new PointData(170, 750), new PointData(550, 825));
    private static final AreaData STAMINA_CLAIM_BUTTON_AREA = new AreaData(
            new PointData(200, 900), new PointData(520, 1020));
    private static final AreaData STAMINA_TOOLTIP_TITLE_AREA = new AreaData(
            new PointData(45, 670), new PointData(300, 750));

    // ========== Fallback Timer OCR ==========
    static final PointData FALLBACK_TIMER_TOP_LEFT = new PointData(285, 642);
    static final PointData FALLBACK_TIMER_BOTTOM_RIGHT = new PointData(430, 666);

    // ========== Constants ==========
    private static final int TIMER_OCR_MAX_ATTEMPTS = 3;
    private static final int BUBBLE_SEARCH_ATTEMPTS = 6;
    private static final long BUBBLE_SEARCH_DELAY_MILLIS = 250L;
    private static final int CLAIM_CLOSE_SETTLE_MILLIS = 800;
    private static final int BUBBLE_CONFIRMATION_MARGIN = 24;
    private static final int CLAIM_BUTTON_POLL_INTERVAL_MILLIS = 400;
    private static final int CLAIM_BUTTON_TIMEOUT_MILLIS = 5_000;
    private static final int REWARD_TITLE_THRESHOLD = 88;
    private static final int CLAIM_BUTTON_THRESHOLD = 88;
    private static final int TOOLTIP_TITLE_THRESHOLD = 88;
    private static final String BUILDING_COUNTDOWN_WHITELIST = "0123456789:d";
    private static final int BASE_STOREHOUSE_STAMINA = 120;

    // On-building countdown glyphs measured on a 720x1280 city frame.
    static final Color BUILDING_TIMER_GREEN = new Color(61, 216, 13);

    // ========== OCR Settings ==========
    private static final OcrSettingsData STAMINA_OCR_SETTINGS = OcrSettingsData.assembler()
            .setTextColor(new Color(248, 247, 234))
            .stripBackground(true)
            .charWhitelist("0123456789")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

            .build();

    private ResilientOcrExecutor<String> textHelper;
    private StorehouseVisitFlow.VisitState visitState = StorehouseVisitFlow.VisitState.READY;
    private StorehouseVisitFlow.ActivityPhase activityPhase = StorehouseVisitFlow.ActivityPhase.RESCHEDULING;
    private Integer pendingAgnesStamina;

    public StorehouseChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected void execute() {
        this.textHelper = new ResilientOcrExecutor<>(provider);
        pendingAgnesStamina = null;
        setVisitState(StorehouseVisitFlow.VisitState.READY, "New execute() visit; state recalculated in memory");

        StorehouseVisitFlow.VisitDecision decision;
        try {
            if (openStorehouse()) {
                decision = StorehouseVisitFlow.execute(new StorehouseActions());
            } else {
                captureInterfaceFailure("storehouse-open");
                decision = StorehouseVisitFlow.retryBeforeFlow("Storehouse could not be opened.");
            }
        } catch (RuntimeException failure) {
            TaskControlSignals.rethrowControlSignal(failure);
            logError("Storehouse visit failed unexpectedly; retrying in five minutes: " + failure.getMessage());
            captureInterfaceFailure("visit-error");
            decision = StorehouseVisitFlow.retryBeforeFlow("Unexpected visit error: "
                    + failure.getClass().getSimpleName());
        }

        setActivityPhase(StorehouseVisitFlow.ActivityPhase.RESCHEDULING);
        setVisitState(decision.state(), decision.reason());
        LocalDateTime scheduledTime = LocalDateTime.now().plus(decision.delay());
        logInfo(String.format("Storehouse visit state %s; %s. Confirmed collections: chest=%d, stamina=%d. "
                        + "Rescheduling once for %s at %s.",
                visitState, decision.reason(), decision.confirmedChestCollections(),
                decision.confirmedStaminaCollections(), decision.delay(),
                scheduledTime.format(DATETIME_FORMATTER)));
        reschedule(scheduledTime);
    }

    /**
     * Opens the Storehouse from the stable Research Center sidebar anchor.
     */
    private boolean openStorehouse() {
        logDebug("Navigating to Storehouse");

        if (!navigationHelper.navigateToSidebarDestination(SidebarDestination.RESEARCH_CENTER)) {
            logError("Research Center sidebar destination not reached.");
            return false;
        }

        // The Research Center centers the city with a visible part of the Storehouse at the left.
        // Selecting that stable visible area avoids a momentum-sensitive map pan.
        tapInside(STOREHOUSE_VISIBLE_BUILDING_AREA.topLeft(), STOREHOUSE_VISIBLE_BUILDING_AREA.bottomRight());
        sleepTask(STOREHOUSE_SELECTION_SETTLE_MILLIS);

        ImageSearchResultData selected = templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_SELECTED_CURRENT,
                SearchConfig.builder()
                        .withArea(STOREHOUSE_TITLE_AREA)
                        .withThreshold(85)
                        .withMaxAttempts(3)
                        .withDelay(300L)
                        .build());
        if (selected.isFound()) {
            logInfo("Storehouse selected and verified by its title anchor.");
            // The reward bubbles are only actionable after Android Back closes the
            // building's Details/Upgrade selection controls.
            pressBack();
            sleepTask(STOREHOUSE_DESELECTION_SETTLE_MILLIS);
            logInfo("Storehouse selection controls closed; reward bubbles are now accessible.");
            return true;
        }

        logWarning("Storehouse title anchor was not detected after the direct selection.");
        return false;
    }

    private final class StorehouseActions implements StorehouseVisitFlow.Actions<StorehouseBubbleDetector.Candidate> {

        @Override
        public StorehouseVisitFlow.Observation<StorehouseBubbleDetector.Candidate> findChest() {
            return observeBubble(StorehouseBubbleDetector.Kind.CHEST, StorehouseBubbleDetector.SEARCH_AREA);
        }

        @Override
        public StorehouseVisitFlow.Observation<StorehouseBubbleDetector.Candidate> findStamina() {
            return observeBubble(StorehouseBubbleDetector.Kind.STAMINA, StorehouseBubbleDetector.SEARCH_AREA);
        }

        @Override
        public StorehouseVisitFlow.CollectionState collectChest(StorehouseBubbleDetector.Candidate candidate) {
            logInfo("Chest found. Claiming reward.");
            tapInside(candidate.center(), candidate.center());
            sleepTask(500);
            tapInside(CHEST_REWARD_OVERLAY_CLOSE_AREA);
            sleepTask(CLAIM_CLOSE_SETTLE_MILLIS);
            return confirmBubbleDisappeared(candidate, StorehouseBubbleDetector.Kind.CHEST);
        }

        @Override
        public StorehouseVisitFlow.CollectionState collectStamina(
                StorehouseBubbleDetector.Candidate candidate) {
            logInfo("Stamina bubble found. Opening its claim dialog.");
            tapInside(candidate.center(), candidate.center());
            ImageSearchResultData claimButton = awaitStaminaClaimButton();
            if (claimButton == null) {
                captureInterfaceFailure("stamina-claim-button-missing");
                return StorehouseVisitFlow.CollectionState.UNCONFIRMED;
            }

            pendingAgnesStamina = readAgnesBonus();
            logDebug("Agnes stamina OCR result: "
                    + (pendingAgnesStamina == null ? "unreadable" : pendingAgnesStamina));
            logInfo("Stamina Claim button confirmed. Claiming the reward.");
            tapInside(claimButton);
            sleepTask(4_000);
            StorehouseVisitFlow.CollectionState confirmation = confirmBubbleDisappeared(
                    candidate, StorehouseBubbleDetector.Kind.STAMINA);
            if (confirmation == StorehouseVisitFlow.CollectionState.CONFIRMED) {
                StaminaService.getServices().addExternalStamina(profile.getId(), BASE_STOREHOUSE_STAMINA);
                if (pendingAgnesStamina != null && pendingAgnesStamina > 0) {
                    StaminaService.getServices().addExternalStamina(profile.getId(), pendingAgnesStamina);
                    logInfo(String.format("Confirmed stamina collection: %d base + %d Agnes bonus.",
                            BASE_STOREHOUSE_STAMINA, pendingAgnesStamina));
                } else {
                    logInfo("Confirmed stamina collection: " + BASE_STOREHOUSE_STAMINA + " base stamina.");
                }
            }
            pendingAgnesStamina = null;
            return confirmation;
        }

        @Override
        public StorehouseVisitFlow.CooldownRead readCooldown() {
            return StorehouseChestRoutine.this.readCooldown();
        }

        @Override
        public void onPhase(StorehouseVisitFlow.ActivityPhase phase) {
            setActivityPhase(phase);
            if (phase == StorehouseVisitFlow.ActivityPhase.READING_COOLDOWN) {
                setVisitState(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN,
                        "Both reward types were absent on reliable city scans");
            }
        }
    }

    private StorehouseVisitFlow.CollectionState confirmBubbleDisappeared(
            StorehouseBubbleDetector.Candidate clicked, StorehouseBubbleDetector.Kind kind) {
        AreaData checkArea = expandedArea(clicked.bounds(), BUBBLE_CONFIRMATION_MARGIN);
        StorehouseVisitFlow.Observation<StorehouseBubbleDetector.Candidate> observation = observeBubble(kind, checkArea);
        if (observation.state() == StorehouseVisitFlow.ObservationState.ABSENT) {
            logInfo("Confirmed " + kind.name().toLowerCase(Locale.ROOT)
                    + " bubble disappeared after collection.");
            return StorehouseVisitFlow.CollectionState.CONFIRMED;
        }
        captureInterfaceFailure(kind.name().toLowerCase(Locale.ROOT) + "-collection-unconfirmed");
        return observation.state() == StorehouseVisitFlow.ObservationState.UNKNOWN
                ? StorehouseVisitFlow.CollectionState.UNKNOWN
                : StorehouseVisitFlow.CollectionState.UNCONFIRMED;
    }

    private StorehouseVisitFlow.Observation<StorehouseBubbleDetector.Candidate> observeBubble(
            StorehouseBubbleDetector.Kind kind, AreaData area) {
        ImageSearchResultData cityAnchor = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE);
        if (cityAnchor == null || !cityAnchor.isFound()) {
            captureInterfaceFailure(kind.name().toLowerCase(Locale.ROOT) + "-scan-not-in-city");
            logWarning("Storehouse " + kind.name().toLowerCase(Locale.ROOT)
                    + " scan is unknown: city anchor missing.");
            return StorehouseVisitFlow.Observation.unknown();
        }

        boolean captureFailed = false;
        for (int attempt = 1; attempt <= BUBBLE_SEARCH_ATTEMPTS; attempt++) {
            try {
                RawImageData capture = emuManager.captureScreen(EMULATOR_NUMBER);
                BufferedImage frame = ImageConverter.toBufferedImage(capture);
                List<StorehouseBubbleDetector.Candidate> candidates = StorehouseBubbleDetector.locate(frame, area);
                for (StorehouseBubbleDetector.Candidate candidate : candidates) {
                    if (candidate.kind() == kind) {
                        logDebug("Storehouse " + kind.name().toLowerCase(Locale.ROOT) + " bubble found");
                        return StorehouseVisitFlow.Observation.found(candidate);
                    }
                }
            } catch (RuntimeException failure) {
                TaskControlSignals.rethrowControlSignal(failure);
                captureFailed = true;
                logWarning("Storehouse " + kind.name().toLowerCase(Locale.ROOT)
                        + " scan capture failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            if (attempt < BUBBLE_SEARCH_ATTEMPTS) {
                sleepTask(BUBBLE_SEARCH_DELAY_MILLIS);
            }
        }
        if (captureFailed) {
            captureInterfaceFailure(kind.name().toLowerCase(Locale.ROOT) + "-scan-unknown");
            return StorehouseVisitFlow.Observation.unknown();
        }
        logDebug("Storehouse " + kind.name().toLowerCase(Locale.ROOT) + " bubble absent after stable scan");
        return StorehouseVisitFlow.Observation.absent();
    }

    private ImageSearchResultData awaitStaminaClaimButton() {
        StorehouseStaminaClaimFlow.Result result = StorehouseStaminaClaimFlow.awaitClaimButton(
                new StorehouseStaminaClaimFlow.Actions() {
                    @Override
                    public boolean isRewardDialogVisible() {
                        ImageSearchResultData title = locateStaminaDialogTemplate(
                                TemplatesEnum.STOREHOUSE_STAMINA_REWARD_TITLE,
                                STAMINA_REWARD_TITLE_AREA,
                                REWARD_TITLE_THRESHOLD);
                        return title != null && title.isFound();
                    }

                    @Override
                    public ImageSearchResultData findClaimButton() {
                        return locateStaminaDialogTemplate(
                                TemplatesEnum.STOREHOUSE_STAMINA_CLAIM_TEXT,
                                STAMINA_CLAIM_BUTTON_AREA,
                                CLAIM_BUTTON_THRESHOLD);
                    }

                    @Override
                    public boolean isTooltipVisible() {
                        return isStaminaTooltipVisible();
                    }

                    @Override
                    public void pressBack() {
                        logInfo("Stamina tooltip confirmed after opening the Storehouse reward; dismissing it once with Back.");
                        StorehouseChestRoutine.this.pressBack();
                    }

                    @Override
                    public void waitAfterBack() {
                        sleepTask(CLAIM_CLOSE_SETTLE_MILLIS);
                    }

                    @Override
                    public void waitForNextPoll() {
                        sleepTask(CLAIM_BUTTON_POLL_INTERVAL_MILLIS);
                    }
                }, Duration.ofMillis(CLAIM_BUTTON_TIMEOUT_MILLIS), System::nanoTime);
        if (result.claimButton() != null) {
            return result.claimButton();
        }
        logWarning("Storehouse stamina reward was not verified with a Claim button"
                + (result.rewardDialogSeen() ? "; reward dialog was visible." : "; reward dialog title was not visible."));
        return null;
    }

    private ImageSearchResultData locateStaminaDialogTemplate(
            TemplatesEnum template, AreaData area, int threshold) {
        return templateSearchHelper.locatePattern(template, SearchConfig.builder()
                .withArea(area)
                .withThreshold(threshold)
                .withMaxAttempts(1)
                .withDelay(0)
                .build());
    }

    private boolean isStaminaTooltipVisible() {
        ImageSearchResultData tooltipTitle = locateStaminaDialogTemplate(
                TemplatesEnum.STOREHOUSE_STAMINA_TOOLTIP_TITLE,
                STAMINA_TOOLTIP_TITLE_AREA,
                TOOLTIP_TITLE_THRESHOLD);
        return tooltipTitle != null && tooltipTitle.isFound();
    }

    private Integer readAgnesBonus() {
        return integerHelper.attemptRecognition(
                STAMINA_AMOUNT_TOP_LEFT,
                STAMINA_AMOUNT_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                STAMINA_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));
    }

    private StorehouseVisitFlow.CooldownRead readCooldown() {
        logInfo("Neither Storehouse reward bubble is present; reading the cooldown.");
        String timer = readBuildingCountdown(buildingCountdownSettings());
        if (timer == null) {
            timer = readBuildingCountdown(buildingCountdownWhiteSettings());
        }
        if (timer == null) {
            logWarning("Storehouse cooldown OCR was unreadable; using the one-hour fallback.");
            return StorehouseVisitFlow.CooldownRead.unreadable("OCR returned no accepted countdown");
        }

        logInfo("Storehouse cooldown OCR read: '" + timer + "'.");
        try {
            Duration cooldown = GameTimeUtils.parseDuration(timer);
            if (cooldown.isZero() || cooldown.isNegative()) {
                return StorehouseVisitFlow.CooldownRead.invalid("Countdown was not positive: " + timer);
            }
            return StorehouseVisitFlow.CooldownRead.valid(cooldown);
        } catch (RuntimeException invalid) {
            return StorehouseVisitFlow.CooldownRead.invalid("Countdown could not be parsed: " + timer);
        }
    }

    private String readBuildingCountdown(OcrSettingsData settings) {
        return textHelper.attemptRecognition(
                FALLBACK_TIMER_TOP_LEFT,
                FALLBACK_TIMER_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                settings,
                GameTimeUtils::isAcceptedFormat,
                text -> text);
    }

    private void captureInterfaceFailure(String situation) {
        String snapshot = TaskDiagnosticSnapshots.capture(
                emuManager, EMULATOR_NUMBER, "storehousechest", situation);
        logWarning("Storehouse interface outcome uncertain; diagnostic snapshot: " + snapshot);
    }

    private void setVisitState(StorehouseVisitFlow.VisitState next, String reason) {
        if (visitState != next) {
            logInfo("Storehouse visit state: " + visitState + " -> " + next + " (" + reason + ")");
        } else {
            logInfo("Storehouse visit state remains " + next + " (" + reason + ")");
        }
        visitState = next;
    }

    private void setActivityPhase(StorehouseVisitFlow.ActivityPhase next) {
        if (activityPhase != next) {
            logInfo("Storehouse activity phase: " + activityPhase + " -> " + next);
            activityPhase = next;
        }
    }

    private AreaData expandedArea(AreaData area, int margin) {
        RawImageData raw = emuManager.captureScreen(EMULATOR_NUMBER);
        BufferedImage frame = ImageConverter.toBufferedImage(raw);
        int left = Math.max(0, area.topLeft().getX() - margin);
        int top = Math.max(0, area.topLeft().getY() - margin);
        int right = Math.min(frame.getWidth() - 1, area.bottomRight().getX() + margin);
        int bottom = Math.min(frame.getHeight() - 1, area.bottomRight().getY() + margin);
        return new AreaData(new PointData(left, top), new PointData(right, bottom));
    }

    static OcrSettingsData buildingCountdownSettings() {
        return buildingCountdownSettings(BUILDING_TIMER_GREEN);
    }

    static OcrSettingsData buildingCountdownWhiteSettings() {
        return buildingCountdownSettings(Color.WHITE);
    }

    private static OcrSettingsData buildingCountdownSettings(Color textColor) {
        return OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(textColor)
                .charWhitelist(BUILDING_COUNTDOWN_WHITELIST)
                .build();
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }
}
