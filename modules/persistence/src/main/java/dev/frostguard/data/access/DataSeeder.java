package dev.frostguard.data.access;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.TpConfigEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.data.entity.ConfigTemplate;
import dev.frostguard.data.entity.DailyTaskTemplate;

/**
 * Declarative seed definitions for Frostguard reference data.
 * Ensures all routine templates and configuration templates
 * exist in the database. Idempotent and safe to invoke on every startup.
 */
final class DataSeeder {

	private static final Logger LOG = LoggerFactory.getLogger(DataSeeder.class);

	private record RoutineSeed(TpDailyTaskEnum type) {
		DailyTaskTemplate toEntity() { return DailyTaskTemplate.fromRoutineType(type); }
		int lookupId() { return type.getId(); }
	}

	private record SettingSeed(TpConfigEnum key) {
		ConfigTemplate toEntity() { return ConfigTemplate.fromDefinition(key); }
		int lookupId() { return key.getId(); }
	}

	private static final List<RoutineSeed> ROUTINE_CATALOG =
		Arrays.stream(TpDailyTaskEnum.values()).map(RoutineSeed::new).toList();

	private static final List<SettingSeed> SETTING_CATALOG =
		Arrays.stream(TpConfigEnum.values()).map(SettingSeed::new).toList();

	static void populate(DataStore store) {
		int routinesAdded = seedRoutineDefinitions(store);
		int settingsAdded = seedSettingDefinitions(store);

		if (routinesAdded + settingsAdded > 0) {
			LOG.info("Seeded {} routine templates and {} setting templates",
				routinesAdded, settingsAdded);
		}
	}

	private static int seedRoutineDefinitions(DataStore store) {
		int count = 0;
		for (RoutineSeed seed : ROUTINE_CATALOG) {
			if (store.lookup(DailyTaskTemplate.class, seed.lookupId()) == null) {
				store.persist(seed.toEntity());
				count++;
			}
		}
		return count;
	}

	private static int seedSettingDefinitions(DataStore store) {
		int count = 0;
		for (SettingSeed seed : SETTING_CATALOG) {
			if (store.lookup(ConfigTemplate.class, seed.lookupId()) == null) {
				store.persist(seed.toEntity());
				count++;
			}
		}
		return count;
	}

	private DataSeeder() {}
}
