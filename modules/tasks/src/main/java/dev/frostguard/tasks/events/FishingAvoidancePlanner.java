package dev.frostguard.tasks.events;

import java.util.ArrayList;
import java.util.List;

/** Bounded space-time search; only its first waypoint is executed before replanning. */
final class FishingAvoidancePlanner {
    record Fish(double x, double y, double width, double height, double vx, double relativeVy) { }
    record Route(int target, boolean safe, List<Integer> positions, List<Double> times) { }

    static final int HOOK_HALF_WIDTH = 35;
    static final int HOOK_HALF_HEIGHT = 45;
    private static final int WALL_MARGIN = 30;
    private static final int LAYERS = 6;
    private static final double STEP_MS = 150;
    private static final double MAX_HORIZONTAL_SPEED = 1.2;
    private static final double RESPONSE_RESERVE_MS = 250;
    private static final double MIN_FIRST_ACTION_MS = 350;
    private static final int CONTROL_CLEARANCE = 25;

    private FishingAvoidancePlanner() { }

    static int target(int hookX, int hookY, List<Fish> fish, double latencyMs) {
        return plan(hookX, hookY, fish, latencyMs).target();
    }

    /** Input duration sets the first leg, not an assertion about encoded frame age.
     * Predictions assume tracked constant velocity, with horizontal wall reflections. */
    static Route plan(int hookX, int hookY, List<Fish> fish, double inputMs) {
        Route unavailable = new Route(hookX, false, List.of(hookX), List.of(0.0));
        if (!Double.isFinite(inputMs) || inputMs < 0 || inputMs > 600 || fish.size() > 256
                || hookX < 0 || hookX >= 720
                || fish.stream().anyMatch(f -> !valid(f))) return unavailable;
        var lanes = new ArrayList<Integer>();
        for (int x = WALL_MARGIN; x <= 720 - WALL_MARGIN; x += 20) lanes.add(x);
        if (!lanes.contains(hookX)) lanes.add(hookX);
        lanes.sort(Integer::compare);
        int count = lanes.size(), origin = lanes.indexOf(hookX);
        double[] costs = new double[count];
        java.util.Arrays.fill(costs, Double.POSITIVE_INFINITY);
        costs[origin] = 0;
        int[][] parents = new int[LAYERS + 1][count];
        for (int[] row : parents) java.util.Arrays.fill(row, -1);
        double[] times = new double[LAYERS + 1];
        int completedLayers = 0;
        for (int layer = 1; layer <= LAYERS; layer++) {
            double duration = layer == 1 ? Math.max(MIN_FIRST_ACTION_MS, inputMs + RESPONSE_RESERVE_MS) : STEP_MS;
            times[layer] = times[layer - 1] + duration;
            double[] next = new double[count];
            java.util.Arrays.fill(next, Double.POSITIVE_INFINITY);
            for (int from = 0; from < count; from++) {
                if (!Double.isFinite(costs[from])) continue;
                for (int to = 0; to < count; to++) {
                    double movement = Math.abs(lanes.get(to) - lanes.get(from));
                    if (movement > Math.min(250, (layer == 1 ? STEP_MS : duration) * MAX_HORIZONTAL_SPEED)) continue;
                    // Prefer a clear early reposition over relying on unexecuted later legs.
                    // Collision checks still require waiting when moving now would cross an object.
                    double cost = costs[from] + movement * (1 + (layer - 1) * 0.1)
                            + Math.abs(lanes.get(to) - hookX) * 0.015;
                    if (cost >= next[to]) continue;
                    boolean clear = layer == 1 ? clearFirstAction(lanes.get(from), lanes.get(to), hookY, fish, inputMs, duration)
                            : clearTransition(lanes.get(from), lanes.get(to), hookY, fish,
                                    times[layer - 1], times[layer], false);
                    if (!clear) continue;
                    next[to] = cost;
                    parents[layer][to] = from;
                }
            }
            boolean reachable = false;
            for (double cost : next) if (Double.isFinite(cost)) { reachable = true; break; }
            // A distant dead end must not suppress an independently verified near-term escape.
            if (!reachable) break;
            costs = next;
            completedLayers = layer;
        }
        if (completedLayers == 0) return unavailable;
        int end = -1;
        for (int x = 0; x < count; x++) {
            if (Double.isFinite(costs[x]) && (end < 0 || costs[x] < costs[end])) end = x;
        }
        if (end < 0) return unavailable;
        Integer[] positions = new Integer[completedLayers + 1];
        for (int layer = completedLayers; layer > 0; layer--) {
            positions[layer] = lanes.get(end);
            end = parents[layer][end];
        }
        positions[0] = hookX;
        var routeTimes = new ArrayList<Double>();
        for (int layer = 0; layer <= completedLayers; layer++) routeTimes.add(times[layer]);
        return new Route(positions[1], true, List.of(positions), List.copyOf(routeTimes));
    }

    private static boolean valid(Fish f) {
        return Double.isFinite(f.x()) && Double.isFinite(f.y()) && Double.isFinite(f.width())
                && Double.isFinite(f.height()) && Double.isFinite(f.vx())
                && Double.isFinite(f.relativeVy()) && f.width() > 0 && f.height() > 0
                && f.width() <= 720 && Math.abs(f.vx()) <= 20;
    }

    /** Verify the entire range of prompt-to-delayed drags, then hold through a response reserve.
     * The reserve is provisional; encoded capture age is not measured by input duration. */
    static boolean clearFirstAction(double from, double to, int hookY, List<Fish> fish,
                                    double inputMs, double windowMs) {
        if (!Double.isFinite(inputMs) || inputMs < 0 || inputMs > 600 || !Double.isFinite(windowMs)) return false;
        double delay = Math.max(Math.min(100, inputMs), inputMs - STEP_MS);
        if (windowMs < delay + STEP_MS) return false;
        if (delay == 0) return clearTransition(from, to, hookY, fish, 0, STEP_MS, true)
                && (windowMs == STEP_MS || clearTransition(to, to, hookY, fish, STEP_MS, windowMs, false));
        double[] boundaries = java.util.stream.DoubleStream.of(0, delay, STEP_MS, delay + STEP_MS, windowMs)
                .distinct().sorted().toArray();
        for (Fish obstacle : fish) {
            if (!valid(obstacle)) return false;
            double paddingX = obstacle.width() / 2 + HOOK_HALF_WIDTH + CONTROL_CLEARANCE;
            double paddingY = obstacle.height() / 2 + HOOK_HALF_HEIGHT;
            double cursor = 0;
            int pieces = 0;
            while (cursor < windowMs) {
                if (++pieces > 64) return false;
                double finish = Math.min(windowMs, nextBounce(obstacle, cursor));
                for (double boundary : boundaries) if (boundary > cursor) { finish = Math.min(finish, boundary); break; }
                if (!(finish > cursor)) return false;
                double length = finish - cursor;
                double objectX = reflectedX(obstacle.x(), obstacle.vx(), obstacle.width() / 2, cursor);
                double objectEnd = reflectedX(obstacle.x(), obstacle.vx(), obstacle.width() / 2, finish);
                double early = dragPosition(from, to, cursor, 0), late = dragPosition(from, to, cursor, delay);
                double earlyEnd = dragPosition(from, to, finish, 0), lateEnd = dragPosition(from, to, finish, delay);
                double lower = Math.min(early, late) - objectX, upper = Math.max(early, late) - objectX;
                double lowerEnd = Math.min(earlyEnd, lateEnd) - objectEnd;
                double upperEnd = Math.max(earlyEnd, lateEnd) - objectEnd;
                // Every intermediate start time lies between the two monotone drag positions.
                double[] left = below(lower, (lowerEnd - lower) / length, paddingX, length);
                double[] right = below(-upper, -(upperEnd - upper) / length, paddingX, length);
                double[] vertical = slab(hookY - obstacle.y() - obstacle.relativeVy() * cursor,
                        -obstacle.relativeVy(), paddingY, length);
                if (left != null && right != null && vertical != null
                        && Math.max(Math.max(left[0], right[0]), vertical[0])
                        <= Math.min(Math.min(left[1], right[1]), vertical[1])) return false;
                cursor = finish;
            }
        }
        return true;
    }

    private static double dragPosition(double from, double to, double time, double start) {
        return from + (to - from) * Math.max(0, Math.min(1, (time - start) / STEP_MS));
    }

    private static double[] below(double position, double velocity, double limit, double duration) {
        if (Math.abs(velocity) < 1e-9) return position <= limit ? new double[]{0, duration} : null;
        double crossing = (limit - position) / velocity;
        double enter = velocity < 0 ? Math.max(0, crossing) : 0;
        double exit = velocity > 0 ? Math.min(duration, crossing) : duration;
        return enter <= exit ? new double[]{enter, exit} : null;
    }

    /** A safe destination is insufficient when the drag itself crosses another object. */
    static boolean clearSweep(double from, double to, int hookY, List<Fish> fish, double latencyMs) {
        return clearTransition(from, to, hookY, fish, 0, Math.max(80, latencyMs), true);
    }

    /** Exact relative-motion slab intersection, split at each horizontal wall bounce.
     * No discrete collision sampling: a thin object can cross between video frames. */
    static boolean clearTransition(double from, double to, int hookY, List<Fish> fish,
                                   double start, double end, boolean allowInitialEscape) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start) return false;
        double duration = end - start, hookVx = (to - from) / duration;
        for (Fish obstacle : fish) {
            if (!valid(obstacle)) return false;
            double paddingX = obstacle.width() / 2 + HOOK_HALF_WIDTH + CONTROL_CLEARANCE;
            double paddingY = obstacle.height() / 2 + HOOK_HALF_HEIGHT;
            double initialX = from - reflectedX(obstacle.x(), obstacle.vx(), obstacle.width() / 2, start);
            boolean escape = allowInitialEscape && start == 0 && Math.abs(initialX) <= paddingX
                    && Math.abs(obstacle.y() + obstacle.relativeVy() * start - hookY) <= paddingY;
            double cursor = start;
            int pieces = 0;
            while (cursor < end) {
                if (++pieces > 64) return false;
                double finish = Math.min(end, nextBounce(obstacle, cursor));
                if (!(finish > cursor)) return false;
                double length = finish - cursor;
                double objectX = reflectedX(obstacle.x(), obstacle.vx(), obstacle.width() / 2, cursor);
                double objectEnd = reflectedX(obstacle.x(), obstacle.vx(), obstacle.width() / 2, finish);
                double rx = from + hookVx * (cursor - start) - objectX;
                double rvx = hookVx - (objectEnd - objectX) / length;
                double ry = hookY - obstacle.y() - obstacle.relativeVy() * cursor;
                double[] horizontal = slab(rx, rvx, paddingX, length);
                double[] vertical = slab(ry, -obstacle.relativeVy(), paddingY, length);
                if (horizontal != null && vertical != null
                        && Math.max(horizontal[0], vertical[0]) <= Math.min(horizontal[1], vertical[1])) {
                    // Only outward escape from an already overlapping body is permitted.
                    if (!escape || rx * rvx <= 0 || horizontal[0] > 0 || vertical[0] > 0) return false;
                }
                double finalRx = rx + rvx * length;
                double finalRy = ry - obstacle.relativeVy() * length;
                if (Math.abs(finalRx) > paddingX || Math.abs(finalRy) > paddingY) escape = false;
                cursor = finish;
            }
            if (escape) return false; // Outward motion must actually leave the initial overlap.
        }
        return true;
    }

    private static double[] slab(double position, double velocity, double radius, double duration) {
        if (Math.abs(velocity) < 1e-9) return Math.abs(position) <= radius
                ? new double[]{0, duration} : null;
        double a = (-radius - position) / velocity, b = (radius - position) / velocity;
        double enter = Math.max(0, Math.min(a, b)), exit = Math.min(duration, Math.max(a, b));
        return enter <= exit ? new double[]{enter, exit} : null;
    }

    private static double nextBounce(Fish f, double time) {
        double span = 720 - f.width();
        if (span <= 0 || Math.abs(f.vx()) < 1e-9) return Double.POSITIVE_INFINITY;
        double unfolded = f.x() - f.width() / 2 + f.vx() * time;
        double boundary = f.vx() > 0 ? (Math.floor(unfolded / span + 1e-9) + 1) * span
                : (Math.ceil(unfolded / span - 1e-9) - 1) * span;
        return time + (boundary - unfolded) / f.vx();
    }

    static double reflectedX(double x, double vx, double halfWidth, double milliseconds) {
        double span = 720 - 2 * halfWidth;
        if (span <= 0) return 360;
        double wrapped = (x - halfWidth + vx * milliseconds) % (2 * span);
        if (wrapped < 0) wrapped += 2 * span;
        return halfWidth + (wrapped <= span ? wrapped : 2 * span - wrapped);
    }
}
