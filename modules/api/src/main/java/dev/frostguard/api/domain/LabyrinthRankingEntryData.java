package dev.frostguard.api.domain;

/** One alliance member found in the Labyrinth ranking response. */
public record LabyrinthRankingEntryData(int rank, long playerId, String playerName, long score,
                                        boolean playerNameFromCache) {

    public LabyrinthRankingEntryData(int rank, long playerId, String playerName, long score) {
        this(rank, playerId, playerName, score, false);
    }

    public LabyrinthRankingEntryData {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (playerId < 1) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        playerName = playerName == null || playerName.isBlank() ? null : playerName;
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative");
        }
    }
}
