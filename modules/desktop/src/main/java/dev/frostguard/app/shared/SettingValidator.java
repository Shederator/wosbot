package dev.frostguard.app.shared;

@FunctionalInterface
public interface SettingValidator<T> {

    ValidationResult<T> validate(String input);
}
