package dev.frostguard.engine.ranking.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import dev.frostguard.engine.ranking.history.GameAnalyticsSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Creates an explicit user-requested JSON or CSV export from a saved snapshot. */
public final class GameAnalyticsExportService {

    private static final char CSV_SEPARATOR = ';';
    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void exportJson(Path target, GameAnalyticsSnapshot snapshot) throws IOException {
        validate(target, snapshot);
        writeAtomically(target.toAbsolutePath().normalize(), mapper.writeValueAsBytes(snapshot));
    }

    public void exportCsv(Path target, GameAnalyticsSnapshot snapshot) throws IOException {
        validate(target, snapshot);
        String csv = snapshot.type() == GameAnalyticsCollectionType.POWER
                ? powerCsv(snapshot.power()) : labyrinthCsv(snapshot.labyrinth());
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] excelCompatible = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, excelCompatible, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, excelCompatible, UTF8_BOM.length, body.length);
        writeAtomically(target.toAbsolutePath().normalize(), excelCompatible);
    }

    private void validate(Path target, GameAnalyticsSnapshot snapshot) throws IOException {
        if (target == null || snapshot == null) {
            throw new IllegalArgumentException("export target and snapshot are required");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private String powerCsv(List<AllianceRankingEntryData> entries) {
        StringBuilder csv = new StringBuilder(
                "rank;player_id;player_name;player_name_from_cache;power;power_from_cache\n");
        for (AllianceRankingEntryData entry : entries) {
            csv.append(entry.rank()).append(CSV_SEPARATOR).append(entry.playerId()).append(CSV_SEPARATOR)
                    .append(csvField(entry.playerName())).append(CSV_SEPARATOR)
                    .append(entry.playerNameFromCache()).append(CSV_SEPARATOR)
                    .append(entry.value() == null ? "" : entry.value()).append(CSV_SEPARATOR)
                    .append(entry.powerFromCache()).append('\n');
        }
        return csv.toString();
    }

    private String labyrinthCsv(List<LabyrinthRankingEntryData> entries) {
        StringBuilder csv = new StringBuilder("rank;player_id;player_name;player_name_from_cache;labyrinth_score\n");
        for (LabyrinthRankingEntryData entry : entries) {
            csv.append(entry.rank()).append(CSV_SEPARATOR).append(entry.playerId()).append(CSV_SEPARATOR)
                    .append(csvField(entry.playerName())).append(CSV_SEPARATOR)
                    .append(entry.playerNameFromCache()).append(CSV_SEPARATOR).append(entry.score()).append('\n');
        }
        return csv.toString();
    }

    private String csvField(String value) {
        return value == null ? "" : '"' + value.replace("\"", "\"\"") + '"';
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
}
