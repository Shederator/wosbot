package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Decision policy for evaluating whether to join a Bear Trap rally candidate based on configuration thresholds
 * and frenzy mode status.
 */
public final class BearRallyDecisionPolicy {

    public enum DecisionResult {
        JOIN,
        SKIP_NOT_JOINABLE,
        SKIP_MIN_MEMBERS_NOT_MET,
        SKIP_MIN_RALLY_CAPACITY_NOT_MET,
        SKIP_MIN_REMAINING_CAPACITY_NOT_MET,
        SKIP_INVALID_CANDIDATE,
        SKIP_ADVANCED_JOIN_DISABLED
    }

    public record Decision(DecisionResult result, String reason, boolean frenzyActive) {}

    private BearRallyDecisionPolicy() {
        // Policy utility
    }

    public static Decision evaluate(BearRallyCandidate candidate, AccountDescriptor profile, LocalDateTime trapStartTime, Clock clock) {
        if (candidate == null || !candidate.isJoinable()) {
            return new Decision(DecisionResult.SKIP_NOT_JOINABLE, "Candidate card is greyed out or not joinable", false);
        }

        Boolean advancedEnabled = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_ADVANCED_JOIN_ENABLED_BOOL, Boolean.class);
        if (!Boolean.TRUE.equals(advancedEnabled)) {
            return new Decision(DecisionResult.JOIN, "Advanced join policy disabled; using standard join path", false);
        }

        if (!isCandidateValid(candidate)) {
            return new Decision(DecisionResult.SKIP_INVALID_CANDIDATE,
                    "Candidate fields are incomplete or internally inconsistent", false);
        }

        Boolean frenzyEnabled = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_MODE_ENABLED_BOOL, Boolean.class);
        Integer frenzyStartMinute = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_FRENZY_START_MINUTE_INT, Integer.class);
        if (frenzyStartMinute == null || frenzyStartMinute < 0 || frenzyStartMinute >= 30) {
            frenzyStartMinute = 22;
        }

        boolean frenzyActive = false;
        if (Boolean.TRUE.equals(frenzyEnabled) && trapStartTime != null) {
            LocalDateTime now = LocalDateTime.now(clock);
            long elapsedMinutes = Duration.between(trapStartTime, now).toMinutes();
            if (elapsedMinutes >= frenzyStartMinute) {
                frenzyActive = true;
            }
        }

        // Check 1: Minimum member count threshold
        Integer minMembers = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_MEMBER_COUNT_INT, Integer.class);
        if (!frenzyActive && minMembers != null && minMembers > 0) {
            if (candidate.currentMembers() < minMembers) {
                return new Decision(DecisionResult.SKIP_MIN_MEMBERS_NOT_MET,
                        "Candidate members (" + candidate.currentMembers() + "/" + candidate.maxMembers()
                                + ") below threshold " + minMembers, false);
            }
        }

        // Check 2: Minimum total rally capacity threshold (别人最大集结量门槛)
        Integer minRallyCapacity = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_RALLY_CAPACITY_INT, Integer.class);
        if (minRallyCapacity != null && minRallyCapacity > 0) {
            if (candidate.rallyCapacity() < minRallyCapacity) {
                return new Decision(DecisionResult.SKIP_MIN_RALLY_CAPACITY_NOT_MET,
                        "Candidate rally capacity (" + candidate.rallyCapacity() + ") below threshold " + minRallyCapacity, false);
            }
        }

        // Check 3: Minimum remaining capacity threshold (别人集结剩余可加入兵量门槛)
        Integer minRemainingCapacity = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_MIN_REMAINING_CAPACITY_INT, Integer.class);
        if (minRemainingCapacity != null && minRemainingCapacity > 0) {
            if (candidate.remainingCapacity() < minRemainingCapacity) {
                return new Decision(DecisionResult.SKIP_MIN_REMAINING_CAPACITY_NOT_MET,
                        "Candidate remaining capacity (" + candidate.remainingCapacity()
                                + ") below threshold " + minRemainingCapacity, false);
            }
        }

        String reason = frenzyActive
                ? "Frenzy mode active; member threshold relaxed while capacity thresholds remain enforced"
                : "Candidate met all threshold criteria";
        return new Decision(DecisionResult.JOIN, reason, frenzyActive);
    }

    private static boolean isCandidateValid(BearRallyCandidate candidate) {
        return candidate.joinButtonPoint() != null
                && candidate.cardArea() != null
                && candidate.hostName() != null
                && !candidate.hostName().isBlank()
                && candidate.currentMembers() >= 0
                && candidate.maxMembers() > 0
                && candidate.currentMembers() <= candidate.maxMembers()
                && candidate.currentTroops() >= 0
                && candidate.rallyCapacity() > 0
                && candidate.currentTroops() <= candidate.rallyCapacity()
                && candidate.remainingCapacity() >= 0
                && candidate.remainingCapacity() <= candidate.rallyCapacity()
                && candidate.countdown() != null
                && !candidate.countdown().isNegative()
                && !candidate.countdown().isZero()
                && candidate.observedAt() != null;
    }
}
