package dev.frostguard.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.Config;
import dev.frostguard.data.entity.Profile;

class ConfigRepositoryWriteTest {

	private Path database;
	private DataStore store;
	private ConfigRepository configs;
	private ProfileRepository profiles;
	private Long profileId;

	@BeforeEach
	void setUp() throws Exception {
		database = Files.createTempFile("frostguard-config-test-", ".db");
		store = DataStore.openIsolated(Map.of(
				"jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
				"hibernate.hbm2ddl.auto", "create-drop"));
		configs = new ConfigRepository(store);
		profiles = new ProfileRepository(store);
		profileId = profiles.createAccountAggregate(Profile.create("Config Test", "1"), List.of(
				new ConfigData(null, "GATHER_COAL_BOOL", "false"),
				new ConfigData(null, "GATHER_MEAT_BOOL", "false")), List.of());
		assertNotNull(profileId);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (store != null) store.close();
		Files.deleteIfExists(database);
	}

	@Test
	void staleSnapshotsUpdatingDifferentKeysDoNotOverwriteEachOther() {
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true"));
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_MEAT_BOOL", "true"));

		Map<String, String> saved = savedSettings();
		assertEquals("true", saved.get("GATHER_COAL_BOOL"));
		assertEquals("true", saved.get("GATHER_MEAT_BOOL"));
	}

	@Test
	void orderedRapidWritesKeepLastCommittedValue() {
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true"));
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "false"));
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true"));

		assertEquals("true", savedSettings().get("GATHER_COAL_BOOL"));
	}

	@Test
	void staleMetadataSaveDoesNotWriteCachedConfigurationBack() {
		var staleDescriptor = profiles.getAccountWithSettingsById(profileId);
		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true"));

		staleDescriptor.setName("Renamed Profile");
		assertTrue(profiles.updateAccountMetadata(staleDescriptor));

		assertEquals("Renamed Profile", profiles.getAccountWithSettingsById(profileId).getName());
		assertEquals("true", savedSettings().get("GATHER_COAL_BOOL"));
	}

	@Test
	void simultaneousWritesToDifferentKeysBothCommit() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<Boolean> uiWrite = executor.submit(() -> {
				start.await();
				return configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true");
			});
			Future<Boolean> runtimeWrite = executor.submit(() -> {
				start.await();
				return configs.writeProfileSetting(profileId, "GATHER_MEAT_BOOL", "true");
			});
			start.countDown();
			assertTrue(uiWrite.get());
			assertTrue(runtimeWrite.get());
		}

		Map<String, String> saved = savedSettings();
		assertEquals("true", saved.get("GATHER_COAL_BOOL"));
		assertEquals("true", saved.get("GATHER_MEAT_BOOL"));
	}

	@Test
	void writeCollapsesLegacyDuplicateRowsForTheSameKey() {
		store.runInTransaction(em -> {
			Profile profile = em.find(Profile.class, profileId);
			var template = em.find(dev.frostguard.data.entity.ConfigTemplate.class,
					TpConfigEnum.PROFILE_CONFIG.getId());
			em.persist(new Config(profile, template, "gather_coal_bool", "stale"));
		});

		assertTrue(configs.writeProfileSetting(profileId, "GATHER_COAL_BOOL", "true"));
		List<Config> matching = configs.getAccountSettings(profileId).stream()
				.filter(row -> "GATHER_COAL_BOOL".equalsIgnoreCase(row.getIdentifier()))
				.toList();
		assertEquals(1, matching.size());
		assertEquals("true", matching.getFirst().getContent());
	}

	private Map<String, String> savedSettings() {
		return configs.getAccountSettings(profileId).stream()
				.collect(java.util.stream.Collectors.toMap(
						Config::getIdentifier, Config::getContent, (first, second) -> second));
	}
}
