package dev.frostguard.engine.error;

// Thrown when the quit-game confirmation dialog is still confirmed present after every dismiss
// attempt QuitDialogGuard is willing to make. Mirrors HomeNotFoundException: we genuinely don't
// know what's on screen anymore, so the dispatcher re-runs INITIALIZE to recover to a known state
// instead of letting the caller proceed into what may still be a still-open quit dialog.
public class QuitDialogStuckException extends RuntimeException {

    private static final long serialVersionUID = 4471029385617L;

    private final int attemptsMade;

    public QuitDialogStuckException(String msg, int attemptsMade) {
        super(msg);
        this.attemptsMade = attemptsMade;
    }

    public static QuitDialogStuckException afterAttempts(String device, int n) {
        return new QuitDialogStuckException(
                "Quit-game dialog on " + device + " still visible after " + n + " dismiss attempt(s)", n);
    }

    public int getAttemptsMade() { return attemptsMade; }

    @Override public String toString() {
        return getClass().getSimpleName() + "{attempts=" + attemptsMade + ", msg=" + getMessage() + "}";
    }
}
