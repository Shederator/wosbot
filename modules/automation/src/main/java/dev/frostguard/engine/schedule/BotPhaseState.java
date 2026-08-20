package dev.frostguard.engine.schedule;

/**
 * A short-lived phase label describing what the bot is doing right now.
 *
 * <p>the operator, 2026-08-08: the status readout jumped from "Starting up" to "Active" within a couple
 * of seconds, which told him nothing about the phase he actually cares about — the point where
 * the bot is reading every countdown off the screen before it acts. This carries that phase up
 * to the UI so the label reflects the work rather than just the run state.</p>
 *
 * <p>Deliberately a plain static holder rather than a listener chain: the launcher already polls
 * once a second to refresh uptime, so it can read this on the same tick, and a phase that fails
 * to clear degrades to the ordinary "Active" label rather than wedging anything.</p>
 */
public final class BotPhaseState {

    private static volatile String phase;

    private BotPhaseState() {
    }

    /** Sets the phase shown in place of the plain running state. */
    public static void set(String label) {
        phase = (label == null || label.isBlank()) ? null : label;
    }

    /** Clears the phase, returning the readout to its normal run state. */
    public static void clear() {
        phase = null;
    }

    /** @return the current phase label, or {@code null} when nothing special is running */
    public static String current() {
        return phase;
    }
}
