package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Durable per-profile intent to perform Bear cleanup without re-entering the tactical UI. */
public final class BearRecoveryFinalization {

    private BearRecoveryFinalization() {
    }

    public static Optional<Instant> deadline(AccountDescriptor profile) {
        if (profile == null) {
            return Optional.empty();
        }
        String raw = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_RECOVERY_FINALIZER_STRING,
                String.class);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static boolean arm(AccountDescriptor profile, Instant deadline) {
        if (profile == null || deadline == null) {
            return false;
        }
        return ConfigService.obtain().writeAccountSetting(
                profile,
                ConfigurationKeyEnum.BEAR_TRAP_RECOVERY_FINALIZER_STRING,
                deadline.toString());
    }

    public static boolean clear(AccountDescriptor profile) {
        if (profile == null) {
            return false;
        }
        if (deadline(profile).isEmpty()) {
            return true;
        }
        return ConfigService.obtain().writeAccountSetting(
                profile,
                ConfigurationKeyEnum.BEAR_TRAP_RECOVERY_FINALIZER_STRING,
                "");
    }
}
