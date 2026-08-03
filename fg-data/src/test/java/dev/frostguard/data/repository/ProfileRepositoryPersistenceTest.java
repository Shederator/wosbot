package dev.frostguard.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Profile;

class ProfileRepositoryPersistenceTest {

	private Path database;
	private DataStore store;
	private ProfileRepository repository;

	@BeforeEach
	void setUp() throws Exception {
		database = Files.createTempFile("frostguard-profile-test-", ".db");
		store = DataStore.openIsolated(Map.of(
				"jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
				"hibernate.hbm2ddl.auto", "create-drop"));
		repository = new ProfileRepository(store);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (store != null) store.close();
		Files.deleteIfExists(database);
	}

	@Test
	void persistsRenamesRecolorsAndDeletesTagsInSQLite() {
		Profile profile = Profile.create("Tagged", "1");
		Long id = repository.createAccountAggregate(profile, List.of(), List.of("svs"));
		assertNotNull(id);
		assertEquals(List.of("svs"), repository.getAccountWithSettingsById(id).getTags());

		assertTrue(repository.updateTag("svs", "BiA", "#ff8800"));
		assertEquals("#ff8800", repository.getTags().getFirst().color());
		assertEquals(List.of("BiA"), repository.getAccountWithSettingsById(id).getTags());

		assertTrue(repository.deleteTag("BiA"));
		assertTrue(repository.getTags().isEmpty());
		assertTrue(repository.getAccountWithSettingsById(id).getTags().isEmpty());
	}

	@Test
	void persistsDuplicateAndImportedAggregatesWithIndependentSettings() {
		Profile source = Profile.create("Source", "1");
		Long sourceId = repository.createAccountAggregate(source,
				List.of(new ConfigData(null, "GATHER_COAL_BOOL", "true")), List.of("farm"));
		Profile duplicate = Profile.create("Source Copy", "2");
		Long duplicateId = repository.createAccountAggregate(duplicate,
				List.of(new ConfigData(null, "GATHER_COAL_BOOL", "true")), List.of("farm"));

		assertNotNull(sourceId);
		assertNotNull(duplicateId);
		assertFalse(sourceId.equals(duplicateId));
		AccountDescriptor loaded = repository.getAccountWithSettingsById(duplicateId);
		assertEquals("Source Copy", loaded.getName());
		assertEquals("true", loaded.getConfigs().getFirst().getValue());
		assertEquals(List.of("farm"), loaded.getTags());
	}

	@Test
	void rollsBackWholeAggregateWhenProfileInsertFails() {
		Profile invalid = Profile.create(null, "1");
		invalid.setLabel(null);
		Long id = repository.createAccountAggregate(invalid,
				List.of(new ConfigData(null, "GATHER_COAL_BOOL", "true")), List.of("rollback-tag"));

		assertNull(id);
		assertFalse(repository.getTagNames().contains("rollback-tag"));
		assertEquals(0, store.executeQuery("SELECT c FROM Config c WHERE c.identifier = :key",
				dev.frostguard.data.entity.Config.class, Map.of("key", "GATHER_COAL_BOOL")).size());
	}
}
