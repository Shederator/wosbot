package dev.frostguard.engine.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.ProfilesData;

/**
 * Maintains per-profile counters and task execution aggregates.
 */
public class StatisticsService {

	private static final Logger LOG = LoggerFactory.getLogger(StatisticsService.class);
	private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static volatile StatisticsService holder;

	private final ObjectMapper mapper = new ObjectMapper();

	private StatisticsService() {
	}

	public static StatisticsService obtain() {
		StatisticsService service = holder;
		if (service != null) {
			return service;
		}
		synchronized (StatisticsService.class) {
			if (holder == null) {
				holder = new StatisticsService();
			}
			return holder;
		}
	}

	public synchronized void addToCounter(AccountDescriptor profile, String counterName, int delta) {
		if (profile == null || counterName == null) {
			return;
		}
		mutate(profile, data -> data.getCustomCounters().merge(counterName, delta, Integer::sum));
	}

	public synchronized void logJobExecution(AccountDescriptor profile, String jobName,
			long durationMs, int ocrMisses, int templateMisses) {
		if (profile == null || jobName == null) {
			return;
		}
		mutate(profile, data -> {
			JobMetrics metrics = data.getTaskStatistics().computeIfAbsent(jobName, JobMetrics::new);
			metrics.setNumberOfRuns(metrics.getNumberOfRuns() + 1);
			metrics.setTotalExecutionTimeMs(metrics.getTotalExecutionTimeMs() + durationMs);
			metrics.setTotalOcrFailures(metrics.getTotalOcrFailures() + ocrMisses);
			metrics.setTotalTemplateSearchFailures(metrics.getTotalTemplateSearchFailures() + templateMisses);
			metrics.setLastRunTime(LocalDateTime.now().format(RUN_STAMP));
		});
	}

	public synchronized void clearMetrics(AccountDescriptor profile) {
		if (profile == null) {
			return;
		}
		store(profile, new ProfilesData());
		LOG.info("Metrics reset for profile id={}", profile.getId());
	}

	public ProfilesData loadMetrics(AccountDescriptor profile) {
		if (profile == null) {
			return new ProfilesData();
		}
		String raw = profile.getConfig(ConfigurationKeyEnum.STATISTICS_JSON_STRING, String.class);
		if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
			return new ProfilesData();
		}
		try {
			return mapper.readValue(raw, ProfilesData.class);
		} catch (JsonProcessingException ex) {
			LOG.error("Metrics payload ignored for profile id={}: {}", profile.getId(), ex.getMessage());
			return new ProfilesData();
		}
	}

	private void mutate(AccountDescriptor profile, Consumer<ProfilesData> change) {
		ProfilesData data = loadMetrics(profile);
		change.accept(data);
		store(profile, data);
	}

	private void store(AccountDescriptor profile, ProfilesData data) {
		try {
			String payload = mapper.writeValueAsString(data);
			ConfigService.obtain().writeAccountSetting(profile, ConfigurationKeyEnum.STATISTICS_JSON_STRING, payload);
		} catch (JsonProcessingException ex) {
			LOG.error("Metrics payload could not be saved for profile id={}: {}", profile.getId(), ex.getMessage());
		}
	}

}
