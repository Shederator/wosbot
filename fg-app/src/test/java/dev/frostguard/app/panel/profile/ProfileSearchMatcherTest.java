package dev.frostguard.app.panel.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProfileSearchMatcherTest {

	@Test
	void searchesAcrossProfileIdentityAndTags() {
		ProfileAux profile = profile();

		assertTrue(ProfileSearchMatcher.matches(profile, "Farm"));
		assertTrue(ProfileSearchMatcher.matches(profile, "987654"));
		assertTrue(ProfileSearchMatcher.matches(profile, "1234"));
		assertTrue(ProfileSearchMatcher.matches(profile, "NERD"));
		assertTrue(ProfileSearchMatcher.matches(profile, "SVS"));
		assertFalse(ProfileSearchMatcher.matches(profile, "PEAK"));
	}

	@Test
	void combinesStructuredFiltersWithAndSemantics() {
		ProfileAux profile = profile();

		assertTrue(ProfileSearchMatcher.matches(profile, "server:1234 tag:svs enabled:true"));
		assertTrue(ProfileSearchMatcher.matches(profile, "alliance:nerd emulator:7"));
		assertFalse(ProfileSearchMatcher.matches(profile, "server:1234 tag:f2p"));
		assertFalse(ProfileSearchMatcher.matches(profile, "enabled:false"));
	}

	private ProfileAux profile() {
		ProfileAux profile = new ProfileAux(42L, "Farm Alpha", "7", true, 1L, "RUNNING", 0L,
				"987654", "Chief Dave", "NERD", "1234");
		profile.setTags(java.util.List.of("Farm", "SVS"));
		return profile;
	}
}
