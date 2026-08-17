package dev.frostguard.app.shared;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public final class ValidatedTextFieldBinding<T> {

    private final TextField field;
    private final SettingValidator<T> validator;
    private final Function<T, String> formatter;
    private final Consumer<T> commitHandler;
    private final FieldValidationPresenter presenter;
    private final UnaryOperator<String> draftNormalizer;
    private boolean errorVisible;

    public ValidatedTextFieldBinding(
            TextField field,
            Label messageLabel,
            SettingValidator<T> validator,
            Function<T, String> formatter,
            Consumer<T> commitHandler) {
        this(field, messageLabel, validator, formatter, commitHandler, UnaryOperator.identity());
    }

    public ValidatedTextFieldBinding(
            TextField field,
            Label messageLabel,
            SettingValidator<T> validator,
            Function<T, String> formatter,
            Consumer<T> commitHandler,
            UnaryOperator<String> draftNormalizer) {
        this.field = Objects.requireNonNull(field);
        this.validator = Objects.requireNonNull(validator);
        this.formatter = Objects.requireNonNull(formatter);
        this.commitHandler = Objects.requireNonNull(commitHandler);
        this.draftNormalizer = Objects.requireNonNull(draftNormalizer);
        presenter = new FieldValidationPresenter(field, messageLabel);
        field.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && !field.isDisabled()) {
                commit();
            }
        });
        field.setOnAction(event -> commit());
        field.textProperty().addListener((obs, oldText, newText) -> {
            if (errorVisible) {
                present(validator.validate(newText));
            }
        });
    }

    public boolean commit() {
        String normalized = draftNormalizer.apply(field.getText());
        field.setText(normalized);
        ValidationResult<T> result = validator.validate(normalized);
        present(result);
        if (!result.isValid()) {
            return false;
        }
        field.setText(formatter.apply(result.value()));
        commitHandler.accept(result.value());
        return true;
    }

    public T loadPersisted(String persisted, String fallback, Consumer<String> malformedHandler) {
        ValidationResult<T> result = validator.validate(persisted);
        if (!result.isValid()) {
            malformedHandler.accept(result.message());
            result = validator.validate(fallback);
        }
        if (!result.isValid()) {
            throw new IllegalArgumentException("Invalid setting fallback: " + result.message());
        }
        field.setText(formatter.apply(result.value()));
        errorVisible = false;
        presenter.clear();
        return result.value();
    }

    private void present(ValidationResult<T> result) {
        errorVisible = !result.isValid();
        if (result.isValid()) {
            presenter.clear();
        } else {
            presenter.showError(result.message());
        }
    }
}
