package dev.frostguard.engine.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.runtime.WorkspacePaths;

/** Stores append-only telemetry history for one stable profile ID. */
public final class TelemetryHistoryStore {

    private static final Object WRITE_LOCK = new Object();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> FLAT_MAP = new TypeReference<>() {};

    private final Path directory;

    public TelemetryHistoryStore(Path workspaceRoot, long profileId) {
        directory = workspaceRoot.toAbsolutePath().normalize()
                .resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profileId));
    }

    public static TelemetryHistoryStore forCurrentWorkspace(long profileId) {
        return new TelemetryHistoryStore(WorkspacePaths.current().root(), profileId);
    }

    public Path directory() {
        return directory;
    }

    public Map<String, Object> readLatestNumericFields() throws IOException {
        Path latest = directory.resolve("latest.json");
        if (!Files.exists(latest)) {
            return null;
        }
        Map<String, Object> decoded = MAPPER.readValue(latest.toFile(), FLAT_MAP);
        Map<String, Object> numeric = new LinkedHashMap<>();
        decoded.forEach((key, value) -> {
            if (value == null || value instanceof Number) {
                numeric.put(key, value instanceof Number number ? number.longValue() : null);
            }
        });
        return numeric;
    }

    /**
     * Finds the newest non-null numeric value for each requested field. A rejected OCR value is
     * stored as {@code null} in the latest sample, so plausibility checks must search past that gap
     * instead of treating the following reading as if no baseline had ever existed.
     */
    public Map<String, Object> readLastKnownNumericFields(Set<String> fields) throws IOException {
        Path history = directory.resolve("history.jsonl");
        if (!Files.exists(history) || fields.isEmpty()) {
            return null;
        }

        List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = lines.size() - 1; index >= 0 && result.size() < fields.size(); index--) {
            Map<String, Object> decoded;
            try {
                decoded = MAPPER.readValue(lines.get(index), FLAT_MAP);
            } catch (IOException malformedLine) {
                continue;
            }
            for (String field : fields) {
                Object value = decoded.get(field);
                if (!result.containsKey(field) && value instanceof Number number) {
                    result.put(field, number.longValue());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    public void append(Map<String, Object> sample) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(sample);
        synchronized (WRITE_LOCK) {
            Files.createDirectories(directory);
            Files.write(directory.resolve("history.jsonl"),
                    (new String(json, StandardCharsets.UTF_8) + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeAtomically(directory.resolve("latest.json"), json);
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
