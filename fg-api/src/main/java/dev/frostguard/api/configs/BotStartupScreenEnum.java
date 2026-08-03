package dev.frostguard.api.configs;

public enum BotStartupScreenEnum {

	CONTROL_TASKS_TIMELINE("Control/Tasks/Timeline View"),
	CONTROL_TASKS_TABLE("Control/Tasks/Table View"),
	CONTROL_LOGS("Control/Logs"),
	CONTROL_PROFILES("Control/Profiles"),
	CONFIG_EMULATORS("Config/Emulators"),
	CONFIG_TELEGRAM("Config/Telegram");

	private final String displayName;

	BotStartupScreenEnum(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	@Override
	public String toString() {
		return displayName;
	}

	public static BotStartupScreenEnum parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return CONTROL_LOGS;
		}

		for (BotStartupScreenEnum option : values()) {
			if (option.name().equalsIgnoreCase(raw) || option.displayName.equalsIgnoreCase(raw)) {
				return option;
			}
		}

		return CONTROL_LOGS;
	}
}
