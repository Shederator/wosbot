package dev.frostguard.app.panel.emulator;

import dev.frostguard.engine.emulator.EmulatorType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

public final class EmulatorAux {

    private enum TextField {
        DISPLAY_NAME,
        EXECUTABLE
    }

    private final EmulatorType emulatorType;
    private final EnumMap<TextField, StringProperty> text = new EnumMap<>(TextField.class);
    private final BooleanProperty launchCandidate = new SimpleBooleanProperty();

    public EmulatorAux(EmulatorType emulatorType, String path) {
        this.emulatorType = Objects.requireNonNull(emulatorType, "emulatorType");
        text.put(TextField.DISPLAY_NAME, new SimpleStringProperty(emulatorType.getDisplayName()));
        text.put(TextField.EXECUTABLE, new SimpleStringProperty(clean(path)));
    }

    public EmulatorType getEmulatorType() { return emulatorType; }

    public String getName() { return nameProperty().get(); }

    public StringProperty nameProperty() { return property(TextField.DISPLAY_NAME); }

    public String getPath() { return pathProperty().get(); }

    public void setPath(String path) { pathProperty().set(clean(path)); }

    public StringProperty pathProperty() { return property(TextField.EXECUTABLE); }

    public boolean isActive() { return launchCandidate.get(); }

    public void setActive(boolean active) { launchCandidate.set(active); }

    public BooleanProperty activeProperty() { return launchCandidate; }

    public boolean hasExecutablePath() { return !getPath().isBlank(); }

    public Optional<Path> pathAsFileSystemPath() { return hasExecutablePath() ? Optional.of(Path.of(getPath())) : Optional.empty(); }

    public StringProperty summaryProperty() {
        String pathText = hasExecutablePath() ? getPath() : "No path configured";
        return new ReadOnlyStringWrapper("%s - %s".formatted(getName(), pathText));
    }

    public void activateOnlyThis(Iterable<EmulatorAux> emulators) {
        if (emulators != null) {
            emulators.forEach(candidate -> candidate.setActive(candidate == this));
        }
    }

    private StringProperty property(TextField field) { return text.get(field); }

    private static String clean(String path) { return Objects.toString(path, "").trim(); }
}
