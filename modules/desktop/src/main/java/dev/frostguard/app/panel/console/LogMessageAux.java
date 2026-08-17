package dev.frostguard.app.panel.console;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Objects;

public final class LogMessageAux {

    private final StringProperty timeStamp = new SimpleStringProperty();
    private final StringProperty severity = new SimpleStringProperty();
    private final StringProperty message = new SimpleStringProperty();
    private final StringProperty task = new SimpleStringProperty();
    private final StringProperty profile = new SimpleStringProperty();

    public LogMessageAux(String timeStamp, String severity, String message, String task, String profile) {
        this.timeStamp.set(blankSafe(timeStamp));
        this.severity.set(blankSafe(severity));
        this.message.set(blankSafe(message));
        this.task.set(blankSafe(task));
        this.profile.set(blankSafe(profile));
    }

    public StringProperty timeStampProperty() {
        return timeStamp;
    }

    public StringProperty messageProperty() {
        return message;
    }

    public StringProperty severityProperty() {
        return severity;
    }

    public StringProperty taskProperty() {
        return task;
    }

    public StringProperty profileProperty() {
        return profile;
    }

    public String getTimeStamp() {
        return timeStamp.get();
    }

    public String getSeverity() {
        return severity.get();
    }

    public String getMessage() {
        return message.get();
    }

    public String getTask() {
        return task.get();
    }

    public String getProfile() {
        return profile.get();
    }

    private static String blankSafe(String value) {
        return Objects.toString(value, "");
    }
}
