package dev.frostguard.app.panel.profile;

import java.util.Collection;
import java.util.List;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ProfileTagData;
import dev.frostguard.engine.listener.ProfileStatusChangeListener;

public interface IProfileModel {

	List<AccountDescriptor> getProfiles();

	boolean addProfile(AccountDescriptor profile);

	boolean saveProfile(AccountDescriptor profile);

	boolean deleteProfile(AccountDescriptor profile);

	boolean bulkUpdateProfiles(AccountDescriptor templateProfile);

	List<String> getTags();
	default List<ProfileTagData> getTagDefinitions() { return List.of(); }
	default boolean updateTag(String oldName, String newName, String color) { return false; }
	default boolean deleteTag(String name) { return false; }

	boolean replaceTags(Long profileId, List<String> tags);

	void addProfileStatusChangeListerner(ProfileStatusChangeListener listener);

	default int saveProfiles(Collection<AccountDescriptor> profiles) {
		if (profiles == null || profiles.isEmpty()) {
			return 0;
		}

		int saved = 0;
		for (AccountDescriptor profile : profiles) {
			if (saveProfile(profile)) {
				saved++;
			}
		}
		return saved;
	}

}
