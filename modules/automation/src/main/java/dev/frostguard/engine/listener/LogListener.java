package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.LogMessageData;

// Sink for structured log entries produced by engine services and
// the scheduling kernel. Bridges engine events into the UI console.
public interface LogListener {

    // Invoked each time a log message is emitted.
    void onLogEntryEmitted(LogMessageData entry);

    // Controls whether entries at the given severity level reach this listener.
    // Receives the ordinal of the severity enum. Returns true to accept.
    default boolean acceptsSeverity(int severityOrdinal) {
        return true;
    }

    // Called when the log buffer is flushed (e.g. on session end).
    default void onLogBufferFlushed() {}
}
