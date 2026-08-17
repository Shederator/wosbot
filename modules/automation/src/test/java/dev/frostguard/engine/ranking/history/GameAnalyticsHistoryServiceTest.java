package dev.frostguard.engine.ranking.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureResult;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAnalyticsHistoryServiceTest {

    @TempDir
    Path workspace;

    @Test
    void persistsValuesAndRefreshesCachedNamesFromLaterPowerHistory() throws Exception {
        AtomicInteger seconds = new AtomicInteger();
        Clock advancingClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() {
                return Instant.parse("2026-08-16T03:15:02Z").plusSeconds(seconds.getAndIncrement());
            }
        };
        GameAnalyticsHistoryService history = new GameAnalyticsHistoryService(
                new ObjectMapper(), advancingClock);
        AccountDescriptor profile = new AccountDescriptor(7L, "Default", "0", true, 1L, 30L);
        history.save(workspace, profile, EmulatorType.MUMU, GameAnalyticsCollectionType.POWER,
                new AllianceRankingCaptureResult(
                        List.of(new AllianceRankingEntryData(1, 42, "Member", 1234)), List.of()));
        history.save(workspace, profile, EmulatorType.MUMU, GameAnalyticsCollectionType.LABYRINTH,
                new AllianceRankingCaptureResult(List.of(),
                        List.of(new LabyrinthRankingEntryData(1, 42, null, 607))));
        history.save(workspace, profile, EmulatorType.MUMU, GameAnalyticsCollectionType.POWER,
                new AllianceRankingCaptureResult(
                        List.of(new AllianceRankingEntryData(1, 42, "Renamed Member", 2345)), List.of()));

        List<GameAnalyticsSnapshot> snapshots = history.list(workspace, 7L);

        assertEquals(3, snapshots.size());
        GameAnalyticsSnapshot labyrinth = snapshots.stream()
                .filter(value -> value.type() == GameAnalyticsCollectionType.LABYRINTH)
                .findFirst().orElseThrow();
        assertEquals(607, labyrinth.labyrinth().getFirst().score());
        assertEquals("Renamed Member", labyrinth.labyrinth().getFirst().playerName());
        assertTrue(labyrinth.labyrinth().getFirst().playerNameFromCache());
    }

    @Test
    void migratesLegacyAutomaticExportsIntoInternalHistory() throws Exception {
        Path legacy = workspace.resolve("game-analytics/20260816-031310");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("analytics.json"), """
                {
                  "schemaVersion": 1,
                  "capturedAt": "2026-08-16T03:13:10Z",
                  "profile": {"id": 7, "name": "Default", "emulatorSlot": "0", "emulatorType": "MUMU"},
                  "power": [],
                  "labyrinth": [{"rank": 1, "playerId": 42, "playerName": null, "score": 607}]
                }
                """);

        List<GameAnalyticsSnapshot> snapshots = new GameAnalyticsHistoryService().list(workspace, 7L);

        assertEquals(1, snapshots.size());
        assertEquals(GameAnalyticsCollectionType.LABYRINTH, snapshots.getFirst().type());
        assertEquals(607, snapshots.getFirst().labyrinth().getFirst().score());
    }

    @Test
    void migratesProfileNameCacheIntoWorkspaceMemoryWithoutNewRun() throws Exception {
        Path legacyNames = workspace.resolve("data/game-analytics/profiles/7/player-names.json");
        Files.createDirectories(legacyNames.getParent());
        Files.writeString(legacyNames, "{\"42\":\"Member\"}");

        new GameAnalyticsHistoryService().list(workspace, 7L);

        Path sharedNames = workspace.resolve("data/game-analytics/player-names.json");
        assertTrue(Files.isRegularFile(sharedNames));
        assertEquals("Member", new ObjectMapper().readTree(sharedNames.toFile())
                .path("players").path("42").path("name").asText());
    }

    @Test
    void storesNamesObservedDirectlyInLabyrinthResponses() throws Exception {
        GameAnalyticsHistoryService history = new GameAnalyticsHistoryService(new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-16T03:52:47Z"), ZoneOffset.UTC));
        AccountDescriptor profile = new AccountDescriptor(7L, "Default", "0", true, 1L, 30L);

        history.save(workspace, profile, EmulatorType.MUMU, GameAnalyticsCollectionType.LABYRINTH,
                new AllianceRankingCaptureResult(List.of(),
                        List.of(new LabyrinthRankingEntryData(1, 42, "Labyrinth Member", 607))));

        Path sharedNames = workspace.resolve("data/game-analytics/player-names.json");
        assertEquals("Labyrinth Member", new ObjectMapper().readTree(sharedNames.toFile())
                .path("players").path("42").path("name").asText());
    }

    @Test
    void preservesTrueRanksAndFillsMissingPowerFromPriorSnapshots() throws Exception {
        GameAnalyticsHistoryService history = new GameAnalyticsHistoryService();
        AccountDescriptor profile = new AccountDescriptor(7L, "Default", "0", true, 1L, 30L);
        history.save(workspace, profile, EmulatorType.MUMU, GameAnalyticsCollectionType.POWER,
                new AllianceRankingCaptureResult(List.of(
                        new AllianceRankingEntryData(1, 41, "First", 2_000),
                        new AllianceRankingEntryData(2, 42, "Second", 1_000)), List.of()));

        GameAnalyticsSnapshot snapshot = history.save(workspace, profile, EmulatorType.MUMU,
                GameAnalyticsCollectionType.POWER, new AllianceRankingCaptureResult(List.of(
                        new AllianceRankingEntryData(1, 41, "First", 2_100),
                        new AllianceRankingEntryData(2, 42, null, null, false, false)), List.of()));

        assertEquals(2, snapshot.power().get(1).rank());
        assertEquals(1_000, snapshot.power().get(1).value());
        assertTrue(snapshot.power().get(1).powerFromCache());
    }
}
