package dev.frostguard.engine.schedule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ScheduleService;

/**
 * Per-profile, in-memory ledger of march-slot demand from troop-consuming tasks.
 *
 * <p>The problem this generalises: gathering wants every idle march slot busy, but a handful of
 * higher-priority troop tasks (Intel, Cryptid hosting, Polar Terror, Beast hunting, Bear Trap) also
 * need slots when they have real work. The old, ad-hoc handling recalled <em>all</em> gather marches
 * and deferred gathering on a fixed guess whenever any of those looked imminent — which livelocked
 * (see the Intel {@code INTEL_LAST_RUN_HAD_MISSIONS_BOOL} gate) and left gathering idle for minutes
 * after a task finished.</p>
 *
 * <p>Instead, a task <b>publishes a claim</b> (via {@link #claim}) only once it has confirmed
 * actionable work, stating how many slots it needs and until when. Gather recalls only the shortfall
 * ({@link #slotsToRecallForGather}) and reschedules to the soonest claim expiry
 * ({@link #soonestClaimExpiry}). When a task finishes it {@link #release}s its claim, which also pulls
 * Gather forward so freed slots refill immediately rather than waiting out a stale defer.</p>
 *
 * <p>Runtime-only: a crash simply means Gather re-evaluates from the live march screen on its next
 * run, which is always the source of truth anyway. Keyed by profile id; expired claims are swept on
 * every read.</p>
 */
public final class TroopSlotPolicy {

    private static final Logger logger = LoggerFactory.getLogger(TroopSlotPolicy.class);

    /**
     * If Gather is already going to run within this window, {@link #release} leaves its schedule
     * alone rather than churning the queue (and the march screen). Mirrors
     * {@code IntelligenceRoutine.GATHER_TRIGGER_GRACE_SECONDS}.
     */
    private static final long GATHER_TRIGGER_GRACE_SECONDS = 90;

    /** A single task's outstanding demand for march slots.
     *
     * <p>{@code alreadyDeployed} distinguishes two genuinely different situations that were
     * previously conflated, which double-counted a slot and recalled a Gather march for nothing:
     * a claim for a march that HASN'T been sent yet (genuine unmet demand -- nothing
     * physically holds this slot, so Gather must free one) versus a claim for a march that's
     * ALREADY out (its slot is already reflected as not-idle by the live march-queue read Gather
     * uses for {@code idleSlots} -- counting it again in demand double-charges the same physical
     * slot and recalls an extra Gather march for nothing). See {@link #claim} vs {@link
     * #claimDeployed}.</p> */
    public record Claim(TpDailyTaskEnum task, int slotsNeeded, LocalDateTime expiresAt, boolean alreadyDeployed) {}

    // profileId -> (task -> claim). Both levels are concurrent: tasks run on per-profile virtual
    // threads and Gather reads across them.
    private static final Map<Long, Map<TpDailyTaskEnum, Claim>> CLAIMS = new ConcurrentHashMap<>();

    private TroopSlotPolicy() {}

    /**
     * Publishes (or refreshes) a task's slot demand for a march NOT yet sent -- no slot is
     * currently occupied for this claim, so Gather must free up {@code slotsNeeded} idle slot(s) to
     * satisfy it. Idempotent per task: a later claim for the same task replaces the earlier one, so
     * a task can simply re-claim to extend or resize its demand.
     */
    public static void claim(AccountDescriptor profile, TpDailyTaskEnum task, int slotsNeeded,
                             LocalDateTime expiresAt) {
        claim(profile, task, slotsNeeded, expiresAt, false);
    }

    /**
     * Records a march that's ALREADY out holding a slot (e.g. right after deployment succeeds).
     * Still reserves the slot from Gather via {@link #release}/{@link #soonestClaimExpiry}, but
     * contributes nothing to {@link #slotsToRecallForGather}'s demand sum: the live march-queue
     * read Gather uses for {@code idleSlots} already sees this march as not-idle, so adding its
     * {@code slotsNeeded} on top would double-count the same physical slot Gather already correctly
     * excluded, and recall an extra gather march that was never actually needed.
     */
    public static void claimDeployed(AccountDescriptor profile, TpDailyTaskEnum task, int slotsNeeded,
                                      LocalDateTime expiresAt) {
        claim(profile, task, slotsNeeded, expiresAt, true);
    }

    private static void claim(AccountDescriptor profile, TpDailyTaskEnum task, int slotsNeeded,
                              LocalDateTime expiresAt, boolean alreadyDeployed) {
        if (profile == null || profile.getId() == null || task == null) {
            return;
        }
        if (slotsNeeded <= 0 || expiresAt == null) {
            return;
        }
        CLAIMS.computeIfAbsent(profile.getId(), k -> new ConcurrentHashMap<>())
                .put(task, new Claim(task, slotsNeeded, expiresAt, alreadyDeployed));
        logger.info("TroopSlotPolicy | {} claimed {} march slot(s) until {} (profile {}, alreadyDeployed={}).",
                task, slotsNeeded, expiresAt, profile.getId(), alreadyDeployed);
    }

    /**
     * Drops a task's claim and, if a claim was actually present, pulls Gather forward now
     * (grace-guarded) so the freed slots refill immediately. Releasing when nothing was claimed is a
     * cheap no-op — it never triggers spurious Gather churn.
     */
    public static void release(AccountDescriptor profile, TpDailyTaskEnum task) {
        if (profile == null || profile.getId() == null || task == null) {
            return;
        }
        Map<TpDailyTaskEnum, Claim> perTask = CLAIMS.get(profile.getId());
        Claim removed = (perTask == null) ? null : perTask.remove(task);
        if (removed == null) {
            return;
        }
        logger.info("TroopSlotPolicy | {} released its slot claim (profile {}); pulling Gather forward.",
                task, profile.getId());
        pullGatherForward(profile);
    }

    /** Non-expired claims for a profile. Sweeps expired entries as a side effect. */
    public static List<Claim> activeClaims(AccountDescriptor profile) {
        if (profile == null || profile.getId() == null) {
            return List.of();
        }
        Map<TpDailyTaskEnum, Claim> perTask = CLAIMS.get(profile.getId());
        if (perTask == null || perTask.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        perTask.values().removeIf(c -> c.expiresAt() == null || !c.expiresAt().isAfter(now));
        return new ArrayList<>(perTask.values());
    }

    /**
     * How many gather marches must be recalled to satisfy outstanding troop demand, given the number
     * of physically idle slots already available: {@code max(0, sum(slotsNeeded) - idleSlots)}.
     *
     * <p>Only claims for marches NOT yet deployed ({@code alreadyDeployed == false}) count toward
     * demand -- an already-deployed march's slot is already reflected as not-idle in {@code
     * idleSlots} itself, so including it here too would double-count the same physical slot.</p>
     */
    public static int slotsToRecallForGather(AccountDescriptor profile, int idleSlots) {
        int demand = activeClaims(profile).stream()
                .filter(c -> !c.alreadyDeployed())
                .mapToInt(Claim::slotsNeeded).sum();
        return Math.max(0, demand - Math.max(0, idleSlots));
    }

    /** The soonest expiry among active claims, or {@code null} when there are none. */
    public static LocalDateTime soonestClaimExpiry(AccountDescriptor profile) {
        return activeClaims(profile).stream()
                .map(Claim::expiresAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * Mirrors {@code IntelligenceRoutine.triggerGatherResourcesNowFlow} / GatherRoutine's
     * {@code triggerPendingIntelNowFlow}: fetch this profile's live queue and pull Gather Resources to
     * "now" so it re-evaluates immediately. Guarded so it only fires when Gather is enabled and isn't
     * already about to run.
     */
    private static void pullGatherForward(AccountDescriptor profile) {
        try {
            Boolean gatherEnabled = profile.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class);
            if (!Boolean.TRUE.equals(gatherEnabled)) {
                return;
            }
            TaskDispatcher coordinator = ScheduleService.obtain().getCoordinator();
            if (coordinator == null) {
                return;
            }
            TaskQueue queue = coordinator.getQueue(profile.getId());
            if (queue == null) {
                return;
            }
            if (queue.isTaskScheduledSoon(TpDailyTaskEnum.GATHER_RESOURCES, GATHER_TRIGGER_GRACE_SECONDS)) {
                return;
            }
            queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
        } catch (Exception ex) {
            logger.warn("TroopSlotPolicy | Could not pull Gather forward for profile {}: {}",
                    profile.getId(), ex.getMessage());
        }
    }
}
