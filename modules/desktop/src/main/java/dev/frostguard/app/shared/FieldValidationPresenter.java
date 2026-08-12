package dev.frostguard.app.shared;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

public final class FieldValidationPresenter {

    private static final String ERROR_CLASS = "setting-field-error";

    private final TextField field;
    private final Label messageLabel;
    private final Tooltip originalTooltip;
    private final String originalAccessibleHelp;

    public FieldValidationPresenter(TextField field, Label messageLabel) {
        this.field = field;
        this.messageLabel = messageLabel;
        originalTooltip = field.getTooltip();
        originalAccessibleHelp = field.getAccessibleHelp();
        messageLabel.getStyleClass().add("setting-validation-message");
        clear();
    }

    public void showError(String message) {
        if (!field.getStyleClass().contains(ERROR_CLASS)) {
            field.getStyleClass().add(ERROR_CLASS);
        }
        field.setTooltip(new Tooltip(message));
        field.setAccessibleHelp(message);
        messageLabel.setText(message);
        messageLabel.setManaged(true);
        messageLabel.setVisible(true);
    }

    public void clear() {
        field.getStyleClass().remove(ERROR_CLASS);
        field.setTooltip(originalTooltip);
        field.setAccessibleHelp(originalAccessibleHelp);
        messageLabel.setText("");
        messageLabel.setManaged(false);
        messageLabel.setVisible(false);
    }
}
