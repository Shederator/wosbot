package dev.frostguard.engine.emulator;

/** The observed outcome of a request to power off one emulator instance. */
public record EmulatorStopResult(Status status, Method method, String evidence) {

    public enum Status {
        CONFIRMED_STOPPED,
        UNCONFIRMED,
        INTERRUPTED
    }

    public enum Method {
        VENDOR,
        ADB,
        NONE
    }

    public boolean confirmed() {
        return status == Status.CONFIRMED_STOPPED;
    }
}
