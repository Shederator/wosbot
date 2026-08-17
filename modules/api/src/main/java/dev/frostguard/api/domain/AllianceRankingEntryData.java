package dev.frostguard.api.domain;

/** One validated member entry in an alliance-scoped ranking. */
public record AllianceRankingEntryData(int rank, long playerId, String playerName, Long value,
                                       boolean playerNameFromCache, boolean powerFromCache) {

    public AllianceRankingEntryData(int rank, long playerId, String playerName, long value) {
        this(rank, playerId, playerName, value, false, false);
    }

    public AllianceRankingEntryData(int rank, long playerId, String playerName, long value,
                                    boolean playerNameFromCache) {
        this(rank, playerId, playerName, value, playerNameFromCache, false);
    }

    public AllianceRankingEntryData {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (playerId < 1) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        playerName = playerName == null || playerName.isBlank() ? null : playerName;
        if (value != null && value < 0) {
            throw new IllegalArgumentException("value must not be negative");
        }
    }
}
