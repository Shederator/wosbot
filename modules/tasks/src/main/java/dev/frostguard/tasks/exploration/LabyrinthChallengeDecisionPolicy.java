package dev.frostguard.tasks.exploration;

import java.util.Locale;

/**
 * Pure "tail of the tape" decision logic for a Labyrinth Challenge attempt: given troop-strength
 * data read off the stat-comparison screen, decide which troop type to lean into, what percentage
 * preset that lean maps to, and what order to try leans in across a multi-attempt pass.
 *
 * <p>Extracted from {@link LabyrinthRaidRoutine}, where this exact policy was live-verified at
 * Research Center / Gear Forge stage 4-4: two real attempts (60/20/20 and 80/10/10 Infantry
 * leans) both lost, but the MODERATE lean (60/20/20) came far closer to winning (enemy left at
 * 56% HP) than the AGGRESSIVE one (80/10/10, enemy left at 7% HP) -- the opposite of "more lean is
 * always better". Two rules follow directly from that result and are encoded here rather than
 * left to be rediscovered per zone: (1) presets stay at a moderate 60% lean, never escalate
 * further, and (2) a loss tries a genuinely DIFFERENT troop type next, never a more extreme
 * version of the same one.
 *
 * <p>Deliberately holds no OCR, no screen coordinates, and no emulator interaction of any kind --
 * every method here is a pure function over already-read data, which is what makes it directly
 * unit-testable and safe to share across zones with completely different screen layouts. Cave of
 * Monsters and Charm Mine ({@link DailyLabyrinthRoutine}) do not yet call this: their Challenge
 * flow is proven live for formation setup, but their own stat-comparison/deploy/result screen
 * coordinates have not been live-calibrated (their layout is confirmed different from Research
 * Center/Gear Forge's -- DailyLabyrinthRoutine's own zone-formation comments document Cave of
 * Monsters/Charm Mine skipping straight from Challenge to a single combined screen, not the
 * separate stat-details/deploy/result sequence this policy was built against). Wiring Cave of
 * Monsters/Charm Mine into this same decision policy is real, scoped follow-up work once those
 * coordinates exist -- not something to guess blind.
 */
public final class LabyrinthChallengeDecisionPolicy {

    private LabyrinthChallengeDecisionPolicy() {}

    public enum TroopType { INFANTRY, LANCER, MARKSMAN }

    /** Small fixed preset library (presets, not live math) -- percentages for
     *  {Infantry, Lancer, Marksman}, indexed by which type to lean into. Stays at a moderate 60%
     *  lean; see the class header for why a more extreme lean tested worse, not better. */
    public static int[] presetFor(TroopType lean) {
        return switch (lean) {
            case INFANTRY -> new int[] { 60, 20, 20 };
            case LANCER -> new int[] { 20, 60, 20 };
            case MARKSMAN -> new int[] { 20, 20, 60 };
        };
    }

    /** Which troop type a preset leans into -- the highest of the three percentages. Used so an
     *  escalation-on-loss step genuinely tries a different composition than a caller-supplied
     *  configured default, not just a different composition than an OCR-derived guess. */
    public static TroopType leanOf(int[] preset) {
        if (preset[0] >= preset[1] && preset[0] >= preset[2]) return TroopType.INFANTRY;
        if (preset[1] >= preset[2]) return TroopType.LANCER;
        return TroopType.MARKSMAN;
    }

    /** Primary lean first, then the troop type with the next-highest strength (a genuinely
     *  different composition) -- never a more extreme version of the same lean. */
    public static TroopType[] orderedLeanCandidates(TroopType primary) {
        TroopType[] others = new TroopType[2];
        int idx = 0;
        for (TroopType t : TroopType.values()) {
            if (t != primary) {
                others[idx++] = t;
            }
        }
        return new TroopType[] { primary, others[0], others[1] };
    }

    /**
     * Picks the troop type with the highest average strength value, given per-type sums and
     * sample counts (e.g. from OCR'ing several stat rows per type). A type with fewer than
     * {@code minCountPerType} readable samples is excluded from consideration rather than
     * disqualifying the whole read -- only when EVERY type falls short does this return
     * {@code null} (too little data anywhere to trust a decision), matching this codebase's
     * "don't guess" convention for an unreadable screen.
     *
     * @param sums index-aligned with {@link TroopType#ordinal()}: summed strength values per type
     * @param counts index-aligned with {@link TroopType#ordinal()}: how many readable samples went
     *               into each sum
     * @param minCountPerType minimum readable samples required for a type to be considered
     *                        (Research Center/Gear Forge use 2, out of 4 rows per type)
     */
    public static TroopType strongestTroopType(double[] sums, int[] counts, int minCountPerType) {
        TroopType best = null;
        double bestAvg = Double.NEGATIVE_INFINITY;
        for (TroopType type : TroopType.values()) {
            if (counts[type.ordinal()] < minCountPerType) {
                continue;
            }
            double avg = sums[type.ordinal()] / counts[type.ordinal()];
            if (avg > bestAvg) {
                bestAvg = avg;
                best = type;
            }
        }
        return best;
    }

    /** Human-readable summary of per-type averages, for logging -- mirrors the format
     *  LabyrinthRaidRoutine already logs live ("INFANTRY=12.3%(4/4 rows) ..."). */
    public static String summarize(double[] sums, int[] counts, int minCountPerType) {
        StringBuilder summary = new StringBuilder();
        for (TroopType type : TroopType.values()) {
            int count = counts[type.ordinal()];
            if (count < minCountPerType) {
                summary.append(type).append("=unreadable ");
                continue;
            }
            double avg = sums[type.ordinal()] / count;
            summary.append(type).append("=").append(String.format(Locale.US, "%.1f%%", avg))
                    .append("(").append(count).append(" rows) ");
        }
        return summary.toString().trim();
    }
}
