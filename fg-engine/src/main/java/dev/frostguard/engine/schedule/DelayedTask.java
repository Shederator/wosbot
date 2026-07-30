package dev.frostguard.engine.schedule;

import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.data.repository.ProfileRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.OcrDebugContext;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.helper.*;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.schedule.preempt.PreemptionToken;
import dev.frostguard.engine.schedule.inject.InjectionRule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

// Foundation class for all game automation tasks. Provides scheduling
// primitives, profile lifecycle, helper wiring, screen-state validation,
// emulator shortcuts, and cooperative preemption/injection.
public abstract class DelayedTask implements Runnable, Delayed {

    // ── core fields ─────────────────────────────────────────────────
    protected volatile boolean recurring = true;
    protected LocalDateTime lastExecutionTime;
    protected LocalDateTime scheduledTime;
    protected String taskName;
    protected AccountDescriptor profile;
    protected String EMULATOR_NUMBER;
    protected TpDailyTaskEnum tpTask;
    protected boolean shouldUpdateConfig;
    protected boolean isInjecting = false;
    private Integer customPriority = null;
    private int repeatIntervalMinutes = 0;
    private String customTaskIdentifier;

    // ── services ────────────────────────────────────────────────────
    protected EmulatorController emuManager = EmulatorController.getInstance();
    protected ScheduleService scheduleService = ScheduleService.obtain();
    protected LoggingService loggingService = LoggingService.obtain();
    private ProfileContextLogger logger;
    protected int currentOcrFailures = 0;

    // ── OCR providers ───────────────────────────────────────────────
    protected BotOcrEngine provider;
    protected ResilientOcrExecutor<Integer> integerHelper;
    protected ResilientOcrExecutor<Duration> durationHelper;
    protected ResilientOcrExecutor<String> stringHelper;

    // ── game helpers ────────────────────────────────────────────────
    protected NavigationHelper navigationHelper;
    protected TemplateSearchHelper templateSearchHelper;
    protected StaminaHelper staminaHelper;
    protected MarchHelper marchHelper;
    protected DeploymentHelper deploymentHelper;
    protected IntelScreenHelper intelScreenHelper;
    protected AllianceHelper allianceHelper;
    protected EventHelper eventHelper;

    // ── formatters ──────────────────────────────────────────────────
    protected static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    protected static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    // Changed by pernerch | Date: 2026-07-02 | Why: force stamina OCR refresh after
    // emulator-local profile switches so stale stamina values cannot leak between accounts.
    private static final Map<String, Long> LAST_ACTIVE_PROFILE_BY_EMULATOR = new ConcurrentHashMap<>();

        // Changed by pernerch | Date: 2026-07-02 | Why: reserve marches for Bear Trap when the
        // profile is configured to actively play the event, while only blocking rally-heavy events otherwise.
        private static final EnumSet<TpDailyTaskEnum> BEAR_LOCKED_MARCH_TASKS = EnumSet.of(
            TpDailyTaskEnum.GATHER_RESOURCES,
            TpDailyTaskEnum.INTEL,
            TpDailyTaskEnum.BEAST_HUNTING,
            TpDailyTaskEnum.EVENT_POLAR_TERROR,
            TpDailyTaskEnum.EVENT_HERO_MISSION,
            TpDailyTaskEnum.MERCENARY_EVENT,
            TpDailyTaskEnum.EVENT_BERSERK_CRYPTID,
            TpDailyTaskEnum.PET_SKILLS
        );

        // Changed by pernerch | Date: 2026-07-02 | Why: when Bear Trap is scheduled but not actively
        // consuming general marches, only rally-heavy event tasks should stay out of the way.
        private static final EnumSet<TpDailyTaskEnum> BEAR_LOCKED_RALLY_EVENT_TASKS = EnumSet.of(
            TpDailyTaskEnum.EVENT_POLAR_TERROR,
            TpDailyTaskEnum.EVENT_HERO_MISSION,
            TpDailyTaskEnum.MERCENARY_EVENT,
            TpDailyTaskEnum.EVENT_BERSERK_CRYPTID
        );

    // ── preemption ──────────────────────────────────────────────────
    private PreemptionToken preemptionToken;

    // ── construction ────────────────────────────────────────────────

    public DelayedTask(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        this.profile = profile;
        this.taskName = tpTask.getName();
        this.scheduledTime = LocalDateTime.now();
        this.EMULATOR_NUMBER = profile.getEmulatorNumber();
        this.tpTask = tpTask;
        this.logger = new ProfileContextLogger(this.getClass(), profile);

        // wire OCR + helpers in a single pass
        this.provider = new BotOcrEngine(emuManager, EMULATOR_NUMBER);
        this.integerHelper = new ResilientOcrExecutor<>(provider);
        this.durationHelper = new ResilientOcrExecutor<>(provider);
        this.stringHelper = new ResilientOcrExecutor<>(provider);

        this.templateSearchHelper = new TemplateSearchHelper(emuManager, EMULATOR_NUMBER, profile);
        this.templateSearchHelper.setPreemptionCheck(this::checkPreemption);
        this.navigationHelper = new NavigationHelper(emuManager, EMULATOR_NUMBER, profile);
        this.marchHelper = new MarchHelper(emuManager, EMULATOR_NUMBER, stringHelper, profile);
        this.deploymentHelper = new DeploymentHelper(emuManager, EMULATOR_NUMBER, templateSearchHelper, profile);
        this.staminaHelper = new StaminaHelper(emuManager, EMULATOR_NUMBER, integerHelper,
                durationHelper, profile, marchHelper);
        this.intelScreenHelper = new IntelScreenHelper(emuManager, EMULATOR_NUMBER,
                templateSearchHelper, navigationHelper, profile);
        this.allianceHelper = new AllianceHelper(emuManager, EMULATOR_NUMBER,
                templateSearchHelper, navigationHelper, profile);
        this.eventHelper = new EventHelper(emuManager, EMULATOR_NUMBER, profile);
    }

    // ── abstract / hook methods ─────────────────────────────────────

    protected abstract void execute();

    protected LaunchPoint getRequiredStartLocation() { return LaunchPoint.ANY; }

    public boolean provideDailyMissionProgress() { return false; }

    public boolean provideTriumphProgress() { return false; }

    protected boolean consumesStamina() { return false; }

    protected boolean acceptsInjections() { return true; }

    protected Object getDistinctKey() {
        return customTaskIdentifier;
    }

    // ── main entry point ────────────────────────────────────────────

    @Override
    public void run() {
        refreshProfileFromDb();
        boolean switchedProfileOnEmulator = markAndDetectProfileSwitchFlow();
        if (switchedProfileOnEmulator) {
            // Changed by pernerch | Date: 2026-07-02 | Why: publish active-profile changes
            // so UI title/profile context tracks the account currently controlling the emulator.
            scheduleService.notifyActiveProfile(profile.getId());
        }
        long t0 = System.currentTimeMillis();
        int baselineOcr = this.currentOcrFailures;
        int baselineTemplate = this.templateSearchHelper.getFailedSearches();
        OcrDebugContext.setContext(profile != null ? profile.getName() : null, getClass().getSimpleName());

        try {
            // lifecycle tasks skip validation entirely
            if (tpTask == TpDailyTaskEnum.INITIALIZE
                    || tpTask == TpDailyTaskEnum.SKIP_TUTORIAL
                    || tpTask == TpDailyTaskEnum.CREATE_CHARACTER) {
                execute();
                return;
            }

            verifyGameProcessActive();
            navigationHelper.ensureCorrectScreenLocation(getRequiredStartLocation());

            if (switchedProfileOnEmulator) {
                // Changed by pernerch | Date: 2026-07-02 | Why: refresh stamina immediately on
                // profile handover so downstream task logic always starts from current account data.
                logInfo("Profile switch detected on emulator " + EMULATOR_NUMBER + ". Refreshing stamina from profile screen.");
                staminaHelper.updateStaminaFromProfile();
            }

            if (shouldDeferForBearTrapMarchReservationFlow()) {
                return;
            }

            if (consumesStamina() && StaminaService.getServices().requiresUpdate(profile.getId())) {
                staminaHelper.updateStaminaFromProfile();
            }

            execute();

            if (shouldUpdateConfig) {
                ProfileService.obtain().persistAccount(profile);
                shouldUpdateConfig = false;
            }

            sleepTask(2000);
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.ANY);
        } finally {
            long elapsed = System.currentTimeMillis() - t0;
            int ocrDelta = this.currentOcrFailures - baselineOcr;
            int templateDelta = this.templateSearchHelper.getFailedSearches() - baselineTemplate;
            dev.frostguard.engine.service.StatisticsService.obtain()
                    .logJobExecution(profile, taskName, elapsed, ocrDelta, templateDelta);
            OcrDebugContext.clear();
        }
    }

    private void refreshProfileFromDb() {
        if (profile == null || profile.getId() == null) return;
        try {
            AccountDescriptor fresh = ProfileRepository.getRepository()
                    .getAccountWithSettingsById(profile.getId());
            if (fresh != null) this.profile = fresh;
        } catch (Exception ex) {
            logWarning("Profile refresh failed before execution: " + ex.getMessage());
        }
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: detect emulator-local profile handover
    // so runtime state (stamina + active profile context) is refreshed exactly once per switch.
    private boolean markAndDetectProfileSwitchFlow() {
        if (EMULATOR_NUMBER == null || EMULATOR_NUMBER.isBlank() || profile == null || profile.getId() == null) {
            return false;
        }

        Long previousProfileId = LAST_ACTIVE_PROFILE_BY_EMULATOR.put(EMULATOR_NUMBER, profile.getId());
        return previousProfileId != null && !previousProfileId.equals(profile.getId());
    }

    private boolean shouldDeferForBearTrapMarchReservationFlow() {
        if (profile == null || tpTask == null || tpTask == TpDailyTaskEnum.BEAR_TRAP) {
            return false;
        }

        if (!Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))) {
            return false;
        }

        LocalDateTime referenceTrapTime = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, LocalDateTime.class);
        Integer preparationMinutes = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, Integer.class);
        if (referenceTrapTime == null || preparationMinutes == null) {
            return false;
        }

        BearTrapHelper.WindowResult window = BearTrapHelper.calculateWindow(
            referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant(),
            preparationMinutes);
        if (window.getState() != TimeWindowHelper.WindowState.INSIDE) {
            return false;
        }

        boolean joinRally = Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL, Boolean.class));
        boolean callOwnRally = Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, Boolean.class));

        boolean reserveAllMarchesForBear = joinRally;
        if (reserveAllMarchesForBear) {
            return deferTaskForBearWindowFlow(BEAR_LOCKED_MARCH_TASKS.contains(tpTask),
                    "Bear Trap is active soon and configured to use marches. Reserving all marches for Bear only.");
        }

        boolean reserveRallyTasksOnly = callOwnRally || BEAR_LOCKED_RALLY_EVENT_TASKS.contains(tpTask);
        if (!reserveRallyTasksOnly) {
            return false;
        }

        return deferTaskForBearWindowFlow(BEAR_LOCKED_RALLY_EVENT_TASKS.contains(tpTask),
                "Bear Trap is active soon. Blocking rally-heavy event tasks so Bear keeps march priority.");
    }

    private boolean deferTaskForBearWindowFlow(boolean shouldDefer, String reason) {
        if (!shouldDefer) {
            return false;
        }

        LocalDateTime retryAt = resolveBearWindowReleaseTimeFlow();
        reschedule(retryAt);
        logInfo(reason + " Rescheduling " + taskName + " for " + retryAt.format(DATETIME_FORMATTER));
        return true;
    }

    private LocalDateTime resolveBearWindowReleaseTimeFlow() {
        LocalDateTime referenceTrapTime = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, LocalDateTime.class);
        Integer preparationMinutes = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, Integer.class);
        if (referenceTrapTime == null || preparationMinutes == null) {
            return LocalDateTime.now().plusMinutes(30);
        }

        BearTrapHelper.WindowResult window = BearTrapHelper.calculateWindow(
                referenceTrapTime.atZone(ZoneId.of("UTC")).toInstant(),
                preparationMinutes);
        Instant releaseInstant = window.getCurrentWindowEnd().plus(Duration.ofMinutes(1));
        return LocalDateTime.ofInstant(releaseInstant, ZoneId.systemDefault());
    }

    private void verifyGameProcessActive() {
        if (!emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName())) {
            throw new HomeNotFoundException("Game process is not active");
        }
    }

    // ── OCR convenience ─────────────────────────────────────────────

    protected Integer readNumberValue(PointData tl, PointData br, TesseractSettingsData cfg) {
        Integer val = integerHelper.attemptRecognition(tl, br, 5, 200L, cfg,
                text -> RegexNumberParser.conformsTo(text, CommonOCRSettings.NUMBER_PATTERN),
                text -> RegexNumberParser.extractByPattern(text, CommonOCRSettings.NUMBER_PATTERN));
        logDebug("Number OCR result: " + (val != null ? val : "null"));
        return val;
    }

    protected String readStringValue(PointData tl, PointData br, TesseractSettingsData cfg) {
        String val = stringHelper.attemptRecognition(tl, br, 5, 200L, cfg,
                Objects::nonNull, text -> text);
        logDebug("String OCR result: " + (val != null ? val : "null"));
        return val;
    }

    // ── emulator interaction ────────────────────────────────────────

    public void tapPoint(PointData point) {
        checkPreemption();
        emuManager.touchPoint(EMULATOR_NUMBER, point);
    }

    public void tapRandomPoint(PointData p1, PointData p2) {
        checkPreemption();
        emuManager.touchArea(EMULATOR_NUMBER, p1, p2);
    }

    public void tapRandomPoint(PointData p1, PointData p2, int count, int delay) {
        checkPreemption();
        emuManager.touchArea(EMULATOR_NUMBER, p1, p2, count, delay);
    }

    public void swipe(PointData start, PointData end) {
        checkPreemption();
        emuManager.swipeScreen(EMULATOR_NUMBER, start, end);
    }

    public void pressBack() {
        checkPreemption();
        emuManager.pressBack(EMULATOR_NUMBER);
    }

    // ── interruptible sleep with injection ───────────────────────────

    protected void sleepTask(long millis) {
        checkPreemption();
        long deadline = System.currentTimeMillis() + millis;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;

                if (!isInjecting && acceptsInjections()) {
                    InjectionRule pending = GlobalMonitorService.getInstance()
                            .pollPendingInjection(profile.getId());
                    if (pending != null) {
                        isInjecting = true;
                        try {
                            logDebug("Injecting: " + pending.getRuleName());
                            pending.executeInjection(emuManager, profile, this);
                            logDebug("Injection done: " + pending.getRuleName());
                        } catch (Exception ex) {
                            logError("Injection error: " + pending.getRuleName(), ex);
                        } finally {
                            isInjecting = false;
                        }
                    }
                }

                Thread.sleep(Math.min(left, 200));
                checkPreemption();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task interrupted during sleep", ie);
        }
    }

    // ── preemption ──────────────────────────────────────────────────

    public void attachToken(PreemptionToken token) { this.preemptionToken = token; }

    protected void checkPreemption() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StopExecutionException("Task interrupted by user request");
        }
        if (preemptionToken != null) preemptionToken.check();
    }

    // ── logging ─────────────────────────────────────────────────────
    // Changed by pernerch | Date: 2026-07-02 | Why: Ensure consistent profile name in logs for multi-profile 
    // emulator debugging. All log levels now include profile name for clarity when multiple profiles 
    // execute tasks on the same emulator (critical for detecting profile-switch race conditions).

    public void logInfo(String message) {
        // Changed: Include profile name in INFO logs for consistency across all log levels.
        // This is critical for multi-profile debugging where multiple TaskQueues run concurrently.
        logger.info(profile.getName() + " - " + message);
        loggingService.emit(TpMessageSeverityEnum.INFO, taskName, profile.getName(), message);
    }

    public void logWarning(String message) {
        logger.warn(profile.getName() + " - " + message);
        loggingService.emit(TpMessageSeverityEnum.WARNING, taskName, profile.getName(), message);
        incrementOcrFailureIfRelevant(message);
    }

    public void logError(String message) {
        logger.error(profile.getName() + " - " + message);
        loggingService.emit(TpMessageSeverityEnum.ERROR, taskName, profile.getName(), message);
        incrementOcrFailureIfRelevant(message);
    }

    public void logError(String message, Throwable t) {
        logger.error(profile.getName() + " - " + message, t);
        loggingService.emit(TpMessageSeverityEnum.ERROR, taskName, profile.getName(), message);
        incrementOcrFailureIfRelevant(message);
    }

    public void logDebug(String message) {
        logger.debug(profile.getName() + " - " + message);
        loggingService.emit(TpMessageSeverityEnum.DEBUG, taskName, profile.getName(), message);
    }

    private void incrementOcrFailureIfRelevant(String message) {
        if (message != null && message.toLowerCase().contains("ocr")) {
            this.currentOcrFailures++;
        }
    }

    // ── scheduling ──────────────────────────────────────────────────

    public void reschedule(LocalDateTime rescheduledTime) {
        long gapMs = Duration.between(LocalDateTime.now(), rescheduledTime).toMillis();
        scheduledTime = LocalDateTime.now().plus(Duration.ofMillis(gapMs));
    }

    public void clearSchedule() {
        scheduledTime = null;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        if (scheduledTime == null) {
            return Long.MAX_VALUE;
        }
        long diffSec = scheduledTime.toEpochSecond(ZoneOffset.UTC)
                - LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        return unit.convert(diffSec, TimeUnit.SECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) return 0;
        if (o instanceof DelayedTask other) {
            if (this.scheduledTime == null && other.scheduledTime == null) return 0;
            if (this.scheduledTime == null) return 1;
            if (other.scheduledTime == null) return -1;
            return this.scheduledTime.compareTo(other.scheduledTime);
        }
        return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
    }

    // ── getters & setters ───────────────────────────────────────────

    public boolean isRecurring() { return recurring; }
    public void setRecurring(boolean recurring) { this.recurring = recurring; }
    public LocalDateTime getLastExecutionTime() { return lastExecutionTime; }
    public void setLastExecutionTime(LocalDateTime t) { this.lastExecutionTime = t; }
    public Integer getTpDailyTaskId() { return tpTask.getId(); }
    public TpDailyTaskEnum getTpTask() { return tpTask; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public Integer getCustomPriority() { return customPriority; }
    public void setCustomPriority(Integer p) { this.customPriority = p; }
    public int getRepeatIntervalMinutes() { return repeatIntervalMinutes; }
    public void setRepeatIntervalMinutes(int m) { this.repeatIntervalMinutes = m; }
    public void setProfile(AccountDescriptor profile) { this.profile = profile; }
    public LocalDateTime getScheduled() { return scheduledTime; }
    public AccountDescriptor getProfile() { return profile; }
    public void setShouldUpdateConfig(boolean v) { this.shouldUpdateConfig = v; }
    public void setCustomTaskIdentifier(String id) { this.customTaskIdentifier = id; }

    // ── equality & hashing ──────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DelayedTask that)) return false;
        if (getClass() != that.getClass()) return false;
        if (tpTask != that.tpTask) return false;
        if (!Objects.equals(profile.getId(), that.profile.getId())) return false;

        Object k1 = this.getDistinctKey();
        Object k2 = that.getDistinctKey();
        if (k1 == null && k2 == null) return true;
        return Objects.equals(k1, k2);
    }

    @Override
    public int hashCode() {
        Object key = getDistinctKey();
        if (key == null) return Objects.hash(getClass(), tpTask, profile.getId());
        return Objects.hash(getClass(), tpTask, profile.getId(), key);
    }
}
