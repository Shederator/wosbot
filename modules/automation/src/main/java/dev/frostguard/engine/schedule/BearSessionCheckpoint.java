package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Durable, non-secret recovery position for one protected Bear event session. */
public final class BearSessionCheckpoint {

    private static final String VERSION = "1";
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

    static Optional<Checkpoint> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] fields = raw.split(SEPARATOR, -1);
        if (fields.length != 10 || !VERSION.equals(fields[0])) {
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
                    Instant.parse(fields[9])));
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
                observation.updatedAt());
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
                (checkpoint.updatedAt() == null ? Instant.now() : checkpoint.updatedAt()).toString());
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

    public record Checkpoint(
            Instant eventEnd,
            String phase,
            String state,
            String action,
            long frameSequence,
            Instant frameCapturedAt,
            int recoveryAttempts,
            String reason,
            Instant updatedAt) {
    }
}
