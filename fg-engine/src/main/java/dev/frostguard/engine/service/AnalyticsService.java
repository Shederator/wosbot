package dev.frostguard.engine.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mixpanel.mixpanelapi.ClientDelivery;
import com.mixpanel.mixpanelapi.MessageBuilder;
import com.mixpanel.mixpanelapi.MixpanelAPI;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

/**
 * Anonymous usage analytics service using Mixpanel.
 * 
 * <p>All tracking is gated by the {@code ANALYTICS_ENABLED_BOOL} global config.
 * Users can opt out at any time via the Config tab toggle.
 * 
 * <p><b>Privacy guarantees:</b>
 * <ul>
 *   <li>No personal data, game profiles, or file paths are ever sent</li>
 *   <li>The analytics ID is a random UUID, not derived from hardware</li>
 *   <li>All tracking calls are fully auditable in this source file</li>
 * </ul>
 * 
 * @see ConfigurationKeyEnum#ANALYTICS_ENABLED_BOOL
 * @see ConfigurationKeyEnum#ANALYTICS_ID_STRING
 */
public class AnalyticsService {

	private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);
	private static final String MIXPANEL_TOKEN = "b858cfb22b56a95757d9e0445f860101";

	private static AnalyticsService instance;

	private MessageBuilder messageBuilder;
	private MixpanelAPI mixpanelApi;
	private String analyticsId;
	private boolean initialized = false;
	private LocalDateTime appStartTime;
	private LocalDateTime botStartTime;
	private String appVersion;
	private ScheduledExecutorService heartbeatExecutor;

	private AnalyticsService() {
	}

	public static AnalyticsService getInstance() {
		if (instance == null) {
			instance = new AnalyticsService();
		}
		return instance;
	}

	/**
	 * Initializes the analytics service. Reads or generates the anonymous ID,
	 * and sets up the Mixpanel client. Safe to call multiple times — subsequent
	 * calls are no-ops.
	 */
	public void initialize() {
		if (initialized) {
			return;
		}

		try {
			appStartTime = LocalDateTime.now();

			// Read or generate analytics ID
			HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
			String storedId = globalConfig != null
					? globalConfig.getOrDefault(ConfigurationKeyEnum.ANALYTICS_ID_STRING.name(), "")
					: "";

			if (storedId == null || storedId.isBlank()) {
				// Generate a new random UUID — never derived from hardware
				analyticsId = UUID.randomUUID().toString();
				ConfigService.obtain().writeGlobalSetting(
						ConfigurationKeyEnum.ANALYTICS_ID_STRING, analyticsId);
				logger.info("Generated new analytics ID");
			} else {
				analyticsId = storedId;
			}

			// Read app version from manifest
			appVersion = getAppVersion();

			// Initialize Mixpanel
			messageBuilder = new MessageBuilder(MIXPANEL_TOKEN);
			mixpanelApi = new MixpanelAPI();

			initialized = true;
			logger.info("Analytics service initialized (enabled: {})", isEnabled());
		} catch (Exception e) {
			logger.warn("Failed to initialize analytics service: {}", e.getMessage());
			initialized = false;
		}
	}

	// ========================================================================
	// TIER 1 EVENTS — Essential (Day 1)
	// ========================================================================

	/**
	 * Tracks application launch with environment info.
	 * Sent once at startup.
	 */
	public void trackAppLaunched(boolean headlessMode) {
		JSONObject props = new JSONObject();
		props.put("version", appVersion);
		props.put("os", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""));
		props.put("os_arch", System.getProperty("os.arch", "unknown"));
		props.put("java_version", System.getProperty("java.version", "unknown"));
		props.put("java_vendor", System.getProperty("java.vendor", "unknown"));
		props.put("headless_mode", headlessMode);
		track("app_launched", props);

		// Start heartbeat to calculate active users
		if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
			heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
			heartbeatExecutor.scheduleAtFixedRate(() -> {
				JSONObject hbProps = new JSONObject();
				hbProps.put("version", appVersion);
				track("app_heartbeat", hbProps);
			}, 10, 10, TimeUnit.MINUTES);
		}
	}

	/**
	 * Tracks application shutdown with session duration.
	 * Sent once at shutdown.
	 */
	public void trackAppShutdown() {
		if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
			heartbeatExecutor.shutdown();
		}
		
		if (appStartTime == null) {
			return;
		}
		JSONObject props = new JSONObject();
		long sessionMinutes = Duration.between(appStartTime, LocalDateTime.now()).toMinutes();
		props.put("session_duration_minutes", sessionMinutes);
		props.put("version", appVersion);
		track("app_shutdown", props);

		// Flush immediately since app is closing
		flush();
	}

	/**
	 * Tracks which emulator type was connected.
	 * 
	 * @param emulatorType The emulator type name (e.g., "MUMU", "MEMU", "LDPLAYER")
	 */
	public void trackEmulatorConnected(String emulatorType) {
		JSONObject props = new JSONObject();
		props.put("emulator_type", emulatorType != null ? emulatorType : "unknown");
		track("emulator_connected", props);
	}

	/**
	 * Tracks the number of configured profiles.
	 * 
	 * @param count Total number of profiles
	 */
	public void trackProfileCount(int count) {
		JSONObject props = new JSONObject();
		props.put("count", count);
		track("profile_count", props);
	}

	/**
	 * Tracks bot start with enabled tasks and profile count.
	 * 
	 * @param enabledTasks List of enabled task/feature names
	 * @param profileCount Number of enabled profiles
	 * @param emulatorType The active emulator type
	 */
	public void trackBotStarted(List<String> enabledTasks, int profileCount, String emulatorType) {
		botStartTime = LocalDateTime.now();
		JSONObject props = new JSONObject();
		props.put("enabled_tasks", enabledTasks);
		props.put("profile_count", profileCount);
		props.put("emulator_type", emulatorType != null ? emulatorType : "unknown");
		props.put("version", appVersion);
		track("bot_started", props);
	}

	/**
	 * Tracks bot stop with reason and runtime.
	 * 
	 * @param reason Why the bot stopped ("manual", "error", "crash")
	 */
	public void trackBotStopped(String reason) {
		JSONObject props = new JSONObject();
		props.put("reason", reason);
		if (botStartTime != null) {
			long runtimeMinutes = Duration.between(botStartTime, LocalDateTime.now()).toMinutes();
			props.put("runtime_minutes", runtimeMinutes);
		}
		props.put("version", appVersion);
		track("bot_stopped", props);
		botStartTime = null;
	}

	// ========================================================================
	// TIER 2 EVENTS — Feature Engagement & Value
	// ========================================================================

	/**
	 * Tracks when a specific task starts executing.
	 * Identifies the most frequently triggered operations.
	 * 
	 * @param taskName Name of the task (e.g., "gather_resources", "nomadic_merchant")
	 */
	public void trackTaskStarted(String taskName) {
		JSONObject props = new JSONObject();
		props.put("task_name", taskName);
		track("task_started", props);
	}

	/**
	 * Tracks when a task finishes. 
	 * Combine with task_started in Mixpanel to generate Success/Fail funnel reports.
	 * 
	 * @param taskName Name of the task
	 * @param status Outcome status (e.g., "success", "failed", "timeout", "skipped")
	 * @param durationSeconds How long the task took to complete
	 */
	public void trackTaskCompleted(String taskName, String status, long durationSeconds) {
		JSONObject props = new JSONObject();
		props.put("task_name", taskName);
		props.put("status", status);
		props.put("duration_seconds", durationSeconds);
		track("task_completed", props);
	}

	// ========================================================================
	// CORE TRACKING INFRASTRUCTURE
	// ========================================================================

	/**
	 * Sends a tracking event to Mixpanel if analytics is enabled.
	 * This is the single gatekeeper — every analytics call goes through here.
	 * 
	 * @param eventName  The event name
	 * @param properties Event properties (no PII allowed)
	 */
	private void track(String eventName, JSONObject properties) {
		if (!initialized || !isEnabled()) {
			return;
		}

		try {
			JSONObject message = messageBuilder.event(analyticsId, eventName, properties);
			ClientDelivery delivery = new ClientDelivery();
			delivery.addMessage(message);

			// Send asynchronously to avoid blocking the main thread
			Thread.ofVirtual().start(() -> {
				try {
					mixpanelApi.deliver(delivery);
					if (!isSilentMode()) {
						logger.info("Analytics event sent: {}", eventName);
					}
				} catch (Exception e) {
					if (!isSilentMode()) {
						logger.warn("Analytics event {} could not be sent; application operation is unaffected: {}",
								eventName, describeDeliveryFailure(e));
					}
				}
			});
		} catch (Exception e) {
			logger.error("Failed to build analytics event {}: {}", eventName, e.getMessage(), e);
		}
	}

	static String describeDeliveryFailure(Exception exception) {
		String message = exception.getMessage();
		return message == null || message.isBlank()
				? exception.getClass().getSimpleName()
				: message;
	}

	/**
	 * Checks whether analytics tracking logs should be hidden based on global config.
	 * 
	 * @return true if logs should be hidden
	 */
	private boolean isSilentMode() {
		try {
			HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
			if (globalConfig == null) {
				return false;
			}
			String value = globalConfig.getOrDefault(
					ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.name(),
					ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.getDefaultValue());
			return Boolean.parseBoolean(value);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Flushes any pending events. Called at shutdown to ensure
	 * the app_shutdown event is delivered.
	 */
	private void flush() {
		// Delivery is immediate per-event, so nothing to flush.
		// This method exists for future batching support.
	}

	/**
	 * Checks whether analytics tracking is enabled via global config.
	 * 
	 * @return true if analytics is enabled
	 */
	public boolean isEnabled() {
		try {
			HashMap<String, String> globalConfig = ConfigService.obtain().loadGlobalSettings();
			if (globalConfig == null) {
				return true; // Default to enabled
			}
			String value = globalConfig.getOrDefault(
					ConfigurationKeyEnum.ANALYTICS_ENABLED_BOOL.name(),
					ConfigurationKeyEnum.ANALYTICS_ENABLED_BOOL.getDefaultValue());
			return Boolean.parseBoolean(value);
		} catch (Exception e) {
			return true; // Default to enabled if config read fails
		}
	}

	/**
	 * Gets the application version from the JAR manifest.
	 */
	private String getAppVersion() {
		try {
			Package pkg = getClass().getPackage();
			if (pkg != null && pkg.getImplementationVersion() != null) {
				return pkg.getImplementationVersion();
			}
			// Fallback: try reading from the parent POM revision property
			return "2.0.0-dev";
		} catch (Exception e) {
			return "unknown";
		}
	}
}
