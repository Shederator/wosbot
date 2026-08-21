package dev.frostguard.app.bootstrap;

import dev.frostguard.app.bootstrap.WindowBoundsPolicy.WindowBounds;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowBoundsPolicyTest {

    @Test
    void clampsOversizedSavedBoundsToTheMonitorWorkArea() {
        WindowBounds saved = new WindowBounds(-7, -7, 2575, 1407);

        WindowBounds recovered = WindowBoundsPolicy.recover(saved,
                List.of(new Rectangle2D(0, 0, 2560, 1392)), 100).orElseThrow();

        assertEquals(new WindowBounds(0, 0, 2560, 1392), recovered);
    }

    @Test
    void preservesBoundsOnTheBestMatchingSecondaryMonitor() {
        WindowBounds saved = new WindowBounds(-1800, 700, 960, 560);
        List<Rectangle2D> screens = List.of(
                new Rectangle2D(0, 0, 2560, 1392),
                new Rectangle2D(-1920, 550, 1920, 1032));

        WindowBounds recovered = WindowBoundsPolicy.recover(saved, screens, 100).orElseThrow();

        assertEquals(saved, recovered);
    }

    @Test
    void rejectsBoundsThatAreNoLongerRecoverableAfterTopologyChange() {
        WindowBounds disconnected = new WindowBounds(3840, 537, 960, 560);

        assertTrue(WindowBoundsPolicy.recover(disconnected,
                List.of(new Rectangle2D(0, 0, 2560, 1392)), 100).isEmpty());
    }
}
