package dev.frostguard.engine.helper;

/**
 * Outcome of an attempted in-game character switch.
 */
public enum CharacterSwitchResult {
    SUCCESS,
    MISSING_CHARACTER_NAME,
    SETTINGS_UNAVAILABLE,
    SWITCH_MENU_UNAVAILABLE,
    TARGET_NOT_FOUND,
    CONFIRMATION_FAILED
}
