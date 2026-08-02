package dev.frostguard.data.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.runtime.FrostguardPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class LegacyDataMigratorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T12:34:56Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty(FrostguardPaths.HOME_PROPERTY);
        System.clearProperty(FrostguardPaths.DATA_PROPERTY);
        System.clearProperty(LegacyDataMigrator.SOURCE_PROPERTY);
    }

    @Test
    void migratesSqliteGroupAndMutableFilesAfterCreatingBackup() throws Exception {
        Path repository = tempDir.resolve("repo");
        Path source = repository.resolve("fg-app/target");
        byte[] database = {1, 2, 3};
        Files.createDirectories(source.resolve("custom_tasks"));
        Files.write(source.resolve("database.db"), database);
        Files.write(source.resolve("database.db-wal"), new byte[]{4});
        Files.write(source.resolve("database.db-shm"), new byte[]{5});
        Files.writeString(source.resolve("custom_tasks/example.java"), "class Example {}");
        FrostguardPaths paths = paths(repository);

        LegacyDataMigrator.MigrationResult result = LegacyDataMigrator.migrate(paths, CLOCK);

        assertTrue(result.migrated());
        assertArrayEquals(database, Files.readAllBytes(paths.dataHome().resolve("frostguard.db")));
        assertTrue(Files.exists(paths.dataHome().resolve("frostguard.db-wal")));
        assertTrue(Files.exists(paths.dataHome().resolve("frostguard.db-shm")));
        assertTrue(Files.exists(paths.customTasks().resolve("example.java")));
        assertTrue(Files.exists(result.backup().resolve("database.db")));
        assertTrue(Files.exists(paths.dataHome().resolve("config/legacy-migration.properties")));
        assertTrue(Files.exists(source.resolve("database.db")), "compatibility source remains untouched");
    }

    @Test
    void refusesMultipleSourcesWithoutChangingData() throws Exception {
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(repository.resolve("fg-app/target"));
        Files.write(repository.resolve("database.db"), new byte[]{1});
        Files.write(repository.resolve("fg-app/target/database.db"), new byte[]{2});
        FrostguardPaths paths = paths(repository);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> LegacyDataMigrator.migrate(paths, CLOCK));

        assertTrue(error.getMessage().contains(LegacyDataMigrator.SOURCE_PROPERTY));
        assertFalse(Files.exists(paths.dataHome().resolve("frostguard.db")));
        assertFalse(Files.exists(paths.dataHome().resolve("migration-backups")));
    }

    @Test
    void explicitSelectionResolvesConflictingSources() throws Exception {
        Path repository = tempDir.resolve("repo");
        Path selected = repository.resolve("fg-app/target");
        Files.createDirectories(selected);
        Files.write(repository.resolve("database.db"), new byte[]{1});
        Files.write(selected.resolve("database.db"), new byte[]{2});
        System.setProperty(LegacyDataMigrator.SOURCE_PROPERTY, selected.toString());
        FrostguardPaths paths = paths(repository);

        LegacyDataMigrator.MigrationResult result = LegacyDataMigrator.migrate(paths, CLOCK);

        assertTrue(result.migrated());
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(paths.dataHome().resolve("frostguard.db")));
    }

    private FrostguardPaths paths(Path repository) throws Exception {
        Files.createDirectories(repository.resolve(".frostguard"));
        System.setProperty(FrostguardPaths.HOME_PROPERTY, repository.resolve(".frostguard").toString());
        System.setProperty(FrostguardPaths.DATA_PROPERTY, repository.resolve(".frostguard/data").toString());
        return FrostguardPaths.resolve(LegacyDataMigratorTest.class);
    }
}
