package dev.frostguard.api.domain;

/**
 * Defines a rectangular area on the device display bounded by two diagonal corners.
 * Primarily used for constraining template searches and OCR reads.
 */
public record AreaData(PointData origin, PointData extent) {

    /**
     * Convenience factory that builds a region from raw pixel values.
     */
    public static AreaData of(int originCol, int originRow, int extentCol, int extentRow) {
        return new AreaData(
                new PointData(originCol, originRow),
                new PointData(extentCol, extentRow));
    }

    /**
     * Returns a full-screen region for the standard 720×1280 viewport.
     */
    public static AreaData fullScreen() {
        return of(0, 0, 720, 1280);
    }

    // Legacy compatibility - aliases for old field names
    public PointData topLeft() { return origin; }
    public PointData bottomRight() { return extent; }
}
