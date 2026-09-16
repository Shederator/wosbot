package dev.frostguard.tasks.events;

import java.util.List;
import java.util.OptionalInt;

/** Scores reachable, positively identified catch points; a sprite centroid is not a fish head. */
final class FishingHarvestPlanner {
    /** Unclassified foreground opportunity, not a verified head, value or collectible identity. */
    record Opportunity(double x, double y, double width, double height, double vx, double relativeVy) { }
    record Candidate(double headX, double headY, double vx, double relativeVy,
            int points, double confidence, boolean collectible) { }

    private static final double MIN_CONFIDENCE = 0.90;
    private static final double MAX_HORIZON_MS = 800;
    // Conservative provisional bound. Live feedback must validate reachability before release.
    private static final double HORIZONTAL_PIXELS_PER_MS = 1.5;
    private static final double EDGE_MARGIN = 30;
    // Caught sprites obscure the indicator near a wall. Keep quantity-first pursuit inland.
    private static final double HAUL_EDGE_MARGIN = 100;

    private FishingHarvestPlanner() { }

    static int boundedDrag(int hookX, int fingerDrag) {
        // A plausible live finger-to-hook response can be as large as 2.5x, before gain settles.
        // Do not let that uncertainty push a caught stack offscreen.
        int available = fingerDrag < 0 ? Math.max(0, hookX - 70) : Math.max(0, 650 - hookX);
        int bound = (int) Math.floor(available / 2.5);
        return Integer.signum(fingerDrag) * (int)Math.min(Math.abs((long) fingerDrag), bound);
    }

    static OptionalInt fillTarget(int hookX, int hookY, List<Opportunity> objects,
            double inputLatencyMs, int freeHaulSlots) {
        if (freeHaulSlots <= 0 || !Double.isFinite(inputLatencyMs) || inputLatencyMs < 0) {
            return OptionalInt.empty();
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        OptionalInt best = OptionalInt.empty();
        for (var object : objects) {
            if (!valid(object) || object.y() >= hookY) continue;
            double intercept = (hookY - object.y()) / object.relativeVy();
            if (intercept <= 0 || intercept > 2000) continue;
            // Moving fish usually lead with their head; stationary items have no inferred orientation.
            // This is a quantity-first heuristic, not positive species/head identification.
            double offset = Math.abs(object.vx()) > 0.03 ? Math.copySign(object.width() * 0.25, object.vx()) : 0;
            double destination = FishingAvoidancePlanner.reflectedX(object.x(), object.vx(), object.width() / 2, intercept) + offset;
            if (destination < HAUL_EDGE_MARGIN || destination > 720 - HAUL_EDGE_MARGIN) continue;
            // An aligned imminent catch needs no gesture. Do not abandon it just because
            // a new gesture would be too late, chasing a more distant object instead.
            boolean aligned = Math.abs(destination - hookX) <= 22;
            if (!aligned && Math.abs(destination - hookX) / HORIZONTAL_PIXELS_PER_MS
                    + inputLatencyMs + 120 > intercept) continue;
            double score = 1 / (0.3 + intercept / 1000)
                    - Math.abs(destination - hookX) / 1800;
            for (var neighbour : objects) {
                if (neighbour == object || !valid(neighbour) || neighbour.y() >= hookY) continue;
                double time = (hookY - neighbour.y()) / neighbour.relativeVy();
                double nx = FishingAvoidancePlanner.reflectedX(neighbour.x(), neighbour.vx(), neighbour.width() / 2, time);
                if (time >= intercept && time < 2000 && Math.abs(nx - destination) < neighbour.width() / 4 + 12) {
                    score += 0.25 / (1 + time / 1000);
                }
            }
            if (score > bestScore) { bestScore = score; best = OptionalInt.of(aligned ? hookX : (int) Math.round(destination)); }
        }
        return best;
    }

    private static boolean valid(Opportunity object) {
        return Double.isFinite(object.x()) && Double.isFinite(object.y())
                && Double.isFinite(object.width()) && Double.isFinite(object.height())
                && Double.isFinite(object.vx()) && Double.isFinite(object.relativeVy())
                && object.relativeVy() > 0.05 && object.width() >= 20 && object.width() <= 250
                && object.height() >= 12 && object.height() <= 180;
    }

    static OptionalInt target(int hookX, int hookY, List<Candidate> candidates,
            double inputLatencyMs, int freeHaulSlots) {
        if (freeHaulSlots <= 0 || !Double.isFinite(inputLatencyMs) || inputLatencyMs < 0) {
            return OptionalInt.empty();
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        OptionalInt best = OptionalInt.empty();
        for (Candidate candidate : candidates) {
            if (!candidate.collectible() || candidate.confidence() < MIN_CONFIDENCE
                    || !Double.isFinite(candidate.confidence()) || candidate.points() <= 0
                    || !Double.isFinite(candidate.headX()) || !Double.isFinite(candidate.headY())
                    || !Double.isFinite(candidate.vx()) || !Double.isFinite(candidate.relativeVy())
                    || candidate.relativeVy() <= 0 || candidate.headY() >= hookY) continue;
            double interceptMs = (hookY - candidate.headY()) / candidate.relativeVy();
            if (interceptMs < inputLatencyMs || interceptMs > MAX_HORIZON_MS) continue;
            double headX = FishingAvoidancePlanner.reflectedX(candidate.headX(), candidate.vx(), 0, interceptMs);
            double destination = Math.max(EDGE_MARGIN, Math.min(720 - EDGE_MARGIN, headX));
            // Clamping outside the playable band must not falsely claim a head interception.
            if (Math.abs(destination - headX) > 12) continue;
            double movementMs = Math.abs(destination - hookX) / HORIZONTAL_PIXELS_PER_MS;
            if (movementMs + inputLatencyMs > interceptMs) continue;
            double score = candidate.points() * candidate.confidence() / (1 + interceptMs / 1000);
            if (score > bestScore) {
                bestScore = score;
                best = OptionalInt.of((int) Math.round(destination));
            }
        }
        return best;
    }
}
