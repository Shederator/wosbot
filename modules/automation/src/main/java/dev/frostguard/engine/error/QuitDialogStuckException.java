package dev.frostguard.engine.error;

// Thrown when the quit-game confirmation is still visible after every dismiss attempt. Like
// HomeNotFoundException, the screen state is unknown, so the dispatcher re-runs Initialize.
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
