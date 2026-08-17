package dev.frostguard.engine.error;

// Thrown when the home/city screen cannot be located after repeated visual probes.
// The dispatcher catches this to decide whether to retry, skip, or park the slot.
public class HomeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 8837201947562L;

    private final int probeCount;

    public HomeNotFoundException(String msg) {
        super(msg);
        this.probeCount = -1;
    }

    public HomeNotFoundException(String msg, int probeCount) {
        super(msg);
        this.probeCount = Math.max(probeCount, -1);
    }

    public static HomeNotFoundException afterAttempts(int n) {
        return new HomeNotFoundException("Could not detect city view (" + n + " probes ran)", n);
    }

    public static HomeNotFoundException processInactive(String label) {
        return new HomeNotFoundException("App not running [" + label + "]", 0);
    }

    public int getAttemptsMade() { return probeCount; }

    @Override public String toString() {
        return probeCount >= 0
                ? getClass().getSimpleName() + "{probes=" + probeCount + ", msg=" + getMessage() + "}"
                : getClass().getSimpleName() + "{msg=" + getMessage() + "}";
    }
}
