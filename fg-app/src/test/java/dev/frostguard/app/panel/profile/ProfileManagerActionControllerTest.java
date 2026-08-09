package dev.frostguard.app.panel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.listener.ProfileStatusChangeListener;

class ProfileManagerActionControllerTest {

	@Test
	void disablesSelectedProfilesAndPausesTheirRunningQueues() {
		StubProfileModel model = new StubProfileModel();
		StubRuntimeController runtime = new StubRuntimeController(Set.of(1L, 3L));
		ProfileManagerActionController controller = new ProfileManagerActionController(null, model, runtime);
		ProfileAux first = profile(1L, "First", true);
		ProfileAux second = profile(2L, "Second", true);

		var result = controller.setProfilesEnabled(List.of(first, second), false);

		assertEquals(2, result.updated());
		assertEquals(0, result.failed());
		assertFalse(first.isEnabled());
		assertFalse(second.isEnabled());
		assertEquals(List.of(1L), runtime.pausedProfileIds);
	}

	@Test
	void restoresOriginalStateWhenOneProfileCannotBeSaved() {
		StubProfileModel model = new StubProfileModel();
		model.failingProfileIds.add(2L);
		StubRuntimeController runtime = new StubRuntimeController(Set.of(1L, 2L));
		ProfileManagerActionController controller = new ProfileManagerActionController(null, model, runtime);
		ProfileAux first = profile(1L, "First", true);
		ProfileAux second = profile(2L, "Second", true);

		var result = controller.setProfilesEnabled(List.of(first, second), false);

		assertEquals(1, result.updated());
		assertEquals(1, result.failed());
		assertFalse(first.isEnabled());
		assertTrue(second.isEnabled());
		assertEquals(List.of(1L), runtime.pausedProfileIds);
	}

	@Test
	void countsOnlySelectedProfilesWithRunningQueues() {
		StubRuntimeController runtime = new StubRuntimeController(Set.of(2L, 4L));
		ProfileManagerActionController controller = new ProfileManagerActionController(
				null, new StubProfileModel(), runtime);

		long running = controller.countRunningProfiles(List.of(
				profile(1L, "First", true), profile(2L, "Second", true), profile(3L, "Third", true)));

		assertEquals(1, running);
	}

	@Test
	void addsAndRemovesTagsAcrossSelectedProfiles() {
		StubProfileModel model = new StubProfileModel();
		ProfileManagerActionController controller = new ProfileManagerActionController(
				null, model, new StubRuntimeController(Set.of()));
		ProfileAux first = profile(1L, "First", true);
		ProfileAux second = profile(2L, "Second", true);
		second.setTags(List.of("Farm"));

		var added = controller.updateTag(List.of(first, second), " Farm ", true);
		var removed = controller.updateTag(List.of(first, second), "farm", false);

		assertTrue(added.successful());
		assertTrue(removed.successful());
		assertEquals(List.of(), first.getTags());
		assertEquals(List.of(), second.getTags());
		assertEquals(List.of("1:Farm", "2:Farm", "1:", "2:"), model.savedTagAssignments);
	}

	private ProfileAux profile(Long id, String name, boolean enabled) {
		return new ProfileAux(id, name, String.valueOf(id), enabled, id, "NOT RUNNING", 0L,
				null, null, null, null);
	}

	private static final class StubRuntimeController
			implements ProfileManagerActionController.ProfileRuntimeController {
		private final Set<Long> activeProfileIds;
		private final List<Long> pausedProfileIds = new ArrayList<>();

		private StubRuntimeController(Set<Long> activeProfileIds) {
			this.activeProfileIds = new HashSet<>(activeProfileIds);
		}

		@Override
		public Set<Long> activeQueueIds() {
			return Set.copyOf(activeProfileIds);
		}

		@Override
		public void pauseQueue(Long profileId) {
			pausedProfileIds.add(profileId);
			activeProfileIds.remove(profileId);
		}
	}

	private static final class StubProfileModel implements IProfileModel {
		private final Set<Long> failingProfileIds = new HashSet<>();
		private final List<String> savedTagAssignments = new ArrayList<>();

		@Override
		public List<AccountDescriptor> getProfiles() {
			return List.of();
		}

		@Override
		public boolean addProfile(AccountDescriptor profile) {
			return true;
		}

		@Override
		public boolean saveProfile(AccountDescriptor profile) {
			return !failingProfileIds.contains(profile.getId());
		}

		@Override
		public boolean deleteProfile(AccountDescriptor profile) {
			return true;
		}

		@Override
		public boolean bulkUpdateProfiles(AccountDescriptor profile) {
			return true;
		}

		@Override
		public List<String> getTags() {
			return List.of();
		}

		@Override
		public boolean replaceTags(Long profileId, List<String> tags) {
			savedTagAssignments.add(profileId + ":" + String.join(",", tags));
			return true;
		}

		@Override
		public void addProfileStatusChangeListerner(ProfileStatusChangeListener listener) {
		}
	}
}
