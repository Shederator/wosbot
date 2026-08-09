package dev.frostguard.engine.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TimeWindowHelper.WindowState;

/** Resolves the selected Bear Trap participation timer into a queue schedule. */
public final class BearTrapParticipationSchedule {

    private static final int DEFAULT_TRAP_NUMBER = 1;
    private static final int DEFAULT_PREPARATION_MINUTES = 10;
    private static final DateTimeFormatter CONFIG_DATE_TIME =
            DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm");

    private BearTrapParticipationSchedule() {
    }

    public static Optional<Plan> resolve(AccountDescriptor profile) {
        return resolve(profile, Clock.systemUTC(), ZoneId.systemDefault());
    }

    static Optional<Plan> resolve(AccountDescriptor profile, Clock clock, ZoneId queueZone) {
        if (profile == null) {
            return Optional.empty();
        }

        int trapNumber = selectedTrapNumber(profile);
        ConfigurationKeyEnum scheduleKey = scheduleKey(trapNumber);
        Optional<LocalDateTime> configuredActivation = configuredActivation(profile, scheduleKey);
        if (configuredActivation.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime activationUtc = configuredActivation.get();

        Integer configuredPreparation = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT,
                Integer.class);
        int preparationMinutes = configuredPreparation != null && configuredPreparation >= 0
                ? configuredPreparation
                : DEFAULT_PREPARATION_MINUTES;

        var window = BearTrapHelper.calculateWindow(
                activationUtc.toInstant(ZoneOffset.UTC),
                preparationMinutes,
                30,
                2,
                clock);
        Instant nextRunInstant = window.getState() == WindowState.INSIDE
                ? clock.instant()
                : window.getNextWindowStart();

        return Optional.of(new Plan(
                trapNumber,
                scheduleKey,
                activationUtc,
                preparationMinutes,
                LocalDateTime.ofInstant(nextRunInstant, queueZone)));
    }

    public static ConfigurationKeyEnum scheduleKey(int trapNumber) {
        return trapNumber == 2
                ? ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING
                : ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
    }

    private static int selectedTrapNumber(AccountDescriptor profile) {
        Integer configured = profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, Integer.class);
        return configured != null && configured == 2 ? 2 : DEFAULT_TRAP_NUMBER;
    }

    private static Optional<LocalDateTime> configuredActivation(
            AccountDescriptor profile,
            ConfigurationKeyEnum scheduleKey) {
        return profile.getConfigs().stream()
                .filter(config -> scheduleKey.equals(config.getSettingKey())
                        || scheduleKey.name().equalsIgnoreCase(String.valueOf(config.getSettingKey())))
                .map(config -> config.getRawValue())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .flatMap(BearTrapParticipationSchedule::parseActivation);
    }

    private static Optional<LocalDateTime> parseActivation(String value) {
        try {
            return Optional.of(LocalDateTime.parse(value, CONFIG_DATE_TIME));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public record Plan(
            int trapNumber,
            ConfigurationKeyEnum scheduleKey,
            LocalDateTime activationUtc,
            int preparationMinutes,
            LocalDateTime nextRun) {
    }
}
