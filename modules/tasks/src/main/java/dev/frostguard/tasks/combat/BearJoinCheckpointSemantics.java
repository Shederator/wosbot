package dev.frostguard.tasks.combat;

/** Conservative restart meaning for durable join substates. */
final class BearJoinCheckpointSemantics {

    private BearJoinCheckpointSemantics() {
    }

    static boolean plusMayHaveBeenCommitted(String substate) {
        return "JOIN_ARMED".equals(substate) || "PLUS_COMMITTED".equals(substate);
    }

    static boolean hasReconstructableRowIdentity(BearRallyListTraversal.Row row) {
        return row != null
                && row.control().identityFingerprint() != 0L
                && !row.control().leaderText().isBlank()
                && !"NONE".equals(row.control().leaderText());
    }

    static boolean carriesSpatialFrontier(String substate) {
        return "ROW_COMPLETED".equals(substate)
                || "RECOVERED_PLUS_ABORTED".equals(substate)
                || plusMayHaveBeenCommitted(substate);
    }

    static boolean provesWarListProvenance(
            String substate, BearNavigationPolicy.Screen screen) {
        return plusMayHaveBeenCommitted(substate)
                && (screen == BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION
                || screen == BearNavigationPolicy.Screen.MARCH_QUEUE_FULL
                || screen == BearNavigationPolicy.Screen.FORMATION);
    }
}
