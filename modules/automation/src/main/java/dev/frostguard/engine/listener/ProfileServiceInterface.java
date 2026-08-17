package dev.frostguard.engine.listener;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ProfileTagData;

import java.util.List;

// Profile CRUD operations and listener registration hooks exposed to the UI layer.
public interface ProfileServiceInterface {

    List<AccountDescriptor> fetchAllAccounts();

    boolean createAccount(AccountDescriptor profile);

    boolean persistAccount(AccountDescriptor profile);

    boolean persistAccountSetting(Long profileId, ConfigurationKeyEnum key, String value);

    boolean removeAccount(AccountDescriptor profile);

    boolean applyBulkUpdate(AccountDescriptor templateAccount);

    List<String> fetchProfileTags();

    List<ProfileTagData> fetchProfileTagDefinitions();

    boolean updateProfileTag(String oldName, String newName, String color);

    boolean deleteProfileTag(String name);

    boolean replaceProfileTags(Long profileId, List<String> tags);

    void registerStatusObserver(ProfileStatusChangeListener observer);

    void registerDataObserver(ProfileDataChangeListener observer);
}
