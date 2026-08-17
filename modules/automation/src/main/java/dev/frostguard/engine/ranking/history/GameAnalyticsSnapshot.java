package dev.frostguard.engine.ranking.history;

import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;
import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureResult;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;

import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record GameAnalyticsSnapshot(int schemaVersion, String id, String capturedAt,
                                    Long profileId, String profileName, String emulatorSlot,
                                    String emulatorType, GameAnalyticsCollectionType type,
                                    List<AllianceRankingEntryData> power,
                                    List<LabyrinthRankingEntryData> labyrinth) {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    public AllianceRankingCaptureResult result() {
        return new AllianceRankingCaptureResult(power, labyrinth);
    }

    @Override
    public String toString() {
        return DISPLAY_TIME.format(Instant.parse(capturedAt));
    }
}
