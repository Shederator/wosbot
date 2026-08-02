package dev.frostguard.distribution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

class LocalInstallationDeployerTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesManagedFilesAndPreservesDataByteForByte() throws Exception {
        Path staging = staging("new");
        Path installation = installation("old");
        byte[] persistent = {0, 1, 2, 3};
        Files.write(installation.resolve("data/private.db"), persistent);

        LocalInstallationDeployer.deploy(staging, installation);

        assertEquals("new", Files.readString(installation.resolve("app/version.txt")));
        assertArrayEquals(persistent, Files.readAllBytes(installation.resolve("data/private.db")));
        assertFalse(Files.exists(installation.resolve("data/README.txt")));
    }

    @Test
    void restoresCompletePreviousInstallationWhenReplacementMoveFails() throws Exception {
        Path staging = staging("new");
        Path installation = installation("old");
        Files.writeString(installation.resolve("data/private.txt"), "keep");
        AtomicInteger moves = new AtomicInteger();

        assertThrows(IOException.class, () -> LocalInstallationDeployer.deploy(staging, installation,
                (source, target) -> {
                    if (moves.incrementAndGet() == 4) throw new IOException("simulated locked file");
                    Files.createDirectories(target.getParent());
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                }));

        assertEquals("old", Files.readString(installation.resolve("app/version.txt")));
        assertEquals("old", Files.readString(installation.resolve("build-info.json")));
        assertEquals("keep", Files.readString(installation.resolve("data/private.txt")));
    }

    private Path staging(String version) throws Exception {
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging.resolve("app"));
        Files.createDirectories(staging.resolve("data"));
        Files.writeString(staging.resolve("app/version.txt"), version);
        Files.writeString(staging.resolve("build-info.json"), version);
        Files.writeString(staging.resolve("Frostguard.bat"), version);
        Files.writeString(staging.resolve("data/README.txt"), "must not deploy");
        return staging;
    }

    private Path installation(String version) throws Exception {
        Path installation = tempDir.resolve(".frostguard");
        Files.createDirectories(installation.resolve("app"));
        Files.createDirectories(installation.resolve("data"));
        Files.writeString(installation.resolve("app/version.txt"), version);
        Files.writeString(installation.resolve("build-info.json"), version);
        Files.writeString(installation.resolve("Frostguard.bat"), version);
        return installation;
    }
}
