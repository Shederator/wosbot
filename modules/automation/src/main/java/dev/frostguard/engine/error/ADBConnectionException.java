package dev.frostguard.engine.error;

// Raised when ADB communication with an emulator device breaks or cannot be established.
public class ADBConnectionException extends RuntimeException {

    private static final long serialVersionUID = 3304719825601L;

    private final String serial;

    public ADBConnectionException(String msg)                { super(msg); this.serial = null; }
    public ADBConnectionException(String msg, Throwable t)   { super(msg, t); this.serial = null; }
    public ADBConnectionException(Throwable t)               { super(t == null ? "ADB link broken" : t.getMessage(), t); this.serial = null; }

    private ADBConnectionException(String serial, String msg, Throwable t) {
        super(msg, t);
        this.serial = serial;
    }

    public static ADBConnectionException forDevice(String serial, String detail) {
        return new ADBConnectionException(serial, "[" + serial + "] " + detail, null);
    }

    public static ADBConnectionException forDevice(String serial, String detail, Throwable t) {
        return new ADBConnectionException(serial, "[" + serial + "] " + detail, t);
    }

    public String getDeviceIdentifier()  { return serial; }
    public boolean isDeviceSpecific()    { return serial != null && !serial.isBlank(); }

    @Override public String toString() {
        return serial != null
                ? getClass().getSimpleName() + "[" + serial + "]: " + getMessage()
                : getClass().getSimpleName() + ": " + getMessage();
    }
}
