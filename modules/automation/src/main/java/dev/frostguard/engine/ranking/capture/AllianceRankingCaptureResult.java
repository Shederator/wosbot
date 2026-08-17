package dev.frostguard.engine.ranking.capture;

import dev.frostguard.api.domain.AllianceRankingEntryData;
import dev.frostguard.api.domain.LabyrinthRankingEntryData;

import java.util.List;

public record AllianceRankingCaptureResult(List<AllianceRankingEntryData> power,
                                           List<LabyrinthRankingEntryData> labyrinth) {
    public AllianceRankingCaptureResult {
        power = List.copyOf(power);
        labyrinth = List.copyOf(labyrinth);
    }
}
