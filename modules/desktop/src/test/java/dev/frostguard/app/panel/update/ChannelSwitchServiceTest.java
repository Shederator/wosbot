package dev.frostguard.app.panel.update;

import dev.frostguard.api.runtime.RuntimeChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelSwitchServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void launchesAnExplicitlyConfiguredInstalledChannel() throws Exception {
        Path nightly = Files.createFile(tempDir.resolve("Frostguard Nightly.exe"));
        Map<String, String> properties = Map.of("frostguard.channel.launcher.nightly", nightly.toString());
        AtomicReference<Path> launched = new AtomicReference<>();
        ChannelSwitchService service = new ChannelSwitchService(properties::get, () -> null,
                launched::set, page -> {
                    throw new AssertionError("release page must not open");
                });

        assertEquals(ChannelSwitchService.Result.LAUNCHED_INSTALLED,
                service.open(RuntimeChannel.NIGHTLY));
        assertEquals(nightly.toAbsolutePath(), launched.get());
    }

    @Test
    void opensThePinnedReleasePageWhenTheOtherChannelIsNotInstalled() throws Exception {
        Map<String, String> properties = Map.of(
                "frostguard.channel.page.stable", "https://downloads.example.com/stable");
        AtomicReference<URI> opened = new AtomicReference<>();
        ChannelSwitchService service = new ChannelSwitchService(properties::get, () -> null,
                executable -> {
                    throw new AssertionError("launcher must not run");
                }, opened::set);

        assertEquals(ChannelSwitchService.Result.OPENED_RELEASE_PAGE,
                service.open(RuntimeChannel.STABLE));
        assertEquals(URI.create("https://downloads.example.com/stable"), opened.get());
    }

    @Test
    void rejectsAnUntrustedReleasePageScheme() {
        Map<String, String> properties = new HashMap<>();
        properties.put("frostguard.channel.page.nightly", "http://downloads.example.com/nightly");
        ChannelSwitchService service = new ChannelSwitchService(properties::get, () -> null,
                executable -> { }, page -> { });

        assertThrows(IOException.class, () -> service.open(RuntimeChannel.NIGHTLY));
    }

    @Test
    void opensThePermanentNightlyPageByDefault() throws Exception {
        AtomicReference<URI> opened = new AtomicReference<>();
        ChannelSwitchService service = new ChannelSwitchService(property -> null, () -> null,
                executable -> {
                    throw new AssertionError("launcher must not run");
                }, opened::set);

        assertEquals(ChannelSwitchService.Result.OPENED_RELEASE_PAGE,
                service.open(RuntimeChannel.NIGHTLY));
        assertEquals(URI.create("https://github.com/Shederator/wosbot/releases/tag/nightly"),
                opened.get());
    }
}
