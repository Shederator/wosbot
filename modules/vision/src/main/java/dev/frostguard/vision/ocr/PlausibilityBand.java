package dev.frostguard.vision.ocr;

/**
 * The "decline rather than guess" OCR sanity check used across the codebase: a fresh numeric
 * reading is rejected as a likely misread, rather than trusted, when it moves too far from the
 * last known-good value for the same field. The misreads actually observed live (a dropped decimal
 * point, a covering popup, a wrong screen region) land far outside any believable change.
 *
 * <p>Both bounds are stated explicitly rather than one being derived from the other, because the
 * believable range is <em>not</em> symmetric and deriving it hid that. A single {@code maxRatio} of
 * 1.5 yields the band {@code [1/1.5, 1.5]} = {@code [0.667, 1.5]}, which permits a 50% increase but
 * only a 33% decrease. For a spendable resource that is backwards: a stockpile routinely drops by
 * most of its value in one step when an upgrade is paid for, whereas a large sudden <em>rise</em>
 * is the shape a misread usually takes. Calling that band "&plusmn;50%" was inaccurate, and the
 * inaccuracy suppressed exactly the legitimate readings it was meant to preserve.
 *
 * <p>Bands are therefore chosen per metric rather than shared, because the three metrics behave
 * differently:
 * <ul>
 *   <li><b>Power</b> grows gradually and does not legitimately fall far, so a steep move in either
 *       direction is far more likely to be a misread than a real change. Tight band.</li>
 *   <li><b>Coal</b> and <b>gems</b> are spent in lumps. A single building upgrade or purchase can
 *       consume most of the balance between two samples, so their lower bound has to be permissive
 *       or the tracker silently discards the very events it exists to record.</li>
 * </ul>
 *
 * <p>The permissive lower bound is a deliberate trade-off worth stating plainly: it means a
 * downward misread on coal/gems (a dropped leading digit, say) can be accepted as a real spend.
 * That is the lesser harm. Rejecting it instead blanks the graph on every genuine purchase — the
 * failure actually observed — whereas a downward misread is corrected by the next sample. The
 * upper bounds stay tight because that is where the observed misreads landed: ~1.7-1.8x jumps, and
 * one 839,000,000-against-1,174,000 read.
 */
public final class PlausibilityBand {

    /** Power: grows gradually, does not legitimately collapse. Tight in both directions. */
    public static final PlausibilityBand POWER = new PlausibilityBand(0.80, 1.50);

    /**
     * Coal: spent in lumps by building upgrades. A single upgrade can take most of the stockpile,
     * so the floor allows a 95% drawdown; the ceiling stays tight to catch the observed misreads.
     */
    public static final PlausibilityBand COAL = new PlausibilityBand(0.05, 1.50, 1_000_000L);

    /** Gems: spent in lumps by purchases; same reasoning as {@link #COAL}. */
    public static final PlausibilityBand GEMS = new PlausibilityBand(0.05, 1.50);

    private final double minRatio;
    private final double maxRatio;
    private final long maxAbsoluteIncrease;

    /**
     * @param minRatio lowest believable {@code candidate / previous}; must be in {@code (0.0, 1.0]}
     * @param maxRatio highest believable {@code candidate / previous}; must be {@code >= 1.0}
     */
    public PlausibilityBand(double minRatio, double maxRatio) {
        this(minRatio, maxRatio, 0L);
    }

    /**
     * @param maxAbsoluteIncrease increase accepted regardless of ratio, for low stockpiles where
     *                            ordinary production makes ratios unstable; must be non-negative
     */
    public PlausibilityBand(double minRatio, double maxRatio, long maxAbsoluteIncrease) {
        if (!(minRatio > 0.0) || minRatio > 1.0) {
            throw new IllegalArgumentException("minRatio must be in (0.0, 1.0], got " + minRatio);
        }
        if (maxRatio < 1.0) {
            throw new IllegalArgumentException("maxRatio must be >= 1.0, got " + maxRatio);
        }
        if (maxAbsoluteIncrease < 0L) {
            throw new IllegalArgumentException("maxAbsoluteIncrease must be non-negative, got "
                    + maxAbsoluteIncrease);
        }
        this.minRatio = minRatio;
        this.maxRatio = maxRatio;
        this.maxAbsoluteIncrease = maxAbsoluteIncrease;
    }

    public double minRatio() {
        return minRatio;
    }

    public double maxRatio() {
        return maxRatio;
    }

    public long maxAbsoluteIncrease() {
        return maxAbsoluteIncrease;
    }

    /**
     * Returns {@code true} when {@code candidate} sits within this band relative to
     * {@code previous}. A {@code previous} of zero or negative has nothing meaningful to compare
     * against and is always treated as plausible — there is no last known-good value to move away
     * from.
     */
    public boolean isPlausible(long candidate, long previous) {
        if (previous <= 0) {
            return true;
        }
        if (candidate >= previous && candidate - previous <= maxAbsoluteIncrease) {
            return true;
        }
        double ratio = (double) candidate / (double) previous;
        return ratio >= minRatio && ratio <= maxRatio;
    }

    @Override
    public String toString() {
        return "[" + minRatio + ", " + maxRatio + "; absolute increase <= "
                + maxAbsoluteIncrease + "]";
    }
}
