package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Durable per-event switch to observe-only. Set when recovery is exhausted or an operator action is
 * required while the event is active: the profile stays owned and recorded, but sends no input
 * for the rest of that event. It is not a user setting and ends with the event.
 */
public final class BearObserveOnlyFallback {

    private BearObserveOnlyFallback() {
    }

    public static Optional<Instant> until(AccountDescriptor profile) {
        if (profile == null) {
            return Optional.empty();
        }
        String raw = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_FALLBACK_STRING, String.class);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static boolean active(AccountDescriptor profile, Instant now) {
        return until(profile).filter(now::isBefore).isPresent();
    }

    public static boolean arm(AccountDescriptor profile, Instant eventEnd) {
        return profile != null && eventEnd != null && ConfigService.obtain().writeAccountSetting(
                profile, ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_FALLBACK_STRING, eventEnd.toString());
    }

    public static boolean clear(AccountDescriptor profile) {
        if (profile == null) {
            return false;
        }
        return until(profile).isEmpty() || ConfigService.obtain().writeAccountSetting(
                profile, ConfigurationKeyEnum.BEAR_TRAP_OBSERVE_FALLBACK_STRING, "");
    }
}
