package dev.frostguard.app.panel.profile;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

public interface IProfileChangeObserver {

	void notifyProfileChange(ConfigurationKeyEnum key, Object value);

	default void notifyProfileChange(ConfigurationKeyEnum key, boolean value) {
		notifyProfileChange(key, Boolean.valueOf(value));
	}

	default void notifyProfileChange(ConfigurationKeyEnum key, int value) {
		notifyProfileChange(key, Integer.valueOf(value));
	}

	default void notifyProfileChange(ConfigurationKeyEnum key, String value) {
		notifyProfileChange(key, (Object) value);
	}

	static IProfileChangeObserver noOp() {
		return (key, value) -> {
		};
	}

}
