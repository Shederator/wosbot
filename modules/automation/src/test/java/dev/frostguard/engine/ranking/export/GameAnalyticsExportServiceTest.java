package dev.frostguard.engine.ranking.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import dev.frostguard.engine.ranking.history.GameAnalyticsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAnalyticsExportServiceTest {

    @TempDir
    Path directory;

    @Test
    void exportsSelectedSnapshotOnlyWhenRequested() throws Exception {
        GameAnalyticsSnapshot snapshot = new GameAnalyticsSnapshot(1, "sample",
                "2026-08-16T03:15:02Z", 7L, "Default", "0", "MUMU",
                GameAnalyticsCollectionType.POWER,
                List.of(new AllianceRankingEntryData(1, 42, "Ł; \"B\"", 1234)), List.of());
        Path json = directory.resolve("ranking.json");
        Path csv = directory.resolve("ranking.csv");

        GameAnalyticsExportService exporter = new GameAnalyticsExportService();
        exporter.exportJson(json, snapshot);
        exporter.exportCsv(csv, snapshot);

        JsonNode document = new ObjectMapper().readTree(json.toFile());
        assertEquals(1, document.path("schemaVersion").asInt());
        assertEquals(42, document.path("power").get(0).path("playerId").asLong());
        byte[] csvBytes = Files.readAllBytes(csv);
        assertEquals((byte) 0xef, csvBytes[0]);
        assertEquals((byte) 0xbb, csvBytes[1]);
        assertEquals((byte) 0xbf, csvBytes[2]);
        String csvText = Files.readString(csv);
        assertTrue(csvText.contains("rank;player_id;player_name;player_name_from_cache;power;power_from_cache"));
        assertTrue(csvText.contains("\"Ł; \"\"B\"\"\";false;1234"));
    }
}
