package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class ConstructionBlockerRegistry {

    private static final String VERSION = "v1";

    enum Consumer {
        INFANTRY,
        LANCER,
        MARKSMAN,
        RESEARCH
    }

    record Reservation(Set<Consumer> consumers, int constructionQueue, LocalDateTime retryAt) {

        Reservation {
            consumers = consumers.isEmpty() ? Set.of() : Set.copyOf(consumers);
        }

        boolean blocks(Consumer consumer) {
            return consumers.contains(consumer);
        }
    }

    private ConstructionBlockerRegistry() {
    }

    static Optional<Reservation> reservation(AccountDescriptor profile) {
        if (profile == null) {
            return Optional.empty();
        }
        if (!isEnabled(profile)) {
            if (!profile.getConfig(ConfigurationKeyEnum.CITY_UPGRADE_CONSTRUCTION_LOCK_STRING, String.class).isBlank()) {
                clear(profile);
            }
            return Optional.empty();
        }
        return decode(profile.getConfig(ConfigurationKeyEnum.CITY_UPGRADE_CONSTRUCTION_LOCK_STRING, String.class));
    }

    static Optional<Reservation> reservationFor(AccountDescriptor profile, Consumer consumer) {
        return reservation(profile).filter(value -> value.blocks(consumer));
    }

    static void reserve(AccountDescriptor profile, Set<Consumer> consumers, int constructionQueue,
            LocalDateTime retryAt) {
        if (profile == null || consumers == null || consumers.isEmpty() || constructionQueue < 1 || retryAt == null) {
            return;
        }
        if (!isEnabled(profile)) {
            clear(profile);
            return;
        }
        write(profile, encode(new Reservation(consumers, constructionQueue, retryAt)));
    }

    static void clear(AccountDescriptor profile) {
        if (profile != null) {
            write(profile, "");
        }
    }

    static String encode(Reservation reservation) {
        String consumers = reservation.consumers().stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
        long retryEpochMillis = reservation.retryAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return String.join(";", VERSION, Integer.toString(reservation.constructionQueue()),
                Long.toString(retryEpochMillis), consumers);
    }

    static Optional<Reservation> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] fields = raw.split(";", -1);
            if (fields.length != 4 || !VERSION.equals(fields[0])) {
                return Optional.empty();
            }
            int queue = Integer.parseInt(fields[1]);
            long retryEpochMillis = Long.parseLong(fields[2]);
            EnumSet<Consumer> consumers = Arrays.stream(fields[3].split(","))
                    .filter(value -> !value.isBlank())
                    .map(Consumer::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Consumer.class)));
            if (queue < 1 || consumers.isEmpty()) {
                return Optional.empty();
            }
            LocalDateTime retryAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(retryEpochMillis), ZoneId.systemDefault());
            return Optional.of(new Reservation(consumers, queue, retryAt));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void write(AccountDescriptor profile, String value) {
        ConfigService.obtain().writeAccountSetting(
                profile, ConfigurationKeyEnum.CITY_UPGRADE_CONSTRUCTION_LOCK_STRING, value);
    }

    private static boolean isEnabled(AccountDescriptor profile) {
        return Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.CITY_UPGRADE_FURNACE_BOOL, Boolean.class))
                && Boolean.TRUE.equals(profile.getConfig(
                        ConfigurationKeyEnum.CITY_UPGRADE_RESERVE_PRODUCTION_BOOL, Boolean.class));
    }
}
