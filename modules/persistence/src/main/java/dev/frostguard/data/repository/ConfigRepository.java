package dev.frostguard.data.repository;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.ConfigTemplate;
import dev.frostguard.data.entity.Profile;

/**
 * Manages {@link Config} persistence operations for both profile-scoped
 * and global settings. Provides intent-expressing domain methods
 * alongside legacy-compatible delegates.
 */
public class ConfigRepository {

	private static ConfigRepository instance;
	private final DataStore store;

	private ConfigRepository() { this(DataStore.getInstance()); }

	public ConfigRepository(DataStore store) {
		this.store = Objects.requireNonNull(store);
	}

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

	/**
	 * Updates exactly one profile-scoped setting in one transaction. Duplicate
	 * legacy rows for the same key are collapsed while the value is written.
	 */
	public boolean writeProfileSetting(Long profileId, String keyName, String value) {
		if (profileId == null || keyName == null || keyName.isBlank()) return false;
		try {
			return store.withinTransaction(em -> {
				Profile profile = em.find(Profile.class, profileId);
				ConfigTemplate template = em.find(ConfigTemplate.class, TpConfigEnum.PROFILE_CONFIG.getId());
				if (profile == null || template == null) return false;

				List<Config> matches = em.createQuery(
						"SELECT c FROM Config c WHERE c.owner.id = :profileId "
								+ "AND lower(c.identifier) = :key ORDER BY c.id",
						Config.class)
						.setParameter("profileId", profileId)
						.setParameter("key", keyName.toLowerCase(Locale.ROOT))
						.getResultList();

				String storedValue = value == null ? "" : value;
				if (matches.isEmpty()) {
					em.persist(new Config(profile, template, keyName, storedValue));
				} else {
					matches.get(0).setContent(storedValue);
					matches.stream().skip(1).forEach(em::remove);
				}
				return true;
			});
		} catch (Exception ex) {
			return false;
		}
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
