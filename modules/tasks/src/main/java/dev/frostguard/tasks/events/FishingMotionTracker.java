package dev.frostguard.tasks.events;

import java.util.ArrayList;
import java.util.List;
import dev.frostguard.api.domain.AreaData;

/** Short-lived tracks preserve imminent threats during animation or a missed foreground detection. */
final class FishingMotionTracker {
    private static final int MAX_TRACKS = 256;
    private static final class Track {
        double x, y, w, h, vx, vy = -0.69;
        long observed;
        int scans;
    }

    private final List<Track> tracks = new ArrayList<>();
    private Double relativeVerticalVelocity;

    List<FishingAvoidancePlanner.Fish> update(List<AreaData> areas, long now, double hookVy) {
        if (areas.size() > MAX_TRACKS) throw new IllegalStateException("Fishing foreground detection overloaded");
        tracks.removeIf(track -> now - track.observed > 450);
        List<Track> matched = new ArrayList<>();
        for (AreaData area : areas) {
            double x = (area.topLeft().getX() + area.bottomRight().getX()) / 2.0;
            double y = (area.topLeft().getY() + area.bottomRight().getY()) / 2.0;
            double width = area.bottomRight().getX() - area.topLeft().getX();
            double height = area.bottomRight().getY() - area.topLeft().getY();
            Track best = null;
            double bestDistance = 75;
            for (Track track : tracks) {
                if (matched.contains(track)) continue;
                long dt = now - track.observed;
                if (dt <= 0 || dt > 450) continue;
                double d = Math.hypot(x - track.x - track.vx * dt, y - track.y - track.vy * dt);
                if (d < bestDistance && width > track.w * 0.4 && width < track.w * 2.5) {
                    best = track;
                    bestDistance = d;
                }
            }
            if (best == null) {
                if (tracks.size() >= MAX_TRACKS) throw new IllegalStateException("Fishing object tracking overloaded");
                best = new Track();
                tracks.add(best);
            } else {
                long dt = now - best.observed;
                best.vx = 0.5 * best.vx + 0.5 * (x - best.x) / dt;
                best.vy = 0.5 * best.vy + 0.5 * (y - best.y) / dt;
            }
            best.x = x;
            best.y = y;
            best.w = width;
            best.h = height;
            best.observed = now;
            best.scans++;
            matched.add(best);
        }
        tracks.removeIf(track -> now - track.observed > 450);
        var measured = matched.stream().filter(track -> track.scans >= 3)
                .mapToDouble(track -> track.vy - hookVy).filter(Double::isFinite).sorted().toArray();
        relativeVerticalVelocity = measured.length >= 3 ? measured[measured.length / 2] : null;
        List<FishingAvoidancePlanner.Fish> result = new ArrayList<>();
        for (Track track : tracks) {
            long dt = now - track.observed;
            double uncertainty = track.scans < 2 ? 25 : 8;
            result.add(new FishingAvoidancePlanner.Fish(
                    FishingAvoidancePlanner.reflectedX(track.x, track.vx, track.w / 2, dt),
                    track.y + track.vy * dt, track.w + uncertainty * 2, track.h,
                    track.vx, track.vy - hookVy));
        }
        return result;
    }

    Double relativeVerticalVelocity() { return relativeVerticalVelocity; }

    List<FishingHarvestPlanner.Opportunity> catchOpportunities(long now, double hookVy) {
        return tracks.stream().filter(track -> track.observed == now && track.scans >= 3)
                .map(track -> new FishingHarvestPlanner.Opportunity(track.x, track.y, track.w,
                        track.h, track.vx, track.vy - hookVy)).toList();
    }
}
