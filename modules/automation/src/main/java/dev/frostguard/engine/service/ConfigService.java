package dev.frostguard.engine.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.ConfigTemplate;
import dev.frostguard.data.entity.Profile;
import dev.frostguard.data.repository.ConfigRepository;
import dev.frostguard.data.repository.ProfileRepository;

/**
 * Gateway for global and profile-scoped configuration values.
 */
public class ConfigService {

	private static final Logger LOG = LoggerFactory.getLogger(ConfigService.class);
	private static volatile ConfigService holder;

	private final ConfigRepository configStore;
	private final ProfileRepository profileStore;

	private ConfigService() {
		configStore = ConfigRepository.getRepository();
		profileStore = ProfileRepository.getRepository();
	}

	public static ConfigService obtain() {
		ConfigService current = holder;
		if (current != null) {
			return current;
		}
		synchronized (ConfigService.class) {
			if (holder == null) {
				holder = new ConfigService();
				holder.installMissingGlobalDefaults();
			}
			return holder;
		}
	}

	public LinkedHashMap<String, String> loadGlobalSettings() {
		List<Config> rows = configStore.getGlobalSettings();
		if (rows == null || rows.isEmpty()) {
			return null;
		}
		LinkedHashMap<String, String> settings = new LinkedHashMap<>();
		for (Config row : rows) {
			if (row != null && row.getIdentifier() != null && !settings.containsKey(row.getIdentifier())) {
				settings.put(row.getIdentifier(), row.getContent());
			}
		}
		return settings;
	}

	public boolean writeAccountSetting(AccountDescriptor profile, ConfigurationKeyEnum key, String value) {
		if (profile == null || key == null) {
			return false;
		}
		try {
			profile.setConfig(key, value);
			Optional<Config> existing = findProfileConfig(profile.getId(), key);
			boolean saved = existing
					.map(row -> updateConfigRow(row, value, () -> configStore.saveSetting(row)))
					.orElseGet(() -> createProfileConfig(profile, key, value));

			if (saved) {
				logProfileWrite(key, value, existing.isPresent());
				ProfileService.obtain().broadcastAccountDataChange(profile);
			}
			return saved;
		} catch (Exception ex) {
			LOG.error("Could not write profile config {}: {}", key.name(), ex.getMessage(), ex);
			return false;
		}
	}

	public boolean writeGlobalSetting(ConfigurationKeyEnum key, String value) {
		if (key == null) {
			return false;
		}
		try {
			Optional<Config> row = findGlobalConfig(key);
			return row
					.map(cfg -> updateConfigRow(cfg, value, () -> configStore.saveSetting(cfg)))
					.orElseGet(() -> createGlobalConfig(key, value));
		} catch (Exception ex) {
			LOG.error("Could not write global config {}: {}", key.name(), ex.getMessage(), ex);
			return false;
		}
	}

	private void installMissingGlobalDefaults() {
		ConfigTemplate globalTemplate = configStore.getWatcherSetting(TpConfigEnum.GLOBAL_CONFIG);
		if (globalTemplate == null) {
			LOG.warn("Global config template missing; default values were not seeded");
			return;
		}

		LinkedHashMap<String, String> present = Optional.ofNullable(loadGlobalSettings()).orElseGet(LinkedHashMap::new);
		for (ConfigurationKeyEnum key : ConfigurationKeyEnum.values()) {
			if (key.isLegacyOnly()) {
				continue;
			}
			if (key.getDefaultValue() != null && !present.containsKey(key.name())) {
				Config row = bareRow(key.name(), key.getDefaultValue(), globalTemplate);
				if (configStore.addSetting(row)) {
					LOG.info("Default global config inserted: {}={}", key.name(), key.getDefaultValue());
				}
			}
		}
	}

	private Optional<Config> findProfileConfig(Long profileId, ConfigurationKeyEnum key) {
		return Optional.ofNullable(configStore.getAccountSettings(profileId)).stream()
				.flatMap(List::stream)
				.filter(Objects::nonNull)
				.filter(row -> key.name().equalsIgnoreCase(row.getIdentifier()))
				.findFirst();
	}

	private Optional<Config> findGlobalConfig(ConfigurationKeyEnum key) {
		return Optional.ofNullable(configStore.getGlobalSettings()).stream()
				.flatMap(List::stream)
				.filter(Objects::nonNull)
				.filter(row -> key.name().equals(row.getIdentifier()))
				.findFirst();
	}

	private boolean createProfileConfig(AccountDescriptor descriptor, ConfigurationKeyEnum key, String value) {
		ConfigTemplate template = configStore.getWatcherSetting(TpConfigEnum.PROFILE_CONFIG);
		Profile entity = profileStore.getAccountById(descriptor.getId());
		if (template == null || entity == null) {
			LOG.error("Cannot create profile config {}; profile or template was not found", key.name());
			return false;
		}
		return configStore.addSetting(new Config(entity, template, key.name(), value));
	}

	private boolean createGlobalConfig(ConfigurationKeyEnum key, String value) {
		ConfigTemplate template = configStore.getWatcherSetting(TpConfigEnum.GLOBAL_CONFIG);
		if (template == null) {
			LOG.error("Cannot create global config {}; template was not found", key.name());
			return false;
		}
		boolean inserted = configStore.addSetting(bareRow(key.name(), value, template));
		if (inserted) {
			LOG.info("Global config created: {}={}", key.name(), value);
		}
		return inserted;
	}

	private Config bareRow(String identifier, String content, ConfigTemplate template) {
		Config row = new Config();
		row.setIdentifier(identifier);
		row.setContent(content);
		row.setWatcherSetting(template);
		return row;
	}

	private boolean updateConfigRow(Config row, String value, Supplier<Boolean> persistence) {
		row.setContent(value);
		return Boolean.TRUE.equals(persistence.get());
	}

	private void logProfileWrite(ConfigurationKeyEnum key, String value, boolean updatedExisting) {
		if (isHiddenStatisticsUpdate(key)) {
			return;
		}
		if (key == ConfigurationKeyEnum.GIFT_CODE_STATE_JSON) {
			LOG.debug("Profile gift code state {}", updatedExisting ? "updated" : "created");
			return;
		}
		LOG.info("Profile config {} {}: {}", key.name(), updatedExisting ? "updated" : "created", value);
	}

	private boolean isHiddenStatisticsUpdate(ConfigurationKeyEnum key) {
		if (key != ConfigurationKeyEnum.STATISTICS_JSON_STRING) {
			return false;
		}
		LinkedHashMap<String, String> globalSettings = loadGlobalSettings();
		if (globalSettings == null) {
			return false;
		}
		String configured = globalSettings.getOrDefault(
				ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.name(),
				ConfigurationKeyEnum.HIDE_ANALYTICS_LOGS_BOOL.getDefaultValue());
		return Boolean.parseBoolean(configured);
	}

}
