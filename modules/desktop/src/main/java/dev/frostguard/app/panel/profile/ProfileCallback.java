package dev.frostguard.app.panel.profile;

import java.util.List;

import dev.frostguard.api.domain.AccountDescriptor;

@FunctionalInterface
public interface ProfileCallback {

	void onProfilesLoaded(List<AccountDescriptor> profiles);

	default void onProfileLoadFailed(Throwable error) {
	}

	default void onProfilesLoadedSafely(List<AccountDescriptor> profiles) {
		onProfilesLoaded(profiles == null ? List.of() : profiles);
	}

	static ProfileCallback noop() {
		return profiles -> {
		};
	}

	static ProfileCallback nullSafe(ProfileCallback callback) {
		return profiles -> {
			if (callback != null) {
				callback.onProfilesLoadedSafely(profiles);
			}
		};
	}
}
