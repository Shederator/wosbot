package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearRallyScannerTest {

    @Test
    void parsesBearCardsFromOneObservationAndDropsNonBearRows() {
        ImageSearchResultData bearButton = ImageSearchResultData.hit(620, 500, 96, 40, 40);
        ImageSearchResultData otherButton = ImageSearchResultData.hit(620, 800, 96, 40, 40);
        ImageSearchResultData bearIcon = ImageSearchResultData.hit(100, 460, 90, 40, 40);
        BearRallyScanner scanner = new BearRallyScanner(
                () -> List.of(otherButton, bearButton),
                () -> List.of(bearIcon),
                (topLeft, bottomRight) -> textFor(topLeft, 480));

        List<BearRallyCandidate> candidates = scanner.scanCandidates(
                Instant.parse("2026-09-17T14:00:00Z"));

        assertEquals(1, candidates.size());
        BearRallyCandidate candidate = candidates.getFirst();
        assertEquals(4, candidate.currentMembers());
        assertEquals(15, candidate.maxMembers());
        assertEquals(420_000, candidate.currentTroops());
        assertEquals(500_000, candidate.maxTroops());
        assertEquals(Duration.ofMinutes(4).plusSeconds(20), candidate.countdown());
        assertTrue(candidate.bearTarget());
    }

    @Test
    void invalidOrIncompleteOcrFailsClosed() {
        ImageSearchResultData button = ImageSearchResultData.hit(620, 500, 96, 40, 40);
        ImageSearchResultData bearIcon = ImageSearchResultData.hit(100, 460, 90, 40, 40);
        BearRallyScanner scanner = new BearRallyScanner(
                () -> List.of(button),
                () -> List.of(bearIcon),
                (topLeft, bottomRight) -> "unreadable");

        assertTrue(scanner.scanCandidates(Instant.now()).isEmpty());
    }

    @Test
    void reportsOcrFailureInsteadOfCallingAVisibleBearRowDrained() {
        ImageSearchResultData button = ImageSearchResultData.hit(620, 500, 96, 40, 40);
        ImageSearchResultData bearIcon = ImageSearchResultData.hit(100, 460, 90, 40, 40);
        BearRallyScanner scanner = new BearRallyScanner(
                () -> List.of(button),
                () -> List.of(bearIcon),
                (topLeft, bottomRight) -> "unreadable");

        BearRallyScanner.ScanResult result = scanner.scan(Instant.now());

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.ocrFailure());
    }

    private static String textFor(PointData topLeft, int anchorY) {
        if (topLeft.getX() == 626) {
            return "4/15";
        }
        if (topLeft.getX() == 284) {
            return "420K/500K";
        }
        if (topLeft.getX() == 571) {
            return "04:20";
        }
        throw new AssertionError("Unexpected OCR region at " + topLeft + " anchor=" + anchorY);
    }
}
