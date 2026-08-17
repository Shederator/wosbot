package dev.frostguard.engine.ranking;

import dev.frostguard.engine.ranking.capture.AllianceRankingCaptureResult;
import dev.frostguard.engine.ranking.capture.GameAnalyticsCollectionType;
import dev.frostguard.engine.ranking.history.GameAnalyticsSnapshot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Publishes progress from manually queued analytics tasks to interested UI panels. */
public final class GameAnalyticsRunRegistry {

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private GameAnalyticsRunRegistry() {
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void publish(Event event) {
        LISTENERS.forEach(listener -> listener.onAnalyticsEvent(event));
    }

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record Event(Long profileId, GameAnalyticsCollectionType type, State state,
                        String message, AllianceRankingCaptureResult result,
                        GameAnalyticsSnapshot snapshot) {
    }

    @FunctionalInterface
    public interface Listener {
        void onAnalyticsEvent(Event event);
    }
}
