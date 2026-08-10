package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.BotStateData;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Callback contract for engine lifecycle phase changes.
 * Observers receive snapshots when the automation kernel
 * transitions between running states, starts up, or shuts down.
 */
public interface BotStateListener {

    /** Delivers a state snapshot following a run-state transition. */
    void onEngineStateTransition(BotStateData snapshot);

    /** Invoked before the engine begins its bootstrap sequence. */
    default void onEngineStarting() {}

    /** Invoked after the engine has released all emulator slots. */
    default void onEngineStopped() {}

    /**
     * Wraps a plain consumer into a listener that only reacts
     * to state transitions, ignoring start/stop hooks.
     */
    static BotStateListener fromConsumer(Consumer<BotStateData> handler) {
        Objects.requireNonNull(handler, "handler");
        return handler::accept;
    }

    /**
     * Multiplexer that fans out each event to a list of delegates.
     * Useful for registering multiple listeners through a single slot.
     */
    final class Composite implements BotStateListener {
        private final List<BotStateListener> delegates = new CopyOnWriteArrayList<>();

        public void add(BotStateListener listener) {
            if (listener != null) delegates.add(listener);
        }

        public void remove(BotStateListener listener) {
            delegates.remove(listener);
        }

        @Override
        public void onEngineStateTransition(BotStateData snapshot) {
            delegates.forEach(d -> d.onEngineStateTransition(snapshot));
        }

        @Override
        public void onEngineStarting() {
            delegates.forEach(BotStateListener::onEngineStarting);
        }

        @Override
        public void onEngineStopped() {
            delegates.forEach(BotStateListener::onEngineStopped);
        }
    }
}
