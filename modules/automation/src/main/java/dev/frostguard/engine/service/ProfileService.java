package dev.frostguard.engine.service;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ProfileStatusData;
import dev.frostguard.api.domain.ProfileTagData;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.Profile;
import dev.frostguard.data.repository.ConfigRepository;
import dev.frostguard.data.repository.ProfileRepository;
import dev.frostguard.engine.listener.ProfileDataChangeListener;
import dev.frostguard.engine.listener.ProfileServiceInterface;
import dev.frostguard.engine.listener.ProfileStatusChangeListener;

/**
 * Profile facade used by the UI, scheduler, and external controls.
 */
public class ProfileService implements ProfileServiceInterface {

	private static final Logger LOG = LoggerFactory.getLogger(ProfileService.class);
	private static volatile ProfileService singleton;

	private final ProfileRepository profiles;
	private final ConfigRepository configs;
	private final CopyOnWriteArrayList<ProfileStatusChangeListener> statusListeners = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<ProfileDataChangeListener> dataListeners = new CopyOnWriteArrayList<>();

	private ProfileService() {
		profiles = ProfileRepository.getRepository();
		configs = ConfigRepository.getRepository();
	}

	public static ProfileService obtain() {
		ProfileService service = singleton;
		if (service != null) {
			return service;
		}
		synchronized (ProfileService.class) {
			if (singleton == null) {
				singleton = new ProfileService();
			}
			return singleton;
		}
	}

	@Override
	public List<AccountDescriptor> fetchAllAccounts() {
		return profiles.getAccounts();
	}

	public HashMap<ConfigurationKeyEnum, String> loadGlobalSettingsMap() {
		List<Config> rows = configs.getGlobalSettings();
		if (rows == null) {
			return null;
		}
		HashMap<ConfigurationKeyEnum, String> mapped = new HashMap<>();
		for (Config row : rows) {
			if (row != null && row.getIdentifier() != null) {
				mapped.put(ConfigurationKeyEnum.valueOf(row.getIdentifier()), row.getContent());
			}
		}
		return mapped;
	}

	@Override
	public boolean createAccount(AccountDescriptor descriptor) {
		return withDescriptor(descriptor, "create", account -> {
			Profile entity = new Profile();
			copyDescriptorFields(account, entity);
			Long id = profiles.createAccountAggregate(entity, account.getConfigs(), account.getTags());
			account.setId(id);
			return id != null;
		}, descriptor);
	}

	@Override
	public boolean persistAccount(AccountDescriptor descriptor) {
		if (descriptor == null || descriptor.getId() == null) {
			return false;
		}
		return withDescriptor(descriptor, "save", account -> {
			if (!profiles.updateAccountMetadata(account)) {
				return false;
			}
			if (!profiles.replaceProfileTags(account.getId(), account.getTags())) {
				return false;
			}
			return true;
		}, descriptor);
	}

	@Override
	public boolean persistAccountSetting(Long profileId, ConfigurationKeyEnum key, String value) {
		return ConfigService.obtain().writeAccountSetting(profileId, key, value);
	}

	@Override
	public boolean removeAccount(AccountDescriptor descriptor) {
		if (descriptor == null || descriptor.getId() == null) {
			return false;
		}
		return withDescriptor(descriptor, "remove", account -> {
			Profile entity = profiles.getAccountById(account.getId());
			if (entity == null) {
				return false;
			}
			List<Config> accountSettings = configs.getAccountSettings(entity.getId());
			if (accountSettings != null) {
				accountSettings.forEach(configs::deleteSetting);
			}
			return profiles.deleteAccount(entity);
		}, descriptor);
	}

	@Override
	public boolean applyBulkUpdate(AccountDescriptor template) {
		if (template == null) {
			return false;
		}
		List<AccountDescriptor> accounts = fetchAllAccounts();
		if (accounts == null || accounts.isEmpty()) {
			return false;
		}

		boolean complete = true;
		for (AccountDescriptor account : accounts) {
			for (var setting : template.getConfigs()) {
				try {
					ConfigurationKeyEnum key = ConfigurationKeyEnum.valueOf(setting.getConfigurationName());
					if (!ConfigService.obtain().writeAccountSetting(account, key, setting.getValue())) {
						complete = false;
						LOG.warn("Bulk profile config update failed for {} key={}", account.getName(), key.name());
					}
				} catch (IllegalArgumentException ex) {
					complete = false;
					LOG.warn("Bulk profile update skipped unknown key {} for {}",
							setting.getConfigurationName(), account.getName());
				}
			}
		}
		return complete;
	}

	@Override
	public List<String> fetchProfileTags() {
		return profiles.getTagNames();
	}

	@Override
	public List<ProfileTagData> fetchProfileTagDefinitions() { return profiles.getTags(); }

	@Override
	public boolean updateProfileTag(String oldName, String newName, String color) {
		return profiles.updateTag(oldName, newName, color);
	}

	@Override
	public boolean deleteProfileTag(String name) { return profiles.deleteTag(name); }

	@Override
	public boolean replaceProfileTags(Long profileId, List<String> tags) {
		boolean changed = profiles.replaceProfileTags(profileId, tags);
		if (changed) {
			AccountDescriptor updated = profiles.getAccountWithSettingsById(profileId);
			if (updated != null) {
				broadcastAccountDataChange(updated);
			}
		}
		return changed;
	}

	public void broadcastStatusChange(ProfileStatusData state) {
		statusListeners.forEach(listener -> listener.onAccountStatusUpdated(state));
	}

	@Override
	public void registerStatusObserver(ProfileStatusChangeListener observer) {
		if (observer != null) {
			statusListeners.addIfAbsent(observer);
		}
	}

	@Override
	public void registerDataObserver(ProfileDataChangeListener observer) {
		if (observer != null) {
			dataListeners.addIfAbsent(observer);
		}
	}

	public void broadcastAccountDataChange(AccountDescriptor profile) {
		dataListeners.forEach(listener -> listener.onAccountDataModified(profile));
	}

	private boolean withDescriptor(AccountDescriptor descriptor, String action,
			ProfileMutation mutation, AccountDescriptor changeEventPayload) {
		if (descriptor == null) {
			return false;
		}
		try {
			boolean changed = mutation.apply(descriptor);
			if (changed) {
				AccountDescriptor committed = descriptor.getId() == null
						? null
						: profiles.getAccountWithSettingsById(descriptor.getId());
				broadcastAccountDataChange(committed != null ? committed : changeEventPayload);
			}
			return changed;
		} catch (Exception ex) {
			LOG.error("Profile {} failed: {}", action, ex.getMessage(), ex);
			return false;
		}
	}

	private void copyDescriptorFields(AccountDescriptor source, Profile target) {
		target.setLabel(source.getName());
		target.setDeviceIndex(source.getEmulatorNumber());
		target.setActive(source.getEnabled());
		target.setWeight(source.getPriority());
		target.setRetryInterval(source.getReconnectionTime());
		target.setAvatarId(source.getCharacterId());
		target.setAvatarName(source.getCharacterName());
		target.setGuildTag(source.getCharacterAllianceCode());
		target.setRealm(source.getCharacterServer());
	}

	@FunctionalInterface
	private interface ProfileMutation {
		boolean apply(AccountDescriptor descriptor);
	}

}
