package dev.frostguard.engine.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.vision.ocr.PlausibilityBand;

class TelemetryHistoryStoreTest {

    @TempDir
    Path workspace;

    @Test
    void appendsHistoryAndAtomicallyReplacesLatestForOneProfile() throws IOException {
        TelemetryHistoryStore store = new TelemetryHistoryStore(workspace, 42L);
        Map<String, Object> first = sample(100L, null);
        Map<String, Object> second = sample(125L, 7L);

        store.append(first);
        store.append(second);

        assertEquals(2, Files.readAllLines(store.directory().resolve("history.jsonl")).size());
        assertEquals(125L, store.readLatestNumericFields().get("power"));
        assertEquals(7L, store.readLatestNumericFields().get("run.Intel"));
        assertNull(store.readLatestNumericFields().get("gems"));
        assertFalse(Files.exists(store.directory().resolve("latest.json.tmp")));
    }

    @Test
    void missingLatestReturnsNoPreviousSample() throws IOException {
        assertNull(new TelemetryHistoryStore(workspace, 99L).readLatestNumericFields());
    }

    @Test
    void findsLastKnownMetricAcrossRejectedAndMalformedSamples() throws IOException {
        TelemetryHistoryStore store = new TelemetryHistoryStore(workspace, 42L);
        store.append(sample(5_615_421L, 53_700L, 106_999L));
        store.append(sample(5_614_490L, null, 106_999L));
        Files.writeString(store.directory().resolve("history.jsonl"), "not-json\n",
                StandardOpenOption.APPEND);

        Map<String, Object> lastKnown = store.readLastKnownNumericFields(
                Set.of("power", "coal", "gems"));

        assertEquals(5_614_490L, lastKnown.get("power"));
        assertEquals(53_700L, lastKnown.get("coal"));
        assertEquals(106_999L, lastKnown.get("gems"));
        assertFalse(PlausibilityBand.COAL.isPlausible(19_000_000L,
                (long) lastKnown.get("coal")));
    }

    private static Map<String, Object> sample(long power, Long runs) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", "2026-08-21T08:30:00Z");
        sample.put("power", power);
        sample.put("gems", null);
        sample.put("run.Intel", runs);
        return sample;
    }

    private static Map<String, Object> sample(long power, Long coal, Long gems) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", "2026-08-21T08:30:00Z");
        sample.put("power", power);
        sample.put("coal", coal);
        sample.put("gems", gems);
        return sample;
    }
}
