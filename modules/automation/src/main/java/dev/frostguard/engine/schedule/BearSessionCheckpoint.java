package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Durable, non-secret recovery position for one protected Bear event session. */
public final class BearSessionCheckpoint {

    private static final String VERSION = "3";
    private static final String SEPARATOR = "\\|";

    private BearSessionCheckpoint() {
    }

    public static Optional<Checkpoint> load(AccountDescriptor profile) {
        if (profile == null) {
            return Optional.empty();
        }
        String raw = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_SESSION_CHECKPOINT_STRING,
                String.class);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return parse(raw);
    }

    public static boolean hasMarker(AccountDescriptor profile) {
        if (profile == null) {
            return false;
        }
        String raw = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_SESSION_CHECKPOINT_STRING,
                String.class);
        return raw != null && !raw.isBlank();
    }

    public static boolean cleanupUnverified(AccountDescriptor profile) {
        return load(profile).map(c -> "CLEANUP_UNVERIFIED".equals(c.reason())).orElse(false);
    }

    static Optional<Checkpoint> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] fields = raw.split(SEPARATOR, -1);
        if ("1".equals(fields[0]) && fields.length == 10) {
            return parseVersionOne(fields);
        }
        boolean versionTwo = "2".equals(fields[0]) && fields.length == 16;
        if (!versionTwo && (fields.length != 17 || !VERSION.equals(fields[0]))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Checkpoint(
                    Instant.parse(fields[1]),
                    text(fields[2]),
                    text(fields[3]),
                    text(fields[4]),
                    Long.parseLong(fields[5]),
                    Instant.parse(fields[6]),
                    Integer.parseInt(fields[7]),
                    text(fields[8]),
                    Instant.parse(fields[9]),
                    Instant.parse(fields[10]),
                    Instant.parse(fields[11]),
                    text(fields[12]),
                    Long.parseLong(fields[13]),
                    text(fields[14]),
                    Integer.parseInt(fields[15]), versionTwo ? "NONE" : text(fields[16])));
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Checkpoint> parseVersionOne(String[] fields) {
        try {
            return Optional.of(new Checkpoint(
                    Instant.parse(fields[1]), text(fields[2]), text(fields[3]), text(fields[4]),
                    Long.parseLong(fields[5]), Instant.parse(fields[6]), Integer.parseInt(fields[7]),
                    text(fields[8]), Instant.parse(fields[9])));
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static boolean open(AccountDescriptor profile, Instant eventEnd) {
        Optional<Checkpoint> current = load(profile);
        if (current.isPresent() && current.orElseThrow().eventEnd().equals(eventEnd)) {
            return true;
        }
        return record(profile, new Checkpoint(
                eventEnd,
                "SCHEDULER",
                "LEASE_ACQUIRED",
                "NONE",
                0,
                Instant.EPOCH,
                0,
                "session-opened",
                Instant.now()));
    }

    public static boolean record(AccountDescriptor profile, Checkpoint checkpoint) {
        if (profile == null || checkpoint == null || checkpoint.eventEnd() == null) {
            return false;
        }
        String serialized = serialize(checkpoint);
        return ConfigService.obtain().writeAccountSetting(
                profile,
                ConfigurationKeyEnum.BEAR_TRAP_SESSION_CHECKPOINT_STRING,
                serialized);
    }

    /** Records UI provenance without erasing the scheduler-owned recovery budget. */
    public static boolean recordObservation(AccountDescriptor profile, Checkpoint observation) {
        if (profile == null || observation == null || observation.eventEnd() == null) {
            return false;
        }
        Checkpoint merged = mergeRecoveryBudget(load(profile).orElse(null), observation);
        return record(profile, merged);
    }

    /**
     * Records a scheduler transition (error routing, queue stop) without erasing the tactical
     * own-rally and join state that recovery needs to avoid repeating an ambiguous input.
     */
    public static boolean recordScheduler(
            AccountDescriptor profile, Checkpoint transition, boolean keepDurableBudget) {
        if (profile == null || transition == null || transition.eventEnd() == null) {
            return false;
        }
        return record(profile, mergeTactical(load(profile).orElse(null), transition, keepDurableBudget));
    }

    static Checkpoint mergeTactical(Checkpoint durable, Checkpoint transition, boolean keepDurableBudget) {
        if (durable == null || !durable.eventEnd().equals(transition.eventEnd())) {
            return transition;
        }
        return new Checkpoint(
                transition.eventEnd(),
                transition.phase(),
                transition.state(),
                transition.action(),
                transition.frameSequence(),
                transition.frameCapturedAt(),
                keepDurableBudget ? durable.recoveryAttempts() : transition.recoveryAttempts(),
                "CLEANUP_UNVERIFIED".equals(durable.reason())
                        ? durable.reason() : transition.reason(),
                transition.updatedAt(),
                durable.ownRallySentAt(),
                durable.ownRallyReturnDeadline(),
                durable.joinSubstate(),
                durable.listRowFingerprint(),
                durable.listLeader(),
                durable.listRowY(), durable.petUseState());
    }

    static Checkpoint mergeRecoveryBudget(Checkpoint durable, Checkpoint observation) {
        if (durable == null || !durable.eventEnd().equals(observation.eventEnd())) {
            return observation;
        }
        return new Checkpoint(
                observation.eventEnd(),
                observation.phase(),
                observation.state(),
                observation.action(),
                observation.frameSequence(),
                observation.frameCapturedAt(),
                durable.recoveryAttempts(),
                durable.reason(),
                observation.updatedAt(),
                durable.ownRallySentAt(),
                durable.ownRallyReturnDeadline(),
                durable.joinSubstate(),
                durable.listRowFingerprint(),
                durable.listLeader(),
                durable.listRowY(), durable.petUseState());
    }

    static String serialize(Checkpoint checkpoint) {
        return String.join("|",
                VERSION,
                checkpoint.eventEnd().toString(),
                safe(checkpoint.phase()),
                safe(checkpoint.state()),
                safe(checkpoint.action()),
                Long.toString(Math.max(0, checkpoint.frameSequence())),
                (checkpoint.frameCapturedAt() == null ? Instant.EPOCH : checkpoint.frameCapturedAt()).toString(),
                Integer.toString(Math.max(0, checkpoint.recoveryAttempts())),
                safe(checkpoint.reason()),
                (checkpoint.updatedAt() == null ? Instant.now() : checkpoint.updatedAt()).toString(),
                instant(checkpoint.ownRallySentAt()).toString(),
                instant(checkpoint.ownRallyReturnDeadline()).toString(),
                safe(checkpoint.joinSubstate()),
                Long.toString(checkpoint.listRowFingerprint()),
                safe(checkpoint.listLeader()),
                Integer.toString(Math.max(0, checkpoint.listRowY())), safe(checkpoint.petUseState()));
    }

    public static boolean recordTactical(
            AccountDescriptor profile,
            Instant eventEnd,
            Instant ownRallySentAt,
            Instant ownRallyReturnDeadline,
            String joinSubstate,
            long listRowFingerprint,
            String listLeader,
            int listRowY,
            String reason) {
        Checkpoint current = load(profile).filter(value -> value.eventEnd().equals(eventEnd))
                .orElse(new Checkpoint(eventEnd, "ACTIVE", "UNOBSERVED", "NONE", 0,
                        Instant.EPOCH, 0, reason, Instant.now()));
        return record(profile, new Checkpoint(
                eventEnd, current.phase(), current.state(), current.action(),
                current.frameSequence(), current.frameCapturedAt(), current.recoveryAttempts(),
                reason, Instant.now(), instant(ownRallySentAt), instant(ownRallyReturnDeadline),
                joinSubstate, listRowFingerprint, listLeader, listRowY, current.petUseState()));
    }

    /** Persist before Use: a lost transport response must never lead to another confirmation. */
    public static boolean armPetUse(AccountDescriptor profile, Instant eventEnd, long frame, Instant capturedAt) {
        Checkpoint current = load(profile).filter(value -> value.eventEnd().equals(eventEnd)).orElse(null);
        if (current == null) return false;
        return record(profile, new Checkpoint(eventEnd, current.phase(), "PET_CONFIRMATION", "CONFIRM_PET_USE",
                frame, capturedAt, current.recoveryAttempts(), "pet-use-armed-before-input", Instant.now(),
                current.ownRallySentAt(), current.ownRallyReturnDeadline(), current.joinSubstate(),
                current.listRowFingerprint(), current.listLeader(), current.listRowY(), "USE_ARMED"));
    }

    public static boolean clear(AccountDescriptor profile) {
        if (profile == null) {
            return false;
        }
        if (load(profile).isEmpty()) {
            return true;
        }
        return ConfigService.obtain().writeAccountSetting(
                profile,
                ConfigurationKeyEnum.BEAR_TRAP_SESSION_CHECKPOINT_STRING,
                "");
    }

    private static String safe(String value) {
        return text(value).replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "NONE" : value;
    }

    private static Instant instant(Instant value) {
        return value == null ? Instant.EPOCH : value;
    }

    public record Checkpoint(
            Instant eventEnd,
            String phase,
            String state,
            String action,
            long frameSequence,
            Instant frameCapturedAt,
            int recoveryAttempts,
            String reason,
            Instant updatedAt,
            Instant ownRallySentAt,
            Instant ownRallyReturnDeadline,
            String joinSubstate,
            long listRowFingerprint,
            String listLeader,
            int listRowY,
            String petUseState) {

        public Checkpoint(Instant eventEnd, String phase, String state, String action, long frameSequence,
                Instant frameCapturedAt, int recoveryAttempts, String reason, Instant updatedAt,
                Instant ownRallySentAt, Instant ownRallyReturnDeadline, String joinSubstate,
                long listRowFingerprint, String listLeader, int listRowY) {
            this(eventEnd, phase, state, action, frameSequence, frameCapturedAt, recoveryAttempts, reason,
                    updatedAt, ownRallySentAt, ownRallyReturnDeadline, joinSubstate, listRowFingerprint,
                    listLeader, listRowY, "NONE");
        }

        public Checkpoint(
                Instant eventEnd,
                String phase,
                String state,
                String action,
                long frameSequence,
                Instant frameCapturedAt,
                int recoveryAttempts,
                String reason,
                Instant updatedAt) {
            this(eventEnd, phase, state, action, frameSequence, frameCapturedAt,
                    recoveryAttempts, reason, updatedAt, Instant.EPOCH, Instant.EPOCH,
                    "NONE", 0L, "NONE", 0);
        }
    }
}
