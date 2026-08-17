package dev.frostguard.engine.ranking.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureResult;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent, workspace-local analytics history with an internal versioned schema. */
public final class GameAnalyticsHistoryService {

    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private static final TypeReference<Map<Long, String>> LEGACY_NAME_MAP = new TypeReference<>() { };

    private final ObjectMapper mapper;
    private final Clock clock;

    public GameAnalyticsHistoryService() {
        this(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT), Clock.systemUTC());
    }

    GameAnalyticsHistoryService(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public synchronized GameAnalyticsSnapshot save(Path workspaceRoot, AccountDescriptor profile,
                                                    EmulatorType emulatorType,
                                                    GameAnalyticsCollectionType type,
                                                    AllianceRankingCaptureResult result) throws IOException {
        validate(profile, emulatorType, type, result);
        Instant capturedAt = Instant.now(clock);
        String id = FILE_TIME.format(capturedAt) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Map<Long, PlayerNameObservation> names = loadNames(workspaceRoot);
        Map<Long, PlayerPowerObservation> powers = loadPowers(workspaceRoot);
        observeDirectNames(result, capturedAt.toString(), names);
        observeDirectPowers(result, capturedAt.toString(), powers);
        AllianceRankingCaptureResult enriched = enrich(result, names, powers, capturedAt);
        GameAnalyticsSnapshot snapshot = new GameAnalyticsSnapshot(
                SCHEMA_VERSION, id, capturedAt.toString(), profile.getAccountId(),
                profile.getDisplayName(), profile.getDeviceSlot(), emulatorType.name(), type,
                enriched.power(), enriched.labyrinth());
        writeSnapshot(workspaceRoot, snapshot);
        writeNames(workspaceRoot, names);
        return snapshot;
    }

    public synchronized List<GameAnalyticsSnapshot> list(Path workspaceRoot, Long profileId) throws IOException {
        migrateLegacyExports(workspaceRoot, profileId);
        Path profileRoot = profileRoot(workspaceRoot, profileId);
        if (!Files.isDirectory(profileRoot)) return List.of();
        Map<Long, PlayerNameObservation> names = loadNames(workspaceRoot);
        Map<Long, PlayerPowerObservation> powers = loadPowers(workspaceRoot);
        List<GameAnalyticsSnapshot> snapshots = new ArrayList<>();
        try (var files = Files.walk(profileRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("player-names.json")).toList()) {
                GameAnalyticsSnapshot snapshot = mapper.readValue(file.toFile(), GameAnalyticsSnapshot.class);
                AllianceRankingCaptureResult enriched = enrich(
                        snapshot.result(), names, powers, Instant.parse(snapshot.capturedAt()));
                snapshots.add(new GameAnalyticsSnapshot(snapshot.schemaVersion(), snapshot.id(),
                        snapshot.capturedAt(), snapshot.profileId(), snapshot.profileName(),
                        snapshot.emulatorSlot(), snapshot.emulatorType(), snapshot.type(),
                        enriched.power(), enriched.labyrinth()));
            }
        }
        snapshots.sort(Comparator.comparing(GameAnalyticsSnapshot::capturedAt).reversed());
        return List.copyOf(snapshots);
    }

    private AllianceRankingCaptureResult enrich(AllianceRankingCaptureResult result,
                                                 Map<Long, PlayerNameObservation> names,
                                                 Map<Long, PlayerPowerObservation> powers,
                                                 Instant capturedAt) {
        List<AllianceRankingEntryData> power = result.power().stream()
                .map(entry -> enrichPowerEntry(entry, names.get(entry.playerId()),
                        powers.get(entry.playerId()), capturedAt))
                .toList();
        List<LabyrinthRankingEntryData> labyrinth = result.labyrinth().stream()
                .map(entry -> enrichLabyrinthName(entry, names.get(entry.playerId()), capturedAt))
                .toList();
        return new AllianceRankingCaptureResult(power, labyrinth);
    }

    private AllianceRankingEntryData enrichPowerEntry(AllianceRankingEntryData entry,
                                                       PlayerNameObservation cachedName,
                                                       PlayerPowerObservation cachedPower,
                                                       Instant capturedAt) {
        String name = entry.playerName();
        boolean nameFromCache = entry.playerNameFromCache();
        if (cachedName != null && (name == null || nameFromCache
                || observedAfter(cachedName, capturedAt))) {
            name = cachedName.name();
            nameFromCache = true;
        }
        Long power = entry.value();
        boolean powerFromCache = entry.powerFromCache();
        if (cachedPower != null && (power == null || powerFromCache)) {
            power = cachedPower.value();
            powerFromCache = true;
        }
        return new AllianceRankingEntryData(entry.rank(), entry.playerId(), name, power,
                nameFromCache, powerFromCache);
    }

    private LabyrinthRankingEntryData enrichLabyrinthName(LabyrinthRankingEntryData entry,
                                                          PlayerNameObservation cached,
                                                          Instant capturedAt) {
        if (cached != null && (entry.playerName() == null || entry.playerNameFromCache()
                || observedAfter(cached, capturedAt))) {
            return new LabyrinthRankingEntryData(entry.rank(), entry.playerId(),
                    cached.name(), entry.score(), true);
        }
        return entry;
    }

    private boolean observedAfter(PlayerNameObservation observation, Instant capturedAt) {
        try {
            return Instant.parse(observation.lastObservedAt()).isAfter(capturedAt);
        } catch (RuntimeException invalidTimestamp) {
            return true;
        }
    }

    private boolean observeDirectNames(AllianceRankingCaptureResult result, String observedAt,
                                       Map<Long, PlayerNameObservation> names) {
        boolean powerChanged = result.power().stream()
                .filter(entry -> !entry.playerNameFromCache())
                .map(entry -> observeName(names, entry.playerId(), entry.playerName(), observedAt))
                .reduce(false, Boolean::logicalOr);
        boolean labyrinthChanged = result.labyrinth().stream()
                .filter(entry -> !entry.playerNameFromCache())
                .map(entry -> observeName(names, entry.playerId(), entry.playerName(), observedAt))
                .reduce(false, Boolean::logicalOr);
        return powerChanged || labyrinthChanged;
    }

    private boolean observeName(Map<Long, PlayerNameObservation> names, long playerId,
                                String playerName, String observedAt) {
        if (playerName == null || playerName.isBlank()) return false;
        PlayerNameObservation current = names.get(playerId);
        if (current != null) {
            if (!atLeastAsRecent(observedAt, current.lastObservedAt())) return false;
            if (observedAt.equals(current.lastObservedAt()) && playerName.equals(current.name())) return false;
        }
        names.put(playerId, new PlayerNameObservation(playerName, observedAt));
        return true;
    }

    private boolean atLeastAsRecent(String candidate, String current) {
        try {
            return !Instant.parse(candidate).isBefore(Instant.parse(current));
        } catch (RuntimeException invalidTimestamp) {
            return true;
        }
    }

    private void observeDirectPowers(AllianceRankingCaptureResult result, String observedAt,
                                     Map<Long, PlayerPowerObservation> powers) {
        result.power().stream()
                .filter(entry -> entry.value() != null && !entry.powerFromCache())
                .forEach(entry -> observePower(
                        powers, entry.playerId(), entry.value(), observedAt));
    }

    private void observePower(Map<Long, PlayerPowerObservation> powers, long playerId,
                              long value, String observedAt) {
        PlayerPowerObservation current = powers.get(playerId);
        if (current != null && !atLeastAsRecent(observedAt, current.lastObservedAt())) return;
        powers.put(playerId, new PlayerPowerObservation(value, observedAt));
    }

    private Map<Long, PlayerPowerObservation> loadPowers(Path workspaceRoot) throws IOException {
        Map<Long, PlayerPowerObservation> powers = new LinkedHashMap<>();
        Path profiles = analyticsRoot(workspaceRoot).resolve("profiles");
        if (!Files.isDirectory(profiles)) return powers;
        try (var files = Files.walk(profiles)) {
            for (Path snapshotFile : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("player-names.json")).toList()) {
                GameAnalyticsSnapshot snapshot = mapper.readValue(snapshotFile.toFile(),
                        GameAnalyticsSnapshot.class);
                snapshot.power().stream()
                        .filter(entry -> entry.value() != null && !entry.powerFromCache())
                        .forEach(entry -> observePower(
                                powers, entry.playerId(), entry.value(), snapshot.capturedAt()));
            }
        }
        return powers;
    }

    private void validate(AccountDescriptor profile, EmulatorType emulatorType,
                          GameAnalyticsCollectionType type, AllianceRankingCaptureResult result) throws IOException {
        if (profile == null || profile.getAccountId() == null || emulatorType == null
                || type == null || result == null) {
            throw new IllegalArgumentException("history arguments must not be null");
        }
        if (type == GameAnalyticsCollectionType.POWER && result.power().isEmpty()) {
            throw new IOException("Alliance Power Ranking response was empty");
        }
        if (type == GameAnalyticsCollectionType.LABYRINTH && result.labyrinth().isEmpty()) {
            throw new IOException("Alliance Labyrinth Ranking response was empty");
        }
    }

    private void writeSnapshot(Path workspaceRoot, GameAnalyticsSnapshot snapshot) throws IOException {
        Path directory = profileRoot(workspaceRoot, snapshot.profileId())
                .resolve(snapshot.type().name().toLowerCase());
        Files.createDirectories(directory);
        writeAtomically(directory.resolve(snapshot.id() + ".json"), mapper.writeValueAsBytes(snapshot));
    }

    private Map<Long, PlayerNameObservation> loadNames(Path workspaceRoot) throws IOException {
        Path file = analyticsRoot(workspaceRoot).resolve("player-names.json");
        Map<Long, PlayerNameObservation> names = new LinkedHashMap<>();
        boolean needsWrite = !Files.isRegularFile(file);
        if (Files.isRegularFile(file)) {
            PlayerNameCacheDocument document = mapper.readValue(file.toFile(), PlayerNameCacheDocument.class);
            if (document.players() != null) {
                names.putAll(document.players());
            }
        }
        Path profiles = analyticsRoot(workspaceRoot).resolve("profiles");
        if (Files.isDirectory(profiles)) {
            try (var files = Files.walk(profiles)) {
                for (Path legacy : files.filter(path -> path.getFileName().toString()
                        .equals("player-names.json")).toList()) {
                    Map<Long, String> oldNames = mapper.readValue(legacy.toFile(), LEGACY_NAME_MAP);
                    String observedAt = Files.getLastModifiedTime(legacy).toInstant().toString();
                    oldNames.forEach((id, name) -> {
                        if (!names.containsKey(id)) {
                            names.put(id, new PlayerNameObservation(name, observedAt));
                        }
                    });
                    needsWrite = true;
                }
            }
            try (var files = Files.walk(profiles)) {
                for (Path snapshotFile : files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().equals("player-names.json")).toList()) {
                    GameAnalyticsSnapshot snapshot = mapper.readValue(snapshotFile.toFile(),
                            GameAnalyticsSnapshot.class);
                    needsWrite |= observeDirectNames(snapshot.result(), snapshot.capturedAt(), names);
                }
            }
        }
        if (needsWrite && !names.isEmpty()) {
            writeNames(workspaceRoot, names);
        }
        return names;
    }

    private void writeNames(Path workspaceRoot, Map<Long, PlayerNameObservation> names) throws IOException {
        Path file = analyticsRoot(workspaceRoot).resolve("player-names.json");
        Files.createDirectories(file.getParent());
        writeAtomically(file, mapper.writeValueAsBytes(new PlayerNameCacheDocument(SCHEMA_VERSION, names)));
    }

    private Path profileRoot(Path workspaceRoot, Long profileId) {
        return analyticsRoot(workspaceRoot).resolve("profiles").resolve(profileId.toString());
    }

    private Path analyticsRoot(Path workspaceRoot) {
        return workspaceRoot.toAbsolutePath().normalize().resolve("data").resolve("game-analytics");
    }

    private void migrateLegacyExports(Path workspaceRoot, Long requestedProfileId) throws IOException {
        Path legacyRoot = workspaceRoot.toAbsolutePath().normalize().resolve("game-analytics");
        if (!Files.isDirectory(legacyRoot)) return;
        try (var files = Files.walk(legacyRoot)) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals("analytics.json")).toList()) {
                JsonNode root = mapper.readTree(file.toFile());
                Long profileId = root.path("profile").path("id").isNumber()
                        ? root.path("profile").path("id").asLong() : null;
                if (profileId == null || !profileId.equals(requestedProfileId)) continue;
                List<AllianceRankingEntryData> power = mapper.convertValue(root.path("power"),
                        new TypeReference<>() { });
                List<LabyrinthRankingEntryData> labyrinth = mapper.convertValue(root.path("labyrinth"),
                        new TypeReference<>() { });
                GameAnalyticsCollectionType type = !power.isEmpty()
                        ? GameAnalyticsCollectionType.POWER : GameAnalyticsCollectionType.LABYRINTH;
                String capturedAt = root.path("capturedAt").asText();
                String id = file.getParent().getFileName() + "-" + type.name().toLowerCase();
                Path target = profileRoot(workspaceRoot, profileId).resolve(type.name().toLowerCase())
                        .resolve(id + ".json");
                if (Files.exists(target)) continue;
                GameAnalyticsSnapshot snapshot = new GameAnalyticsSnapshot(SCHEMA_VERSION, id, capturedAt,
                        profileId, root.path("profile").path("name").asText(),
                        root.path("profile").path("emulatorSlot").asText(),
                        root.path("profile").path("emulatorType").asText(), type, power, labyrinth);
                Map<Long, PlayerNameObservation> names = loadNames(workspaceRoot);
                observeDirectNames(snapshot.result(), capturedAt, names);
                writeSnapshot(workspaceRoot, snapshot);
                writeNames(workspaceRoot, names);
            }
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record PlayerNameObservation(String name, String lastObservedAt) {
    }

    private record PlayerPowerObservation(long value, String lastObservedAt) {
    }

    private record PlayerNameCacheDocument(int schemaVersion,
                                           Map<Long, PlayerNameObservation> players) {
    }
}
