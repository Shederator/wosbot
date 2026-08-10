package dev.frostguard.app.bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.emulator.EmulatorType;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.ScheduleService;
import dev.frostguard.engine.service.TelegramBotService;

public class HeadlessApp {

	private static final Logger logger = LoggerFactory.getLogger(HeadlessApp.class);

	public static void start(String[] args) {
		logger.info("Initializing Headless Bot...");

		// 1. Initialize external libraries
		try {
			OpenCvPatternLocator.loadNativeLibrary();
			logger.info("OpenCV native library loaded successfully.");
		} catch (IOException e) {
			logger.error("Failed to load OpenCV: ", e);
		}

		// 2. Initialize Emulator Manager paths
		initializeEmulatorController();

		// 3. Initialize Telegram Bot manually
		initializeTelegramBot();

		boolean autostart = false;
		for (String arg : args) {
			if ("--autostart".equalsIgnoreCase(arg)) {
				autostart = true;
				break;
			}
		}

		if (autostart) {
			logger.info("Autostart flag detected. Starting automation...");
			ScheduleService.obtain().launchEngine();
		} else {
			logger.info("Headless bot ready. Waiting for Telegram commands to start automation...");
		}
	}

	private static void initializeTelegramBot() {
		HashMap<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
		if (cfg == null) {
			return;
		}

		boolean enabled = Boolean.parseBoolean(
				cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_ENABLED_BOOL.name(), "false"));
		String token = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_BOT_TOKEN_STRING.name(), "");
		String chatIdStr = cfg.getOrDefault(ConfigurationKeyEnum.TELEGRAM_ALLOWED_CHAT_ID_STRING.name(), "");

		if (enabled && !token.isBlank()) {
			long chatId = chatIdStr.isBlank() ? 0L : Long.parseLong(chatIdStr);
			TelegramBotService.getInstance().start(token, chatId);
			logger.info("Telegram Bot Service started in Headless mode.");

			// Launch the background watcher to monitor the app process
			dev.frostguard.engine.service.TelegramWatcherLauncher.startWatcherIfNotRunning();
		} else {
			logger.warn("Telegram integration is disabled or missing token. The headless bot might be unreachable unless autostart is used.");
		}
	}

	private static void initializeEmulatorController() {
		HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();

		if (globalConfig == null || globalConfig.isEmpty()) {
			globalConfig = new HashMap<>();
		}

		String savedActiveEmulator = globalConfig.get(ConfigurationKeyEnum.CURRENT_EMULATOR_STRING.name());
		EmulatorType activeEmulator = null;
		if (savedActiveEmulator != null && !savedActiveEmulator.isEmpty()) {
			try {
				activeEmulator = EmulatorType.valueOf(savedActiveEmulator);
			} catch (IllegalArgumentException e) {
				// Ignore Invalid Enum constant
			}
		}
		boolean activeEmulatorValid = false;

		if (activeEmulator != null) {
			String activePath = globalConfig.get(activeEmulator.getConfigKey());
			if (activePath != null && new File(activePath).exists()) {
				activeEmulatorValid = true;
			} else {
				ScheduleService.obtain().persistEmulatorPath(activeEmulator.getConfigKey(), null);
			}
		}

		if (!activeEmulatorValid) {
			logger.warn("No valid active emulator configured. Automation might fail unless changed.");
		}
	}
}
