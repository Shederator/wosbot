package dev.frostguard.engine.error;

// Non-recoverable halt signal — the dispatcher must permanently stop processing a profile slot.
public class StopExecutionException extends RuntimeException {

    private static final long serialVersionUID = 7192045831024L;

    public enum Reason { GAME_MISSING, USER_CANCELLED, FATAL_ERROR }

    private final Reason reason;

    public StopExecutionException(String msg) { this(msg, Reason.FATAL_ERROR); }

    public StopExecutionException(String msg, Reason r) {
        super(msg);
        this.reason = (r != null) ? r : Reason.FATAL_ERROR;
    }

    public static StopExecutionException gameMissing()   { return new StopExecutionException("Game APK absent from device", Reason.GAME_MISSING); }
    public static StopExecutionException userCancelled() { return new StopExecutionException("Operator requested halt", Reason.USER_CANCELLED); }

    public static StopExecutionException withReason(Reason r, String msg) {
        return new StopExecutionException(msg, r);
    }

    public Reason  getReason()       { return reason; }
    public boolean isCancellation()  { return reason == Reason.USER_CANCELLED; }
    public boolean isFatal()         { return reason == Reason.FATAL_ERROR; }

    @Override public String toString() {
        return reason.name() + ": " + getMessage();
    }
}
