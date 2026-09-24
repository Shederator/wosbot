package dev.frostguard.app.shared;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileOpenerTest {

    @Test
    void windowsStartsExplorerWithoutWaitingForItsExitCode() {
        LocalFileOpener.Launch launch = LocalFileOpener.launchFor("Windows 11", Path.of("C:\\workspace\\logs"));

        assertEquals("explorer.exe", launch.command().getFirst());
        assertEquals(Path.of("C:\\workspace\\logs").toString(), launch.command().get(1));
        assertFalse(launch.waitForExit());
    }

    @Test
    void linuxUsesXdgOpenAndMacosUsesOpen() {
        LocalFileOpener.Launch linux = LocalFileOpener.launchFor("Linux", Path.of("/tmp/logs"));
        LocalFileOpener.Launch mac = LocalFileOpener.launchFor("Mac OS X", Path.of("/tmp/logs"));

        assertEquals("xdg-open", linux.command().getFirst());
        assertTrue(linux.waitForExit());
        assertEquals("open", mac.command().getFirst());
        assertTrue(mac.waitForExit());
    }
}
