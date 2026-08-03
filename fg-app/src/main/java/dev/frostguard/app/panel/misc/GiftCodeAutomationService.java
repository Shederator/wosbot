package dev.frostguard.app.panel.misc;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.app.panel.misc.GiftCodeClient.GiftCodeEntry;
import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemResult;
import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemOutcome;
import dev.frostguard.app.panel.misc.GiftCodeStore.LegacyClaim;
import dev.frostguard.app.panel.misc.GiftCodeStore.GiftCodeRecipient;
import dev.frostguard.engine.service.ProfileService;

public final class GiftCodeAutomationService {

    private static final Logger LOG = LoggerFactory.getLogger(GiftCodeAutomationService.class);
    private static final GiftCodeAutomationService INSTANCE = new GiftCodeAutomationService();
    private static final int MAX_ATTEMPTS_PER_CLAIM = 3;
    private static final Duration AUTO_CHECK_INTERVAL = Duration.ofHours(1);
    private static final Duration MIN_CLAIM_REQUEST_INTERVAL = Duration.ofSeconds(1);

    private final GiftCodeClient client = new GiftCodeClient();
    private final GiftCodeRedeemer redeemer = new GiftCodeRedeemer();
    private final GiftCodeStore store = new GiftCodeStore();
    private final GiftCodeHistoryStore history = new GiftCodeHistoryStore();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gift-code-automation");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Consumer<GiftCodeState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean manualClaimRunning = new AtomicBoolean();
    private final AtomicBoolean manualStopRequested = new AtomicBoolean();
    private final Set<Long> cancelledAutoProfiles = ConcurrentHashMap.newKeySet();
    private final Set<Long> activeAutoProfiles = ConcurrentHashMap.newKeySet();

    private volatile List<GiftCodeEntry> activeCodes = List.of();
    private volatile String status = "Gift codes have not been checked yet.";
    private volatile String manualClaimStatus = "";
    private volatile boolean started;

    private GiftCodeAutomationService() {}

    public static GiftCodeAutomationService getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        migrateLegacyData();
        ProfileService.obtain().registerDataObserver(ignored -> {
            if (isProfileRefreshAllowed()) {
                refreshProfileState();
            }
        });
        scheduleHourlyChecks();
        if (hasEnabledAutoProfiles()) {
            runAutomatedCheck();
        }
    }

    public GiftCodeState snapshot() {
        List<AccountDescriptor> accounts = profiles();
        List<GiftCodeProfile> giftProfiles = accounts.stream().map(this::toGiftCodeProfile).toList();
        List<GiftCodeRecipient> recipients = giftProfiles.stream()
                .flatMap(profile -> profile.recipients().stream())
                .toList();
        return new GiftCodeState(activeCodes, giftProfiles, recipients, busy.get(), manualClaimRunning.get(),
                manualStopRequested.get(), status, manualClaimStatus);
    }

    public void addListener(Consumer<GiftCodeState> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
            listener.accept(snapshot());
        }
    }

    public void removeListener(Consumer<GiftCodeState> listener) {
        listeners.remove(listener);
    }

    public void fetchNow() {
        submitExclusive(() -> fetchCodes("Fetched"));
    }

    public void claimAllUnchecked() {
        if (!busy.compareAndSet(false, true)) {
            status = "A gift code operation is already running.";
            publish();
            return;
        }
        manualStopRequested.set(false);
        manualClaimRunning.set(true);
        status = "Starting manual gift code claim...";
        manualClaimStatus = status;
        publish();
        executor.execute(() -> {
            try {
                if (fetchCodes("Fetched") && !manualStopRequested.get()) {
                    claimUncheckedCodes(profiles(), false);
                } else if (manualStopRequested.get()) {
                    status = "Manual gift code claim stopped.";
                    manualClaimStatus = status;
                } else {
                    manualClaimStatus = status;
                }
            } finally {
                manualClaimRunning.set(false);
                manualStopRequested.set(false);
                busy.set(false);
                publish();
            }
        });
    }

    public void stopManualClaim() {
        if (!manualClaimRunning.get()) {
            return;
        }
        manualStopRequested.set(true);
        status = "Stopping manual gift code claim...";
        manualClaimStatus = status;
        LOG.info("Manual gift code claim stop requested");
        publish();
    }

    public boolean setAutoEnabled(Long profileId, boolean enabled) {
        AccountDescriptor profile = findProfile(profileId);
        if (profile == null) {
            status = "The selected profile no longer exists.";
            publish();
            return false;
        }
        if (enabled && !hasValidProfileIdentity(profile)) {
            store.setAutoEnabled(profile, false);
            status = "Add the Player ID and Server/Region to " + profileName(profile)
                    + " before enabling auto claim.";
            publish();
            return false;
        }
        boolean wasRunning = activeAutoProfiles.contains(profileId);
        if (!store.setAutoEnabled(profile, enabled)) {
            status = "Could not save auto claim for " + profileName(profile) + ".";
            publish();
            return false;
        }
        if (enabled) {
            cancelledAutoProfiles.remove(profileId);
            store.setLastCheckUtc(profile, null);
        } else {
            cancelledAutoProfiles.add(profileId);
        }
        status = enabled
                ? "Auto claim enabled for " + profileName(profile) + ". Checks run hourly."
                : wasRunning
                        ? "Stopping automatic gift code claim for " + profileName(profile) + "..."
                        : "Auto claim disabled for " + profileName(profile) + ".";
        LOG.info("Gift code auto claim {} for profile {}",
                enabled ? "enabled" : wasRunning ? "stop requested" : "disabled", profileName(profile));
        publish();
        if (enabled) {
            runAutomatedCheck();
        }
        return true;
    }

    public boolean addExtraRecipient(Long ownerProfileId, String playerId, String alias, String region) {
        AccountDescriptor owner = findProfile(ownerProfileId);
        String normalized = playerId == null ? "" : playerId.trim();
        String normalizedRegion = region == null ? "" : region.trim();
        if (owner == null) {
            status = "Select an owning profile first.";
        } else if (!normalized.matches("\\d+")) {
            status = "Player ID must contain digits only.";
        } else if (!normalizedRegion.matches("\\d+")) {
            status = "Region must contain digits only.";
        } else if (store.saveExtraRecipient(owner, normalized, alias, normalizedRegion)) {
            status = "Added gift code recipient to " + profileName(owner) + ".";
            publish();
            return true;
        } else {
            status = "Could not save the additional recipient.";
        }
        publish();
        return false;
    }

    public void removeExtraRecipient(Long ownerProfileId, String playerId) {
        AccountDescriptor owner = findProfile(ownerProfileId);
        if (owner == null || !store.removeExtraRecipient(owner, playerId)) {
            status = "Could not remove the additional recipient.";
        } else {
            status = "Removed gift code recipient from " + profileName(owner) + ".";
        }
        publish();
    }

    private void runAutomatedCheck() {
        submitExclusive(() -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            List<AccountDescriptor> due = profiles().stream()
                    .filter(this::isAutoEnabledAndValid)
                    .toList();
            if (due.isEmpty()) {
                return;
            }
            due.forEach(profile -> activeAutoProfiles.add(profile.getId()));
            publish();
            try {
                if (fetchCodes("Hourly check found")) {
                    List<AccountDescriptor> activeDue = due.stream()
                            .filter(profile -> !cancelledAutoProfiles.contains(profile.getId()))
                            .toList();
                    if (activeDue.isEmpty()) {
                        status = "Automatic gift code claim stopped.";
                        publish();
                        return;
                    }
                    activeDue.forEach(profile -> store.setLastCheckUtc(profile, today));
                    claimUncheckedCodes(activeDue, true);
                }
            } finally {
                due.forEach(profile -> activeAutoProfiles.remove(profile.getId()));
                publish();
            }
        });
    }

    private boolean fetchCodes(String verb) {
        status = "Fetching active gift codes...";
        publish();
        try {
            activeCodes = List.copyOf(client.fetchActiveCodes());
            status = verb + " " + activeCodes.size() + " active gift code(s).";
            LOG.info("{} {} active gift code(s)", verb, activeCodes.size());
            publish();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = "Gift code fetch was interrupted.";
        } catch (Exception exception) {
            status = "Gift code fetch failed: " + safeMessage(exception);
            LOG.warn("Gift code fetch failed", exception);
        }
        publish();
        return false;
    }

    private void claimUncheckedCodes(List<AccountDescriptor> owners, boolean autoRun) {
        Map<PlayerCodeKey, MutableClaimWork> grouped = new LinkedHashMap<>();
        int validAssignments = 0;
        int configurationErrors = 0;
        for (GiftCodeEntry code : activeCodes) {
            for (AccountDescriptor owner : owners) {
                for (GiftCodeRecipient recipient : recipients(owner)) {
                    if (!hasValidRecipientIdentity(recipient)) {
                        configurationErrors++;
                        LOG.warn("Gift code claim result status=CONFIG_ERROR player={} region={} profiles={} "
                                        + "message=Player ID or region is missing",
                                recipient.playerId(), recipient.region(), profileName(owner));
                        continue;
                    }
                    validAssignments++;
                    PlayerCodeKey key = new PlayerCodeKey(recipient.playerId(), code.code());
                    grouped.computeIfAbsent(key, ignored -> new MutableClaimWork(recipient, code))
                            .add(owner, recipient.region());
                }
            }
        }

        List<ClaimWork> pending = new ArrayList<>();
        int skipped = 0;
        for (MutableClaimWork work : grouped.values()) {
            if (work.hasRegionConflict()) {
                configurationErrors++;
                LOG.warn("Gift code claim result status=CONFIG_ERROR code={} player={} regions={} profiles={} "
                                + "message=Conflicting regions for the same player",
                        work.code.code(), work.recipient.playerId(), work.regions, profileNames(work.owners.values()));
            } else if (history.wasTerminallyChecked(work.recipient.playerId(), work.code.code())) {
                skipped++;
            } else {
                pending.add(work.toClaimWork());
            }
        }
        int deduplicated = Math.max(0, validAssignments - grouped.size());
        if (pending.isEmpty()) {
            String result = skipped == 0
                    ? configurationErrors == 0 ? "No profile recipients are configured."
                            : "No claims started because recipient configuration is incomplete."
                    : "All active codes are already checked in this bot's shared claim history.";
            updateClaimStatus(result, autoRun);
            LOG.info(result);
            return;
        }

        int attempted = 0;
        int redeemed = 0;
        int alreadyRedeemed = 0;
        int failed = 0;
        int retryable = 0;
        int cancelled = 0;
        long lastClaimRequestStartedNanos = 0L;
        while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
            if (autoRun) {
                int before = pending.size();
                pending.removeIf(work -> work.owners().stream()
                        .allMatch(owner -> cancelledAutoProfiles.contains(owner.getId())));
                cancelled += before - pending.size();
                if (pending.isEmpty()) {
                    break;
                }
            } else if (manualStopRequested.get()) {
                cancelled += pending.size();
                pending.clear();
                break;
            }
            ClaimWork work = pending.remove(0);
            if (!waitForClaimRequestSlot(lastClaimRequestStartedNanos, autoRun, work.owners())) {
                cancelled += pending.size() + 1;
                pending.clear();
                break;
            }
            attempted++;
            updateClaimStatus("Claiming " + work.code().code() + " for " + work.recipient().alias()
                    + " [" + profileNames(work.owners()) + "] (attempt " + work.attempt() + ")...", autoRun);
            lastClaimRequestStartedNanos = System.nanoTime();
            RedeemResult result = redeemer.redeem(
                    work.recipient().playerId(), work.recipient().region(), work.code().code());
            LOG.info("Gift code claim result status={} code={} player={} region={} profiles={} message={}",
                    result.outcome(), work.code().code(), work.recipient().playerId(), work.recipient().region(),
                    profileNames(work.owners()), result.message());
            if (result.terminal()) {
                if (history.remember(work.recipient().playerId(), work.recipient().region(),
                        work.code().code(), result)) {
                    if (result.outcome() == RedeemOutcome.REDEEMED) {
                        redeemed++;
                    } else if (result.outcome() == RedeemOutcome.ALREADY_REDEEMED) {
                        alreadyRedeemed++;
                    } else {
                        failed++;
                    }
                } else {
                    retryable++;
                    LOG.warn("Could not persist canonical gift code result code={} player={}",
                            work.code().code(), work.recipient().playerId());
                }
            } else if (work.attempt() < MAX_ATTEMPTS_PER_CLAIM) {
                LOG.warn("Gift code claim retry status={} code={} player={} attempt={}/{} message={}",
                        result.outcome(), work.code().code(), work.recipient().playerId(),
                        work.attempt(), MAX_ATTEMPTS_PER_CLAIM, result.message());
                if (!pauseWhileClaimActive(retryDelayMillis(work.attempt()), autoRun, work.owners())) {
                    cancelled += pending.size() + 1;
                    pending.clear();
                    break;
                }
                pending.add(work.nextAttempt());
            } else {
                retryable++;
                LOG.warn("Gift code claim result status=RETRY_EXHAUSTED code={} player={} attempts={} message={}",
                        work.code().code(), work.recipient().playerId(), work.attempt(), result.message());
            }
        }
        String outcome = !autoRun && manualStopRequested.get() ? "Claim run stopped: " : "Claim run complete: ";
        String result = outcome + redeemed + " redeemed, " + alreadyRedeemed + " already redeemed, "
                + failed + " failed, " + retryable + " retryable, " + skipped + " history skips, "
                + deduplicated + " duplicate assignments merged, " + configurationErrors
                + " configuration errors, " + cancelled + " cancelled (" + attempted + " API calls).";
        updateClaimStatus(result, autoRun);
        LOG.info(result);
    }

    private GiftCodeProfile toGiftCodeProfile(AccountDescriptor profile) {
        return new GiftCodeProfile(profile.getId(), profileName(profile), normalizedPlayerId(profile),
                normalizedRegion(profile),
                store.isAutoEnabled(profile), activeAutoProfiles.contains(profile.getId()),
                store.lastCheckUtc(profile), recipients(profile));
    }

    private List<GiftCodeRecipient> recipients(AccountDescriptor profile) {
        Map<String, GiftCodeRecipient> unique = new LinkedHashMap<>();
        if (hasValidPlayerId(profile)) {
            String playerId = normalizedPlayerId(profile);
            unique.put(playerId, new GiftCodeRecipient(profile.getId(), profileName(profile), playerId,
                    profileName(profile), normalizedRegion(profile), true));
        }
        for (GiftCodeRecipient extra : store.extraRecipients(profile)) {
            unique.putIfAbsent(extra.playerId(), extra);
        }
        return new ArrayList<>(unique.values());
    }

    private List<AccountDescriptor> profiles() {
        try {
            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            return profiles == null ? List.of() : profiles;
        } catch (RuntimeException exception) {
            LOG.warn("Could not load profiles for gift codes", exception);
            return List.of();
        }
    }

    private AccountDescriptor findProfile(Long profileId) {
        return profiles().stream()
                .filter(profile -> profileId != null && profileId.equals(profile.getId()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasEnabledAutoProfiles() {
        return profiles().stream().anyMatch(this::isAutoEnabledAndValid);
    }

    private boolean isAutoEnabledAndValid(AccountDescriptor profile) {
        return store.isAutoEnabled(profile) && hasValidProfileIdentity(profile);
    }

    private boolean hasValidPlayerId(AccountDescriptor profile) {
        return normalizedPlayerId(profile).matches("\\d+");
    }

    private boolean hasValidProfileIdentity(AccountDescriptor profile) {
        return hasValidPlayerId(profile) && normalizedRegion(profile).matches("\\d+");
    }

    private boolean hasValidRecipientIdentity(GiftCodeRecipient recipient) {
        return recipient.playerId() != null && recipient.playerId().matches("\\d+")
                && recipient.region() != null && recipient.region().matches("\\d+");
    }

    private String normalizedPlayerId(AccountDescriptor profile) {
        return profile.getCharacterId() == null ? "" : profile.getCharacterId().trim();
    }

    private String normalizedRegion(AccountDescriptor profile) {
        return profile.getCharacterServer() == null ? "" : profile.getCharacterServer().trim();
    }

    private String profileName(AccountDescriptor profile) {
        return profile.getName() == null || profile.getName().isBlank()
                ? "Profile " + profile.getId()
                : profile.getName();
    }

    private String profileNames(Collection<AccountDescriptor> profiles) {
        return profiles.stream().map(this::profileName).distinct().sorted().toList().toString();
    }

    private void migrateLegacyData() {
        int imported = 0;
        int failed = 0;
        for (AccountDescriptor profile : profiles()) {
            if (!store.migrateLegacyRecipients(profile)) {
                failed++;
                LOG.warn("Could not migrate legacy gift code recipients for profile {}", profileName(profile));
            }
            for (LegacyClaim claim : store.legacyClaims(profile)) {
                if (history.importLegacy(claim.playerId(), claim.region(), claim.giftCode(), claim.result())) {
                    imported++;
                } else {
                    failed++;
                    LOG.warn("Could not import legacy gift code history code={} player={} profile={}",
                            claim.giftCode(), claim.playerId(), profileName(profile));
                }
            }
        }
        if (imported > 0 || failed > 0) {
            LOG.info("Gift code history migration processed {} legacy entries with {} failures; "
                    + "canonical history is bot-local and shared across profiles", imported, failed);
        }
    }

    private void submitExclusive(Runnable task) {
        if (!busy.compareAndSet(false, true)) {
            status = "A gift code operation is already running.";
            publish();
            return;
        }
        publish();
        executor.execute(() -> {
            try {
                task.run();
            } finally {
                busy.set(false);
                publish();
            }
        });
    }

    private void scheduleHourlyChecks() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long initialDelay = delayUntilNextHourlyCheck(now).toMillis();
        executor.scheduleAtFixedRate(this::runAutomatedCheck, initialDelay,
                AUTO_CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    static Duration delayUntilNextHourlyCheck(ZonedDateTime now) {
        ZonedDateTime nextHour = now.withMinute(0).withSecond(0).withNano(0).plusHours(1);
        return Duration.between(now, nextHour);
    }

    static long retryDelayMillis(int completedAttempt) {
        return switch (completedAttempt) {
            case 1 -> Duration.ofSeconds(5).toMillis();
            default -> Duration.ofSeconds(15).toMillis();
        };
    }

    private void refreshProfileState() {
        for (AccountDescriptor profile : profiles()) {
            if (store.isAutoEnabled(profile) && !hasValidProfileIdentity(profile)) {
                store.setAutoEnabled(profile, false);
                status = "Auto claim was disabled for " + profileName(profile)
                        + " because its Player ID or Server/Region is missing.";
            }
        }
        publish();
    }

    static boolean isProfileRefreshAllowed() {
        return !Thread.currentThread().isInterrupted();
    }

    private boolean pause(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean waitForClaimRequestSlot(long lastRequestStartedNanos, boolean autoRun,
                                            List<AccountDescriptor> owners) {
        if (lastRequestStartedNanos == 0L) {
            return true;
        }
        long elapsedNanos = System.nanoTime() - lastRequestStartedNanos;
        long remainingNanos = MIN_CLAIM_REQUEST_INTERVAL.toNanos() - elapsedNanos;
        if (remainingNanos <= 0L) {
            return true;
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return pauseWhileClaimActive(remainingMillis, autoRun, owners);
    }

    private boolean pauseWhileClaimActive(long millis, boolean autoRun, List<AccountDescriptor> owners) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, millis));
        while (true) {
            if ((!autoRun && manualStopRequested.get())
                    || (autoRun && owners.stream().allMatch(owner -> cancelledAutoProfiles.contains(owner.getId())))) {
                return false;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                return true;
            }
            long sliceMillis = Math.min(250L,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            if (!pause(sliceMillis)) {
                return false;
            }
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void updateClaimStatus(String message, boolean autoRun) {
        status = message;
        if (!autoRun) {
            manualClaimStatus = message;
        }
        publish();
    }

    private void publish() {
        GiftCodeState state = snapshot();
        listeners.forEach(listener -> listener.accept(state));
    }

    public record GiftCodeProfile(Long profileId,
                                  String profileName,
                                  String playerId,
                                  String region,
                                  boolean autoEnabled,
                                  boolean autoRunning,
                                  LocalDate lastCheckUtc,
                                  List<GiftCodeRecipient> recipients) {
        public GiftCodeProfile {
            recipients = List.copyOf(recipients);
        }

        @Override
        public String toString() {
            return profileName;
        }
    }

    public record GiftCodeState(List<GiftCodeEntry> activeCodes,
                                List<GiftCodeProfile> profiles,
                                List<GiftCodeRecipient> recipients,
                                boolean busy,
                                boolean manualClaimRunning,
                                boolean manualStopRequested,
                                String status,
                                String manualClaimStatus) {
        public GiftCodeState {
            activeCodes = List.copyOf(activeCodes);
            profiles = List.copyOf(profiles);
            recipients = List.copyOf(recipients);
        }
    }

    private record ClaimWork(List<AccountDescriptor> owners,
                             GiftCodeRecipient recipient,
                             GiftCodeEntry code,
                             int attempt) {
        private ClaimWork {
            owners = List.copyOf(owners);
        }

        ClaimWork nextAttempt() {
            return new ClaimWork(owners, recipient, code, attempt + 1);
        }
    }

    private record PlayerCodeKey(String playerId, String giftCode) {}

    private static final class MutableClaimWork {
        private final GiftCodeRecipient recipient;
        private final GiftCodeEntry code;
        private final Map<Long, AccountDescriptor> owners = new LinkedHashMap<>();
        private final Set<String> regions = new LinkedHashSet<>();

        private MutableClaimWork(GiftCodeRecipient recipient, GiftCodeEntry code) {
            this.recipient = recipient;
            this.code = code;
        }

        private void add(AccountDescriptor owner, String region) {
            owners.putIfAbsent(owner.getId(), owner);
            regions.add(region);
        }

        private boolean hasRegionConflict() {
            return regions.size() > 1;
        }

        private ClaimWork toClaimWork() {
            return new ClaimWork(new ArrayList<>(owners.values()), recipient, code, 1);
        }
    }
}
