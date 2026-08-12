package dev.frostguard.app.shared;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public final class UtcDateTimeEditor extends VBox {

    private static final String INCOMPLETE_MESSAGE = "Select a complete UTC date and time.";
    private static final String INVALID_MESSAGE = "Enter an hour from 0–23 and minute from 0–59.";

    private final DatePicker datePicker = new DatePicker();
    private final Spinner<Integer> hourSpinner = createSpinner(0, 23);
    private final Spinner<Integer> minuteSpinner = createSpinner(0, 59);
    private final Button applyButton = new Button("Apply");
    private final Button clearButton = new Button("Clear");
    private final Label nextActivationPreview = new Label("Next activation: —");
    private final Label localPreview = new Label("Local time: —");
    private final Label validationMessage = new Label();
    private final FieldValidationPresenter datePresenter = new FieldValidationPresenter(datePicker.getEditor());
    private final FieldValidationPresenter hourPresenter = new FieldValidationPresenter(hourSpinner.getEditor());
    private final FieldValidationPresenter minutePresenter = new FieldValidationPresenter(minuteSpinner.getEditor());
    private final BooleanProperty timerEnabled = new SimpleBooleanProperty(false);
    private final ZoneId localZone;
    private final Clock clock;

    private Consumer<LocalDateTime> commitHandler = ignored -> { };
    private Runnable clearHandler = () -> { };
    private LocalDateTime committedValue;

    public UtcDateTimeEditor() {
        this(ZoneId.systemDefault(), Clock.systemUTC());
    }

    UtcDateTimeEditor(ZoneId localZone) {
        this(localZone, Clock.systemUTC());
    }

    UtcDateTimeEditor(ZoneId localZone, Clock clock) {
        this.localZone = Objects.requireNonNull(localZone);
        this.clock = Objects.requireNonNull(clock);
        configureLayout();
        configureListeners();
        refreshState();
    }

    private void configureLayout() {
        setSpacing(4);
        setFillWidth(true);

        datePicker.setEditable(true);
        datePicker.setPromptText("dd-MM-yyyy, e.g. 28-07-2026");
        datePicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return UtcDateTimeValue.formatDate(date);
            }

            @Override
            public LocalDate fromString(String text) {
                return UtcDateTimeValue.parseDate(text);
            }
        });
        datePicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(datePicker, Priority.ALWAYS);

        Label separator = new Label(":");
        applyButton.setMinWidth(58);
        applyButton.setOnAction(event -> commitSelection());
        clearButton.setMinWidth(58);
        clearButton.setOnAction(event -> clearSelection());

        HBox inputRow = new HBox(5, datePicker, hourSpinner, separator, minuteSpinner, applyButton, clearButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        nextActivationPreview.getStyleClass().add("task-card-description");
        nextActivationPreview.setWrapText(true);
        localPreview.getStyleClass().add("task-card-description");
        localPreview.setWrapText(true);
        validationMessage.getStyleClass().add("setting-validation-message");
        validationMessage.setWrapText(true);
        validationMessage.setManaged(false);
        validationMessage.setVisible(false);

        getChildren().setAll(inputRow, nextActivationPreview, localPreview, validationMessage);
    }

    private void configureListeners() {
        datePicker.valueProperty().addListener((obs, oldValue, newValue) -> {
            datePicker.getEditor().setText(UtcDateTimeValue.formatDate(newValue));
            refreshState();
        });
        datePicker.getEditor().textProperty().addListener((obs, oldValue, newValue) -> refreshState());
        hourSpinner.valueProperty().addListener((obs, oldValue, newValue) -> refreshState());
        minuteSpinner.valueProperty().addListener((obs, oldValue, newValue) -> refreshState());
        hourSpinner.getEditor().textProperty().addListener((obs, oldValue, newValue) -> refreshState());
        minuteSpinner.getEditor().textProperty().addListener((obs, oldValue, newValue) -> refreshState());
        timerEnabled.addListener((obs, oldValue, newValue) -> refreshState());
    }

    private static Spinner<Integer> createSpinner(int minimum, int maximum) {
        Spinner<Integer> spinner = new Spinner<>();
        spinner.setEditable(true);
        spinner.setMinWidth(82);
        spinner.setPrefWidth(82);
        spinner.setMaxWidth(82);
        SpinnerValueFactory.IntegerSpinnerValueFactory valueFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(minimum, maximum, minimum);
        valueFactory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : String.format("%02d", value);
            }

            @Override
            public Integer fromString(String text) {
                return Integer.valueOf(text.trim());
            }
        });
        spinner.setValueFactory(valueFactory);
        spinner.getEditor().setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,2}") ? change : null));
        return spinner;
    }

    public void setDateTime(LocalDateTime value) {
        committedValue = value;
        if (value == null) {
            datePicker.setValue(null);
            datePicker.getEditor().setText("");
            setSpinnerValue(hourSpinner, 0);
            setSpinnerValue(minuteSpinner, 0);
        } else {
            datePicker.setValue(value.toLocalDate());
            datePicker.getEditor().setText(UtcDateTimeValue.formatDate(value.toLocalDate()));
            setSpinnerValue(hourSpinner, value.getHour());
            setSpinnerValue(minuteSpinner, value.getMinute());
        }
        refreshState();
    }

    public LocalDateTime getDateTime() {
        UtcDateTimeValue.Selection selection = currentSelection();
        return selection.isValid() ? selection.value() : null;
    }

    public boolean hasCommittedDateTime() {
        return committedValue != null;
    }

    public void setOnCommit(Consumer<LocalDateTime> handler) {
        commitHandler = Objects.requireNonNull(handler);
    }

    public void setOnClear(Runnable handler) {
        clearHandler = Objects.requireNonNull(handler);
    }

    public BooleanProperty timerEnabledProperty() {
        return timerEnabled;
    }

    public String getLocalPreviewText() {
        return localPreview.getText();
    }

    public String getNextActivationPreviewText() {
        return nextActivationPreview.getText();
    }

    public String getValidationMessage() {
        return validationMessage.getText();
    }

    DatePicker datePicker() {
        return datePicker;
    }

    Spinner<Integer> hourSpinner() {
        return hourSpinner;
    }

    Spinner<Integer> minuteSpinner() {
        return minuteSpinner;
    }

    void commitSelection() {
        UtcDateTimeValue.Selection selection = currentSelection();
        if (!selection.isValid()) {
            refreshState();
            return;
        }

        committedValue = selection.value();
        setSpinnerValue(hourSpinner, committedValue.getHour());
        setSpinnerValue(minuteSpinner, committedValue.getMinute());
        commitHandler.accept(committedValue);
        refreshState();
    }

    void clearSelection() {
        if (committedValue == null) {
            return;
        }
        committedValue = null;
        datePicker.setValue(null);
        datePicker.getEditor().setText("");
        setSpinnerValue(hourSpinner, 0);
        setSpinnerValue(minuteSpinner, 0);
        clearHandler.run();
        refreshState();
    }

    private void refreshState() {
        UtcDateTimeValue.Selection selection = currentSelection();
        if (selection.isValid()) {
            LocalDateTime nextActivation = UtcDateTimeValue.nextActivation(selection.value(), clock);
            nextActivationPreview.setText(UtcDateTimeValue.formatUtcPreview(nextActivation));
            localPreview.setText(UtcDateTimeValue.formatLocalPreview(nextActivation, localZone));
        } else {
            nextActivationPreview.setText("Next activation: —");
            localPreview.setText("Local time: —");
        }

        String error = timerEnabled.get() ? validationText(selection.validation()) : "";
        validationMessage.setText(error);
        validationMessage.setVisible(!error.isEmpty());
        validationMessage.setManaged(!error.isEmpty());
        updateInputErrorState(error);
        applyButton.setDisable(!selection.isValid() || selection.value().equals(committedValue));
        clearButton.setDisable(committedValue == null);
    }

    private UtcDateTimeValue.Selection currentSelection() {
        return UtcDateTimeValue.resolveDateText(
                datePicker.getEditor().getText(),
                hourSpinner.getEditor().getText(),
                minuteSpinner.getEditor().getText());
    }

    private static String validationText(UtcDateTimeValue.Validation validation) {
        return switch (validation) {
            case VALID -> "";
            case INCOMPLETE -> INCOMPLETE_MESSAGE;
            case INVALID -> INVALID_MESSAGE;
        };
    }

    private void updateInputErrorState(String error) {
        if (error.isEmpty()) {
            datePresenter.clear();
            hourPresenter.clear();
            minutePresenter.clear();
        } else {
            datePresenter.showError(error);
            hourPresenter.showError(error);
            minutePresenter.showError(error);
        }
    }

    private static void setSpinnerValue(Spinner<Integer> spinner, int value) {
        spinner.getValueFactory().setValue(value);
        spinner.getEditor().setText(String.format("%02d", value));
    }

    void setDraft(LocalDate date, String hour, String minute) {
        datePicker.setValue(date);
        datePicker.getEditor().setText(UtcDateTimeValue.formatDate(date));
        hourSpinner.getEditor().setText(hour);
        minuteSpinner.getEditor().setText(minute);
        refreshState();
    }

    void setDateTextDraft(String date, String hour, String minute) {
        datePicker.getEditor().setText(date);
        hourSpinner.getEditor().setText(hour);
        minuteSpinner.getEditor().setText(minute);
        refreshState();
    }
}
