package dev.frostguard.engine.platform;

import dev.frostguard.engine.emulator.EmulatorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformRuntimeTest {

    @Test
    void resolvesWindowsExecutablesForWindowsPlatforms() {
        assertEquals("adb.exe", PlatformRuntime.executableName("adb.exe", "adb", "Windows 11"));
        assertEquals("MuMuManager.exe", PlatformRuntime.executableName("MuMuManager.exe", "MuMuManager", "Windows 11"));
    }

    @Test
    void resolvesUnixExecutablesForMacOs() {
        assertEquals("adb", PlatformRuntime.executableName("adb.exe", "adb", "Mac OS X"));
        assertEquals("MuMuManager", PlatformRuntime.executableName("MuMuManager.exe", "MuMuManager", "Mac OS X"));
    }

    @Test
    void detectsMacOsAndWindowsFromOsName() {
        assertTrue(PlatformRuntime.isMacOs("Mac OS X"));
        assertTrue(PlatformRuntime.isWindows("Windows 11"));
    }

    @Test
    void exposesPlatformAwareEmulatorExecutableNames() {
        assertEquals("MuMuManager", EmulatorType.MUMU.getExecutableName("Mac OS X"));
        assertEquals("MuMuManager.exe", EmulatorType.MUMU.getExecutableName("Windows 11"));
        assertEquals("ldconsole", EmulatorType.LDPLAYER.getExecutableName("Mac OS X"));
    }
}
