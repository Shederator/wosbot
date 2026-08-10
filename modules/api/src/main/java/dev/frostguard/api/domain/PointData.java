package dev.frostguard.api.domain;

import java.util.Objects;

/**
 * Lightweight coordinate holder for screen positions expressed
 * using a column/row convention. Used pervasively by the engine
 * for tap targets, search boundaries, and swipe endpoints.
 *
 * <p>While constructed as mutable for shift operations, callers
 * should prefer the copy constructor when aliasing is a concern.</p>
 */
public class PointData {

    private int col;
    private int row;

    /**
     * Constructs a point at the specified column and row.
     *
     * @param col horizontal coordinate
     * @param row vertical coordinate
     */
    public PointData(int col, int row) {
        this.col = col;
        this.row = row;
    }

    /**
     * Creates an independent copy of the supplied point to
     * prevent unintentional mutation through shared references.
     *
     * @param source the point to duplicate
     */
    public PointData(PointData source) {
        this.col = source.col;
        this.row = source.row;
    }

    /** Returns the horizontal coordinate. */
    public int col() {
        return col;
    }

    /** Returns the vertical coordinate. */
    public int row() {
        return row;
    }

    /**
     * Adjusts the column by the given offset and returns the
     * resulting coordinate.
     *
     * @param delta the amount to add (may be negative)
     * @return the updated column value
     */
    public int shiftCol(int delta) {
        col += delta;
        return col;
    }

    /**
     * Adjusts the row by the given offset and returns the
     * resulting coordinate.
     *
     * @param delta the amount to add (may be negative)
     * @return the updated row value
     */
    public int shiftRow(int delta) {
        row += delta;
        return row;
    }

    /**
     * Computes the Manhattan (taxicab) distance between this
     * point and the supplied target.
     *
     * @param target the other point
     * @return the non-negative Manhattan distance
     */
    public int manhattanDistanceTo(PointData target) {
        return Math.abs(this.col - target.col)
                + Math.abs(this.row - target.row);
    }

    /**
     * Produces a new point whose coordinates are the midpoint
     * between this point and the supplied partner.
     *
     * @param other the other point
     * @return a fresh midpoint
     */
    public PointData midpointWith(PointData other) {
        return new PointData(
                (this.col + other.col) / 2,
                (this.row + other.row) / 2);
    }

    /**
     * Returns a new point with coordinates offset from this one.
     *
     * @param colDelta horizontal offset
     * @param rowDelta vertical offset
     * @return a fresh displaced point (this point remains unchanged)
     */
    public PointData displaced(int colDelta, int rowDelta) {
        return new PointData(col + colDelta, row + rowDelta);
    }

    /**
     * Tests whether this point lies within the rectangle defined
     * by two corner points (inclusive boundaries).
     *
     * @param topLeft     upper-left corner of the region
     * @param bottomRight lower-right corner of the region
     * @return {@code true} when this point is inside or on the boundary
     */
    public boolean isWithin(PointData topLeft, PointData bottomRight) {
        return col >= topLeft.col && col <= bottomRight.col
                && row >= topLeft.row && row <= bottomRight.row;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public int getX()               { return col; }
    public int getY()               { return row; }
    public int addX(int offset)     { return shiftCol(offset); }
    public int addY(int offset)     { return shiftRow(offset); }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PointData other)) return false;
        return col == other.col && row == other.row;
    }

    @Override
    public int hashCode() {
        return Objects.hash(col, row);
    }

    @Override
    public String toString() {
        return "PointData{col=" + col + ", row=" + row + "}";
    }
}
