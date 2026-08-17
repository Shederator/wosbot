package dev.frostguard.data.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.api.domain.ProfileTagData;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.ConfigTemplate;
import dev.frostguard.data.entity.Profile;
import dev.frostguard.data.entity.ProfileTag;
import dev.frostguard.api.configs.TpConfigEnum;

/**
 * Manages {@link Profile} persistence operations and the associated
 * configuration entries. Provides both intent-expressing domain methods
 * and legacy-compatible delegates for downstream callers.
 */
public class ProfileRepository {

	private static ProfileRepository instance;
	private final DataStore store;

	private ProfileRepository() { this(DataStore.getInstance()); }

	public ProfileRepository(DataStore store) { this.store = java.util.Objects.requireNonNull(store); }

	public static ProfileRepository getRepository() {
		if (instance == null) {
			instance = new ProfileRepository();
		}
		return instance;
	}

	public List<AccountDescriptor> getAccounts() {
		List<AccountDescriptor> descriptors = fetchAllDescriptors();

		if (descriptors.isEmpty()) {
			provisionDefaultProfile();
			descriptors = fetchAllDescriptors();
		}

		attachConfigEntries(descriptors);
		attachTags(descriptors);
		return descriptors;
	}

	public AccountDescriptor getAccountWithSettingsById(Long id) {
		if (id == null) return null;

		return findDescriptorById(id)
			.map(this::attachSingleConfigs)
			.map(this::attachSingleTags)
			.orElse(null);
	}

	public List<AccountDescriptor> findEnabledProfiles() {
		return getAccounts().stream()
			.filter(a -> Boolean.TRUE.equals(a.getEnabled()))
			.collect(Collectors.toList());
	}

	public boolean addAccount(Profile profile) { return store.persist(profile); }

	public Long createAccountAggregate(Profile profile, List<ConfigData> settings, List<String> tagNames) {
		try {
			return store.withinTransaction(em -> {
				ConfigTemplate template = em.find(ConfigTemplate.class, TpConfigEnum.PROFILE_CONFIG.getId());
				if (template == null) throw new IllegalStateException("Profile configuration template is missing");
				for (ConfigData setting : settings == null ? List.<ConfigData>of() : settings) {
					profile.getSettings().add(new Config(profile, template,
							setting.getConfigurationName(), setting.getValue()));
				}
				for (String raw : tagNames == null ? List.<String>of() : tagNames) {
					String name = normalizeTag(raw);
					if (name.isBlank()) continue;
					List<ProfileTag> matches = em.createQuery(
							"SELECT t FROM ProfileTag t WHERE lower(t.name) = :name", ProfileTag.class)
							.setParameter("name", name.toLowerCase()).getResultList();
					ProfileTag tag = matches.isEmpty() ? new ProfileTag(name) : matches.get(0);
					if (matches.isEmpty()) em.persist(tag);
					profile.getTags().add(tag);
				}
				em.persist(profile);
				em.flush();
				return profile.getId();
			});
		} catch (Exception ex) {
			return null;
		}
	}
	public boolean saveAccount(Profile profile) { return store.merge(profile); }

	public boolean updateAccountMetadata(AccountDescriptor descriptor) {
		if (descriptor == null || descriptor.getId() == null) return false;
		try {
			return store.withinTransaction(em -> {
				Profile profile = em.find(Profile.class, descriptor.getId());
				if (profile == null) return false;
				profile.setLabel(descriptor.getName());
				profile.setDeviceIndex(descriptor.getEmulatorNumber());
				profile.setActive(descriptor.getEnabled());
				profile.setWeight(descriptor.getPriority());
				profile.setRetryInterval(descriptor.getReconnectionTime());
				profile.setAvatarId(descriptor.getCharacterId());
				profile.setAvatarName(descriptor.getCharacterName());
				profile.setGuildTag(descriptor.getCharacterAllianceCode());
				profile.setRealm(descriptor.getCharacterServer());
				return true;
			});
		} catch (Exception ex) {
			return false;
		}
	}
	public boolean deleteAccount(Profile profile) { return store.remove(profile); }

	public Profile getAccountById(Long id) {
		if (id == null) return null;
		return store.lookup(Profile.class, id);
	}

	public List<String> getTagNames() {
		return store.executeQuery("SELECT t.name FROM ProfileTag t ORDER BY lower(t.name)", String.class, null);
	}

	public List<ProfileTagData> getTags() {
		return store.executeQuery("SELECT t FROM ProfileTag t ORDER BY lower(t.name)", ProfileTag.class, null)
				.stream().map(tag -> new ProfileTagData(tag.getName(),
						tag.getColor() == null ? "#388bfd" : tag.getColor())).toList();
	}

	public boolean updateTag(String oldName, String newName, String color) {
		try {
			return store.withinTransaction(em -> {
				String normalizedName = normalizeTag(newName);
				if (normalizedName.isBlank()) return false;
				Long collisionCount = em.createQuery(
						"SELECT count(t) FROM ProfileTag t WHERE lower(t.name) = :newName AND lower(t.name) <> :oldName",
						Long.class).setParameter("newName", normalizedName.toLowerCase())
						.setParameter("oldName", oldName.toLowerCase()).getSingleResult();
				if (collisionCount > 0) return false;
				List<ProfileTag> matches = em.createQuery(
						"SELECT t FROM ProfileTag t WHERE lower(t.name) = :name", ProfileTag.class)
						.setParameter("name", oldName.toLowerCase()).getResultList();
				if (matches.isEmpty()) return false;
				ProfileTag tag = matches.get(0);
				tag.setName(normalizedName);
				tag.setColor(normalizeColor(color));
				return true;
			});
		} catch (Exception ex) {
			return false;
		}
	}

	public boolean deleteTag(String name) {
		try {
			return store.withinTransaction(em -> {
				List<ProfileTag> matches = em.createQuery(
						"SELECT t FROM ProfileTag t WHERE lower(t.name) = :name", ProfileTag.class)
						.setParameter("name", name.toLowerCase()).getResultList();
				if (matches.isEmpty()) return false;
				ProfileTag tag = matches.get(0);
				em.createNativeQuery("DELETE FROM profile_tag_assignments WHERE tag_id = ?")
						.setParameter(1, tag.getId()).executeUpdate();
				em.remove(tag);
				return true;
			});
		} catch (Exception ex) {
			return false;
		}
	}

	public boolean replaceProfileTags(Long profileId, List<String> tagNames) {
		if (profileId == null) return false;
		try {
			return store.withinTransaction(em -> {
				Profile profile = em.find(Profile.class, profileId);
				if (profile == null) return false;
				var normalized = tagNames == null ? List.<String>of() : tagNames.stream()
						.map(ProfileRepository::normalizeTag)
						.filter(name -> !name.isBlank())
						.distinct()
						.toList();
				var assigned = new java.util.LinkedHashSet<ProfileTag>();
				for (String name : normalized) {
					List<ProfileTag> matches = em.createQuery(
							"SELECT t FROM ProfileTag t WHERE lower(t.name) = :name", ProfileTag.class)
							.setParameter("name", name.toLowerCase())
							.getResultList();
					ProfileTag tag = matches.isEmpty() ? new ProfileTag(name) : matches.get(0);
					if (matches.isEmpty()) em.persist(tag);
					assigned.add(tag);
				}
				profile.setTags(assigned);
				return true;
			});
		} catch (Exception ex) {
			return false;
		}
	}

	public List<Config> getAccountSettings(Long accountId) {
		if (accountId == null) return Collections.emptyList();
		return querySettingsByProfile(accountId);
	}

	public boolean deleteSettings(List<Config> configs) {
		if (configs == null || configs.isEmpty()) return false;
		try {
			configs.forEach(store::remove);
			return true;
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}

	public boolean saveSettings(List<Config> configs) {
		if (configs == null || configs.isEmpty()) return false;
		try {
			configs.forEach(store::persist);
			return true;
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}

	private static final String DESCRIPTOR_PROJECTION =
		"SELECT new dev.frostguard.api.domain.AccountDescriptor(" +
			"p.id, p.label, p.deviceIndex, p.active, p.weight, p.retryInterval, " +
			"p.avatarId, p.avatarName, p.guildTag, p.realm" +
		") FROM Profile p";

	private static final String CONFIG_PROJECTION =
		"SELECT new dev.frostguard.api.domain.ConfigData(" +
			"c.owner.id, c.identifier, c.content" +
		") FROM Config c";

	private List<AccountDescriptor> fetchAllDescriptors() {
		List<AccountDescriptor> result = store.executeQuery(
			DESCRIPTOR_PROJECTION, AccountDescriptor.class, null);
		return result != null ? result : new ArrayList<>();
	}

	private Optional<AccountDescriptor> findDescriptorById(Long profileId) {
		String query = DESCRIPTOR_PROJECTION + " WHERE p.id = :profileId";
		List<AccountDescriptor> rows = store.executeQuery(
			query, AccountDescriptor.class, Map.of("profileId", profileId));
		return rows != null && !rows.isEmpty()
			? Optional.of(rows.get(0))
			: Optional.empty();
	}

	private void attachConfigEntries(List<AccountDescriptor> descriptors) {
		List<Long> profileIds = descriptors.stream()
			.map(AccountDescriptor::getId)
			.collect(Collectors.toList());

		if (profileIds.isEmpty()) return;

		String query = CONFIG_PROJECTION + " WHERE c.owner.id IN :profileIds";
		List<ConfigData> entries = store.executeQuery(
			query, ConfigData.class, Map.of("profileIds", profileIds));

		if (entries == null) return;

		Map<Long, List<ConfigData>> grouped = entries.stream()
			.collect(Collectors.groupingBy(ConfigData::getProfileId));

		descriptors.forEach(d ->
			d.setConfigs(grouped.getOrDefault(d.getId(), new ArrayList<>())));
	}

	private void attachTags(List<AccountDescriptor> descriptors) {
		if (descriptors.isEmpty()) return;
		List<Object[]> rows = store.executeQuery(
				"SELECT p.id, t.name FROM Profile p JOIN p.tags t ORDER BY lower(t.name)", Object[].class, null);
		if (rows == null) return;
		Map<Long, List<String>> grouped = rows.stream().collect(Collectors.groupingBy(
				row -> (Long) row[0], Collectors.mapping(row -> (String) row[1], Collectors.toList())));
		descriptors.forEach(descriptor -> descriptor.setTags(grouped.getOrDefault(descriptor.getId(), List.of())));
	}

	private AccountDescriptor attachSingleTags(AccountDescriptor descriptor) {
		List<String> tags = store.executeQuery(
				"SELECT t.name FROM Profile p JOIN p.tags t WHERE p.id = :profileId ORDER BY lower(t.name)",
				String.class, Map.of("profileId", descriptor.getId()));
		descriptor.setTags(tags);
		return descriptor;
	}

	private static String normalizeTag(String raw) {
		if (raw == null) return "";
		String trimmed = raw.trim().replaceAll("\\s+", " ");
		return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40);
	}

	private static String normalizeColor(String raw) {
		return raw != null && raw.matches("#[0-9a-fA-F]{6}") ? raw.toLowerCase() : "#388bfd";
	}

	private AccountDescriptor attachSingleConfigs(AccountDescriptor descriptor) {
		String query = CONFIG_PROJECTION + " WHERE c.owner.id = :profileId";
		List<ConfigData> entries = store.executeQuery(
			query, ConfigData.class, Map.of("profileId", descriptor.getId()));
		descriptor.setConfigs(entries != null ? entries : new ArrayList<>());
		return descriptor;
	}

	private List<Config> querySettingsByProfile(Long profileId) {
		return store.executeQuery(
			"SELECT c FROM Config c WHERE c.owner.id = :profileId",
			Config.class, Map.of("profileId", profileId));
	}

	private void provisionDefaultProfile() {
		store.persist(Profile.createDefault());
	}
}
