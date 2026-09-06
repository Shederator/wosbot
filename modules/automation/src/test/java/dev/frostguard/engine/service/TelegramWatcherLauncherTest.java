package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TelegramWatcherLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsDetectedDesktopJarWhenConfiguredPathIsBlank() throws Exception {
        Path config = tempDir.resolve("watcher/telegram-watcher.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "token=secret\nchatId=123\nbotJarPath=\nlocalPort=8765\n");
        Path desktopJar = Files.createFile(tempDir.resolve("frostguard-desktop-3.0.2.jar"));

        TelegramWatcherLauncher.repairBotJarPath(config, Optional.of(desktopJar));

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        }
        assertEquals(desktopJar.toString(), properties.getProperty("botJarPath"));
        assertEquals("secret", properties.getProperty("token"));
        assertEquals("123", properties.getProperty("chatId"));
        assertEquals("8765", properties.getProperty("localPort"));
    }

    @Test
    void preservesExistingValidDesktopJar() throws Exception {
        Path configuredJar = Files.createFile(tempDir.resolve("configured.jar"));
        Path detectedJar = Files.createFile(tempDir.resolve("detected.jar"));
        Path config = tempDir.resolve("telegram-watcher.properties");
        Properties configuredProperties = new Properties();
        configuredProperties.setProperty("botJarPath", configuredJar.toString());
        try (OutputStream output = Files.newOutputStream(config)) {
            configuredProperties.store(output, "test");
        }

        TelegramWatcherLauncher.repairBotJarPath(config, Optional.of(detectedJar));

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        }
        assertEquals(configuredJar.toString(), properties.getProperty("botJarPath"));
    }
}
