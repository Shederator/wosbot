package dev.frostguard.app.panel.profile;

import java.util.Collection;

@FunctionalInterface
public interface IProfileLoadListener {

	void onProfileLoad(ProfileAux profile);

	default void onProfileUnload() {
		onProfileLoad(null);
	}

	static IProfileLoadListener noOp() {
		return profile -> {
		};
	}

	static void notifyAll(Collection<? extends IProfileLoadListener> listeners, ProfileAux profile) {
		if (listeners == null) {
			return;
		}
		listeners.stream()
			.filter(listener -> listener != null)
			.forEach(listener -> listener.onProfileLoad(profile));
	}
}
