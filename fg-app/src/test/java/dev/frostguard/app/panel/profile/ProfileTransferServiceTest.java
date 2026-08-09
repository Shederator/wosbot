package dev.frostguard.app.panel.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTransferServiceTest {

	@TempDir
	Path tempDirectory;

	@Test
	void roundTripsMultipleProfilesWithoutDatabaseIds() throws Exception {
		ProfileTransferService service = new ProfileTransferService();
		ProfileAux first = profile(7L, "Farm One");
		ProfileAux second = profile(8L, "Farm Two");
		Path export = tempDirectory.resolve("profiles.json");

		service.write(export, List.of(first, second));
		var imported = service.read(export);

		assertEquals(2, imported.size());
		assertNull(imported.get(0).getId());
		assertEquals("Farm One", imported.get(0).getName());
		assertEquals("1234", imported.get(0).getCharacterServer());
		assertEquals(List.of("Farm", "SVS"), imported.get(0).getTags());
		assertEquals(1, imported.get(0).getConfigs().size());
		assertEquals("GATHER_TASK_BOOL", imported.get(0).getConfigs().get(0).getConfigurationName());
	}

	@Test
	void duplicateUsesNewNameAndCopiesSettingsAndTags() throws Exception {
		ProfileTransferService service = new ProfileTransferService();

		var duplicate = service.duplicate(profile(7L, "Farm One"), "Farm One Copy");

		assertNull(duplicate.getId());
		assertEquals("Farm One Copy", duplicate.getName());
		assertEquals(List.of("Farm", "SVS"), duplicate.getTags());
		assertEquals("true", duplicate.getConfigs().get(0).getValue());
	}

	@Test
	void rejectsUnknownConfigurationsAndInvalidTypedValues() throws Exception {
		ProfileTransferService service = new ProfileTransferService();
		Path unknown = tempDirectory.resolve("unknown.json");
		Files.writeString(unknown, """
				{"formatVersion":1,"profiles":[{"name":"Bad","configs":[{"name":"NOT_A_KEY","value":"true"}]}]}
				""");
		Path invalid = tempDirectory.resolve("invalid.json");
		Files.writeString(invalid, """
				{"formatVersion":1,"profiles":[{"name":"Bad","configs":[{"name":"GATHER_COAL_BOOL","value":"perhaps"}]}]}
				""");

		assertThrows(java.io.IOException.class, () -> service.read(unknown));
		assertThrows(java.io.IOException.class, () -> service.read(invalid));
	}

	@Test
	void rejectsFilesLargerThanFiveMegabytes() throws Exception {
		Path oversized = tempDirectory.resolve("oversized.json");
		Files.write(oversized, new byte[5 * 1024 * 1024 + 1]);
		assertThrows(java.io.IOException.class, () -> new ProfileTransferService().read(oversized));
	}

	private ProfileAux profile(Long id, String name) {
		ProfileAux profile = new ProfileAux(id, name, "3", true, 10L, "NOT RUNNING", 30L,
				"98765", "Chief", "NERD", "1234");
		profile.getConfigs().add(new ConfigAux("GATHER_TASK_BOOL", "true"));
		profile.setTags(List.of("Farm", "SVS"));
		return profile;
	}
}
