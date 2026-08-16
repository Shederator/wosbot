package dev.frostguard.engine.emulator.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BlueStacksAirEmulatorInstanceTest {

    @Test
    void mapsBlankIdentifierToBasePort() {
        assertEquals("127.0.0.1:5555", BlueStacksAirEmulatorInstance.mapDeviceSerial("", "127.0.0.1", 5555));
        assertEquals("127.0.0.1:5555", BlueStacksAirEmulatorInstance.mapDeviceSerial(null, "127.0.0.1", 5555));
    }

    @Test
    void mapsExplicitHostPortAsIs() {
        assertEquals("10.0.0.2:5615", BlueStacksAirEmulatorInstance.mapDeviceSerial("10.0.0.2:5615", "127.0.0.1", 5555));
    }

    @Test
    void mapsAbsolutePortsDirectly() {
        assertEquals("127.0.0.1:5565", BlueStacksAirEmulatorInstance.mapDeviceSerial("5565", "127.0.0.1", 5555));
    }

    @Test
    void mapsSmallIndexesRelativeToBasePort() {
        assertEquals("127.0.0.1:5555", BlueStacksAirEmulatorInstance.mapDeviceSerial("0", "127.0.0.1", 5555));
        assertEquals("127.0.0.1:5556", BlueStacksAirEmulatorInstance.mapDeviceSerial("1", "127.0.0.1", 5555));
        assertEquals("127.0.0.1:5557", BlueStacksAirEmulatorInstance.mapDeviceSerial("2", "127.0.0.1", 5555));
    }
}
