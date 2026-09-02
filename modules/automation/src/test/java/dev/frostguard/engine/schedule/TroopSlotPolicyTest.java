package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A surviving Intel claim used to double-count march slots it already occupied: the claim
 * represented slots already visible as busy in the live queue, so slotsToRecallForGather()
 * overcounted demand and recalled an extra Gather march for nothing.
 *
 * <p>{@link TroopSlotPolicy#claim} (genuine unmet demand -- nothing physically holds the slot yet)
 * and {@link TroopSlotPolicy#claimDeployed} (a march already out, already reflected as not-idle by
 * the live march-queue read Gather itself uses) now contribute differently to {@link
 * TroopSlotPolicy#slotsToRecallForGather}: only {@code claim()} counts toward demand, since {@code
 * claimDeployed()}'s slot is already excluded from {@code idleSlots} by the live read that produced
 * it -- counting it again would double-charge the same physical slot.</p>
 */
class TroopSlotPolicyTest {

    private AccountDescriptor profile() {
        // A fresh, unique id per test so the shared static CLAIMS map never leaks state between tests.
        return new AccountDescriptor((long) System.identityHashCode(new Object()), "Test", "1", true, 1L, 30L);
    }

    @Test
    void deployedClaimContributesNothingToRecallDemandEvenWithZeroIdleSlots() {
        AccountDescriptor profile = profile();
        // Intel's beast march is already out -- occupying 1 of the physical slots, already
        // reflected as not-idle. Gather is using every other slot (idle=0 here is realistic: total
        // capacity - Intel's 1 - Gather's remaining = 0 idle).
        TroopSlotPolicy.claimDeployed(profile, TpDailyTaskEnum.INTEL, 1, LocalDateTime.now().plusMinutes(10));

        int toRecall = TroopSlotPolicy.slotsToRecallForGather(profile, 0);

        assertEquals(0, toRecall,
                "an already-deployed claim's slot is already excluded from idleSlots -- it must not also inflate demand");
    }

    @Test
    void unmetDemandClaimStillRecallsWhenNoIdleSlotsExist() {
        AccountDescriptor profile = profile();
        // Genuine unmet demand: nothing has been sent yet, Beast Hunting found the queue full.
        TroopSlotPolicy.claim(profile, TpDailyTaskEnum.BEAST_HUNTING, 1, LocalDateTime.now().plusMinutes(10));

        int toRecall = TroopSlotPolicy.slotsToRecallForGather(profile, 0);

        assertEquals(1, toRecall, "a not-yet-deployed claim genuinely needs an idle slot freed for it");
    }

    @Test
    void mixedDeployedAndUnmetClaimsOnlyCountTheUnmetOneTowardDemand() {
        AccountDescriptor profile = profile();
        TroopSlotPolicy.claimDeployed(profile, TpDailyTaskEnum.INTEL, 1, LocalDateTime.now().plusMinutes(10));
        TroopSlotPolicy.claim(profile, TpDailyTaskEnum.EVENT_POLAR_TERROR, 1, LocalDateTime.now().plusMinutes(10));

        int toRecall = TroopSlotPolicy.slotsToRecallForGather(profile, 0);

        assertEquals(1, toRecall, "only the not-yet-deployed Polar claim should count -- Intel's is already accounted for");
    }

    @Test
    void deployedClaimStillCoversItsSlotWhenEnoughIdleExists() {
        AccountDescriptor profile = profile();
        TroopSlotPolicy.claimDeployed(profile, TpDailyTaskEnum.INTEL, 1, LocalDateTime.now().plusMinutes(10));

        // Plenty of idle capacity available regardless -- still zero recall, for the right reason
        // (deployed claims never contribute demand at all, not just "demand happened to be covered").
        int toRecall = TroopSlotPolicy.slotsToRecallForGather(profile, 5);

        assertEquals(0, toRecall);
    }

    @Test
    void reClaimingDeployedReplacesThePriorClaimForTheSameTask() {
        AccountDescriptor profile = profile();
        TroopSlotPolicy.claimDeployed(profile, TpDailyTaskEnum.INTEL, 1, LocalDateTime.now().plusMinutes(1));
        // Real ETA becomes known later in the pass and re-claims -- same task, replaces the earlier one.
        TroopSlotPolicy.claimDeployed(profile, TpDailyTaskEnum.INTEL, 1, LocalDateTime.now().plusMinutes(20));

        assertEquals(1, TroopSlotPolicy.activeClaims(profile).size(),
                "re-claiming the same task must replace, not accumulate, its claim");
    }
}
