package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.QueueStateData;

import java.util.Objects;
import java.util.function.Consumer;

// Observes scheduler queue mutations: full-state broadcasts,
// membership changes, and drain notifications before shutdown.
public interface QueueStateListener {

    // Broad category of queue lifecycle events.
    enum EventKind { SNAPSHOT, DRAINING, MEMBERSHIP }

    // Delivers the latest queue snapshot after a scheduling cycle.
    void onQueueStateChanged(QueueStateData snapshot);

    // Signals that the scheduler is about to drain all queues.
    default void onQueueDraining() {}

    // A profile was enqueued or removed from the active set.
    default void onQueueMembershipChanged(QueueStateData snapshot, int delta) {
        onQueueStateChanged(snapshot);
    }

    // Creates a listener that only reacts to snapshot updates.
    static QueueStateListener snapshotOnly(Consumer<QueueStateData> handler) {
        Objects.requireNonNull(handler, "handler");
        return handler::accept;
    }

    // Wraps this listener to silently discard null snapshots.
    default QueueStateListener nonNull() {
        QueueStateListener self = this;
        return new QueueStateListener() {
            @Override
            public void onQueueStateChanged(QueueStateData snapshot) {
                if (snapshot == null) return;
                self.onQueueStateChanged(snapshot);
            }

            @Override
            public void onQueueDraining() {
                self.onQueueDraining();
            }

            @Override
            public void onQueueMembershipChanged(QueueStateData snapshot, int delta) {
                if (snapshot == null) return;
                self.onQueueMembershipChanged(snapshot, delta);
            }
        };
    }
}
