package dev.frostguard.engine.error;

import dev.frostguard.engine.helper.CharacterSwitchResult;

/**
 * Signals that initialization could not establish the configured character.
 */
public class CharacterSwitchFailedException extends RuntimeException {

    private final CharacterSwitchResult result;

    public CharacterSwitchFailedException(CharacterSwitchResult result) {
        super("Character switch failed: " + result);
        this.result = result;
    }

    public CharacterSwitchResult getResult() {
        return result;
    }
}
