package dev.frostguard.vision.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TesseractOcrProviderTest {

    @Test
    void selectsWindowsDebugSinkOnWindows() {
        assertEquals("quiet", TesseractOcrProvider.quietConfigName("Windows 11"));
    }

    @Test
    void selectsUnixDebugSinkOnLinuxAndMacOs() {
        assertEquals("quiet-unix", TesseractOcrProvider.quietConfigName("Linux"));
        assertEquals("quiet-unix", TesseractOcrProvider.quietConfigName("Mac OS X"));
    }
}
