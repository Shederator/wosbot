package dev.frostguard.data.repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.ConfigTemplate;

/**
 * Manages {@link Config} persistence operations for both profile-scoped
 * and global settings. Provides intent-expressing domain methods
 * alongside legacy-compatible delegates.
 */
public class ConfigRepository {

	private static ConfigRepository instance;
	private final DataStore store = DataStore.getInstance();

	private ConfigRepository() {}

	public static ConfigRepository getRepository() {
		if (instance == null) {
			instance = new ConfigRepository();
		}
		return instance;
	}

	public List<Config> settingsForProfile(Long profileId) {
		if (profileId == null) return Collections.emptyList();
		return queryByOwner(profileId);
	}

	public List<Config> globalSettings() {
		return store.executeQuery(
			"SELECT c FROM Config c WHERE c.owner IS NULL", Config.class, null);
	}

	public ConfigTemplate templateById(TpConfigEnum key) {
		if (key == null) return null;
		return store.lookup(ConfigTemplate.class, key.getId());
	}

	public Optional<Config> findSetting(Long profileId, String keyName) {
		if (profileId == null || keyName == null) return Optional.empty();
		return queryByOwner(profileId).stream()
			.filter(c -> keyName.equalsIgnoreCase(c.getIdentifier()))
			.findFirst();
	}

	public boolean addSetting(Config config) { return store.persist(config); }
	public boolean saveSetting(Config config) { return store.merge(config); }
	public boolean deleteSetting(Config config) { return store.remove(config); }

	public Config getSettingById(Long id) {
		if (id == null) return null;
		return store.lookup(Config.class, id);
	}

	// Compatibility delegates
	public List<Config> getAccountSettings(Long accountId) { return settingsForProfile(accountId); }
	public List<Config> getGlobalSettings() { return globalSettings(); }
	public ConfigTemplate getWatcherSetting(TpConfigEnum settingKey) { return templateById(settingKey); }

	private List<Config> queryByOwner(Long profileId) {
		return store.executeQuery(
			"SELECT c FROM Config c WHERE c.owner.id = :profileId",
			Config.class, Map.of("profileId", profileId));
	}
}
