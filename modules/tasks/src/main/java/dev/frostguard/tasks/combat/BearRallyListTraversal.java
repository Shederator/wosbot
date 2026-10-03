package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic cursor for one top-to-bottom pass through the live Bear Rally list.
 *
 * <p>This class deliberately has no semantic "already attempted" history. A pass is spatial: after
 * a row transaction completes, the next eligible row is the one visibly below that row. A scroll
 * is accepted only when the last visible Bear row can be found again by unique captain identity
 * read from that frame. Reopening the Rally tab resets a pass to the top.
 */
final class BearRallyListTraversal {

    static final Duration EMPTY_OBSERVATION_WINDOW = Duration.ofSeconds(3);

    record Row(BearRallyScanner.RallyRow control) {
        Row {
            Objects.requireNonNull(control, "control");
        }

        int y() {
            return control.rowY();
        }

        boolean sameIdentity(Row other) {
            return other != null
                    && control.identityFingerprint() != 0L
                    && control.identityFingerprint() == other.control.identityFingerprint()
                    && !control.leaderText().isBlank()
                    && control.leaderText().equals(other.control.leaderText());
        }

        boolean joinable() {
            return control.joinable();
        }
    }

    record Observation(Instant observedAt, List<Row> rows, boolean absoluteBottom) {
        Observation {
            Objects.requireNonNull(observedAt, "observedAt");
            rows = rows == null ? List.of() : rows.stream()
                    .sorted(Comparator.comparingInt(Row::y))
                    .toList();
        }
    }

    enum Kind {
        JOIN,
        WAIT_FOR_VISIBLE_PLUS,
        SCROLL,
        REOPEN_FROM_TOP,
        OVERLAP_NOT_PROVEN
    }

    record Decision(Kind kind, Row row) {
        static Decision of(Kind kind) {
            return new Decision(kind, null);
        }
    }

    private Row completedRow;
    private Row pendingScrollAnchor;
    private Instant emptySince;

    Decision decide(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (pendingScrollAnchor != null) {
            Optional<Row> overlap = uniqueIdentity(observation.rows(), pendingScrollAnchor);
            if (overlap.isEmpty()) {
                return Decision.of(Kind.OVERLAP_NOT_PROVEN);
            }
            completedRow = overlap.get();
            pendingScrollAnchor = null;
        }

        if (completedRow != null
                && observation.rows().stream().filter(completedRow::sameIdentity).count() > 1) {
            return Decision.of(Kind.OVERLAP_NOT_PROVEN);
        }
        if (completedRow != null
                && !observation.rows().isEmpty()
                && observation.rows().stream().noneMatch(completedRow::sameIdentity)) {
            // Rows below a departed rally can collapse upward past its old y-coordinate. The old
            // coordinate therefore cannot be used as a cursor. Reopen the list and establish a
            // new, visibly ordered top pass instead of skipping an unproven successor.
            reopenedAtTop();
            return Decision.of(Kind.REOPEN_FROM_TOP);
        }

        Optional<Row> next = nextJoinableBelowCompleted(observation.rows());
        if (next.isPresent()) {
            emptySince = null;
            return new Decision(Kind.JOIN, next.get());
        }

        if (emptySince == null) {
            emptySince = observation.observedAt();
        }
        if (Duration.between(emptySince, observation.observedAt())
                .compareTo(EMPTY_OBSERVATION_WINDOW) < 0) {
            return Decision.of(Kind.WAIT_FOR_VISIBLE_PLUS);
        }
        emptySince = null;
        if (observation.absoluteBottom()) {
            return Decision.of(Kind.REOPEN_FROM_TOP);
        }
        // A zero-plus viewport still contains generic Bear rows. If there are truly no rows,
        // scrolling cannot be proven safe from this observation and must fail closed.
        if (observation.rows().isEmpty()) return Decision.of(Kind.OVERLAP_NOT_PROVEN);
        pendingScrollAnchor = observation.rows().getLast();
        return new Decision(Kind.SCROLL, pendingScrollAnchor);
    }

    void completed(Row row) {
        completedRow = Objects.requireNonNull(row, "row");
        emptySince = null;
    }

    void reopenedAtTop() {
        completedRow = null;
        pendingScrollAnchor = null;
        emptySince = null;
    }

    void scrollAuthorized(Row exactAnchor) {
        pendingScrollAnchor = Objects.requireNonNull(exactAnchor, "exactAnchor");
    }

    void restoreCompleted(Row row) {
        completedRow = row;
        pendingScrollAnchor = null;
        emptySince = null;
    }

    /** Selects the current frame's first legal row after the spatial frontier. */
    Optional<Row> currentTopmostJoinable(List<Row> rows) {
        return nextJoinableBelowCompleted(rows == null ? List.of() : rows.stream()
                .sorted(Comparator.comparingInt(Row::y)).toList());
    }

    Optional<Row> authorizeCandidate(Row expected, List<Row> freshRows) {
        if (expected == null || freshRows == null
                || freshRows.stream().filter(expected::sameIdentity).count() != 1) return Optional.empty();
        return currentTopmostJoinable(freshRows).filter(expected::sameIdentity);
    }

    private static Optional<Row> uniqueIdentity(List<Row> rows, Row expected) {
        List<Row> matches = rows.stream().filter(expected::sameIdentity).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    static boolean stableAbsoluteBottom(
            List<BearRallyScanner.RallyRow> before,
            List<BearRallyScanner.RallyRow> immediate,
            List<BearRallyScanner.RallyRow> settled) {
        return sameRowsAtSamePositions(before, immediate)
                && sameRowsAtSamePositions(immediate, settled);
    }

    private static boolean sameRowsAtSamePositions(
            List<BearRallyScanner.RallyRow> before,
            List<BearRallyScanner.RallyRow> after) {
        if (before == null || after == null || before.isEmpty() || before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            BearRallyScanner.RallyRow left = before.get(i);
            BearRallyScanner.RallyRow right = after.get(i);
            Row identity = new Row(left);
            if (before.stream().filter(row -> identity.sameIdentity(new Row(row))).count() != 1
                    || after.stream().filter(row -> identity.sameIdentity(new Row(row))).count() != 1) return false;
            if (left.rowY() != right.rowY()
                    || left.identityFingerprint() == 0L
                    || left.identityFingerprint() != right.identityFingerprint()
                    || left.leaderText().isBlank()
                    || !left.leaderText().equals(right.leaderText())) {
                return false;
            }
        }
        return true;
    }

    private Optional<Row> nextJoinableBelowCompleted(List<Row> rows) {
        if (completedRow == null) {
            return rows.stream().filter(Row::joinable).findFirst();
        }
        Optional<Row> same = uniqueIdentity(rows, completedRow);
        if (same.isEmpty()) return Optional.empty();
        int frontier = same.get().y();
        return rows.stream().filter(row -> row.y() > frontier).filter(Row::joinable).findFirst();
    }
}
