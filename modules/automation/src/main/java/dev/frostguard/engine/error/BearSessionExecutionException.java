package dev.frostguard.engine.error;

/** Signals that an active Bear session must be retried without releasing its scheduler lease. */
public final class BearSessionExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum FailureKind {
        CAPTURE_TRANSIENT,
        DEVICE_OFFLINE,
        APP_NOT_FOREGROUND,
        RECONNECT_SCREEN,
        VISUAL_UNKNOWN,
        PERSISTENCE,
        FATAL_CONFIGURATION
    }

    public enum RecoveryDirective {
        DEGRADED_WAIT,
        REBIND_DEVICE,
        RESTART_APP,
        OPERATOR_ACTION
    }

    private final FailureKind failureKind;
    private final RecoveryDirective recoveryDirective;
    private final String device;
    private final String operation;

    public BearSessionExecutionException(
            FailureKind failureKind,
            RecoveryDirective recoveryDirective,
            String device,
            String operation,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
        this.recoveryDirective = recoveryDirective;
        this.device = device;
        this.operation = operation;
    }

    public FailureKind failureKind() {
        return failureKind;
    }

    public RecoveryDirective recoveryDirective() {
        return recoveryDirective;
    }

    public String device() {
        return device;
    }

    public String operation() {
        return operation;
    }
}
