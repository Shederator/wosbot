package dev.frostguard.app.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.engine.service.AnalyticsService;
import dev.frostguard.tasks.TaskRegistrations;
import dev.frostguard.vision.logging.ProfileContextLogger;

public class Main {
	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		try {
			boolean isHeadless = false;
			for (String arg : args) {
				if ("--headless".equalsIgnoreCase(arg)) {
					isHeadless = true;
					break;
				}
			}
			
			// 1. Setup environment
			System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
			java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.WARNING);
			java.util.logging.Logger.getLogger("javafx").setLevel(java.util.logging.Level.SEVERE);
			java.util.logging.Logger.getLogger("com.sun.javafx").setLevel(java.util.logging.Level.SEVERE);
			java.util.logging.Logger.getLogger("javax.swing").setLevel(java.util.logging.Level.SEVERE);

			logger.info("Initializing Frostguard...");

			// 2. Setup Shutdown Hook
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logger.info("Closing down subsystems.");
				try { AnalyticsService.getInstance().trackAppShutdown(); } catch (Exception ignored) {}
				ProfileContextLogger.shutdown();
			}, "frostguard-shutdown"));

			// 3. Initialize Services
			try { AnalyticsService.getInstance().initialize(); } catch (Exception ignored) {}
			TaskRegistrations.initialize();
			try { AnalyticsService.getInstance().trackAppLaunched(isHeadless); } catch (Exception ignored) {}

			// 4. Delegate to appropriate launcher
			if (isHeadless) {
				logger.info("Headless application triggered.");
				HeadlessApp.start(args);
				Thread.currentThread().join();
			} else {
				FXApp.main(args);
			}

		} catch (Exception e) {
			logger.error("Startup failure: ", e);
			ProfileContextLogger.shutdown();
			System.exit(1);
		}
	}
}
