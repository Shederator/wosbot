package dev.frostguard.api.domain;

import java.util.Objects;

/**
 * Encapsulates the outcome of a template-matching operation performed
 * by the vision subsystem against a screen capture.
 */
public class ImageSearchResultData {

    /** Qualitative match classification. */
    public enum MatchOutcome { HIT, MISS, PARTIAL }

    private MatchOutcome outcome;
    private double matchScore;
    private int hitX;
    private int hitY;
    /**
     * On-screen size of the matched template region ({@code null} when
     * unknown). For multi-scale matches this reflects the scaled size,
     * not the raw template size.
     */
    private SizeData templateSize;

    /* ── static factories ── */

    public static ImageSearchResultData hit(int x, int y, double score) {
        ImageSearchResultData r = new ImageSearchResultData();
        r.outcome = MatchOutcome.HIT;
        r.matchScore = score;
        r.hitX = x;
        r.hitY = y;
        return r;
    }

    public static ImageSearchResultData hit(int x, int y, double score, SizeData templateSize) {
        ImageSearchResultData r = hit(x, y, score);
        r.templateSize = templateSize;
        return r;
    }

    /**
     * Factory for a successful match that also records the actual matched
     * bounding region. {@code x}/{@code y} are the region center; the width
     * and height reflect the (possibly scaled) template size on screen.
     * This is especially important for multi-scale matches, where the
     * effective on-screen size differs from the raw template size.
     */
    public static ImageSearchResultData hit(int x, int y, double score, int width, int height) {
        return hit(x, y, score, new SizeData(Math.max(0, width), Math.max(0, height)));
    }

    public static ImageSearchResultData miss() {
        ImageSearchResultData r = new ImageSearchResultData();
        r.outcome = MatchOutcome.MISS;
        r.matchScore = 0.0;
        r.hitX = -1;
        r.hitY = -1;
        return r;
    }

    /* ── legacy 3-arg constructor for downstream compatibility ── */

    public ImageSearchResultData(boolean found, PointData point, double score) {
        this.outcome = found ? MatchOutcome.HIT : MatchOutcome.MISS;
        this.matchScore = score;
        this.hitX = point != null ? point.getX() : -1;
        this.hitY = point != null ? point.getY() : -1;
    }

    public ImageSearchResultData(boolean found, PointData point, double score, SizeData templateSize) {
        this(found, point, score);
        this.templateSize = templateSize;
    }

    /* ── no-arg for frameworks ── */
    public ImageSearchResultData() {}

    /* ── derived ── */

    public boolean isLocated()                          { return outcome == MatchOutcome.HIT; }
    public boolean isAboveThreshold(double threshold)   { return matchScore >= threshold; }

    /** True when the matched bounding region dimensions are known. */
    public boolean hasMatchedArea() {
        return isLocated() && getMatchWidth() > 0 && getMatchHeight() > 0;
    }

    /**
     * Returns the actual matched bounding region on screen, or {@code null}
     * when the result is a miss or the region dimensions are unknown.
     * Callers should pass this region to the input layer instead of
     * reconstructing or guessing the clickable area themselves.
     *
     * <p>Both corners are <em>inclusive</em>, matching the convention used
     * by {@link AreaData} and the emulator tap implementation: a region of
     * {@code matchWidth} pixels spans exactly {@code matchWidth} distinct
     * column coordinates, so the bottom-right column is
     * {@code topLeftX + matchWidth - 1} (and equivalently for rows).</p>
     */
    public AreaData getMatchedArea() {
        if (!hasMatchedArea()) return null;
        int matchWidth = getMatchWidth();
        int matchHeight = getMatchHeight();
        int topLeftX = hitX - matchWidth / 2;
        int topLeftY = hitY - matchHeight / 2;
        return new AreaData(
                new PointData(topLeftX, topLeftY),
                new PointData(topLeftX + matchWidth - 1, topLeftY + matchHeight - 1));
    }

    /* ── accessors ── */

    public MatchOutcome getOutcome()                    { return outcome; }
    public void setOutcome(MatchOutcome o)              { this.outcome = o; }

    public double getMatchScore()                       { return matchScore; }
    public void setMatchScore(double s)                 { this.matchScore = s; }

    public int getHitX()                                { return hitX; }
    public void setHitX(int x)                          { this.hitX = x; }

    public int getHitY()                                { return hitY; }
    public void setHitY(int y)                          { this.hitY = y; }

    public SizeData getTemplateSize()                   { return templateSize; }
    public void setTemplateSize(SizeData s)             { this.templateSize = s; }

    /** Width of the matched template region in screen pixels (0 when unknown). */
    public int getMatchWidth()                          { return templateSize != null ? Math.max(0, templateSize.getWidth()) : 0; }

    /** Height of the matched template region in screen pixels (0 when unknown). */
    public int getMatchHeight()                         { return templateSize != null ? Math.max(0, templateSize.getHeight()) : 0; }

    /* ── legacy delegates ── */

    public boolean isFound()        { return isLocated(); }
    public void setFound(boolean f) { this.outcome = f ? MatchOutcome.HIT : MatchOutcome.MISS; }
    public double getConfidence()   { return matchScore; }
    public void setConfidence(double c) { this.matchScore = c; }
    public int getX()               { return hitX; }
    public void setX(int x)         { this.hitX = x; }
    public int getY()               { return hitY; }
    public void setY(int y)         { this.hitY = y; }
    public PointData getPoint()     { return isLocated() ? new PointData(hitX, hitY) : null; }
    public double getMatchPercentage() { return matchScore; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ImageSearchResultData that)) return false;
        return Double.compare(matchScore, that.matchScore) == 0
            && hitX == that.hitX && hitY == that.hitY
            && outcome == that.outcome
            && Objects.equals(templateSize, that.templateSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outcome, matchScore, hitX, hitY, templateSize);
    }

    @Override
    public String toString() {
        return outcome + " @(" + hitX + "," + hitY + ") score=" + String.format("%.3f", matchScore)
                + (templateSize == null ? "" : " size=" + templateSize);
    }
}
