package dev.frostguard.engine.ranking.capture;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MuMuTcpdumpAnalyticsCaptureTest {

    @Test
    void selectsInterfaceFromDefaultRoute() throws IOException {
        String route = "1.1.1.1 via 10.0.2.2 dev wlan0 table wlan0 src 10.0.2.15 uid 0";

        assertEquals("wlan0", MuMuTcpdumpAnalyticsCapture.defaultRouteInterface(route));
    }

    @Test
    void rejectsRouteWithoutDevice() {
        assertThrows(IOException.class,
                () -> MuMuTcpdumpAnalyticsCapture.defaultRouteInterface("unreachable 1.1.1.1"));
    }
}
