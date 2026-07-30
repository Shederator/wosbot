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
    public void setTemplateSize(SizeData s)              { this.templateSize = s; }

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
