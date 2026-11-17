package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearRallyScannerTest {

    @Test
    void returnsEmptyListWhenZeroJoinButtonsFound() {
        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> List.of(),
                (tl, br) -> null
        );

        List<BearRallyCandidate> candidates = scanner.scanCandidates(Instant.now());
        assertTrue(candidates.isEmpty());
    }

    @Test
    void parsesAndSortsCandidatesFromTopToBottom() {
        ImageSearchResultData btnLower = ImageSearchResultData.hit(600, 600, 95.0);
        ImageSearchResultData btnUpper = ImageSearchResultData.hit(600, 300, 95.0);

        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> List.of(btnLower, btnUpper),
                (tl, br) -> {
                    // Extract based on region
                    if (tl.getY() < 400) {
                        // Upper card
                        if (tl.getX() == 281) return "PlayerOne";
                        if (tl.getX() == 626) return "3/6";
                        if (tl.getX() == 284) return "100.0K/200.0K";
                        if (tl.getX() == 571) return "04:30";
                    } else {
                        // Lower card
                        if (tl.getX() == 281) return "PlayerTwo";
                        if (tl.getX() == 626) return "1/6";
                        if (tl.getX() == 284) return "50.0K/150.0K";
                        if (tl.getX() == 571) return "02:15";
                    }
                    return null;
                }
        );

        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        List<BearRallyCandidate> candidates = scanner.scanCandidates(now);

        assertEquals(2, candidates.size());

        // Verify sorted from top to bottom
        BearRallyCandidate first = candidates.get(0);
        assertEquals("PlayerOne", first.hostName());
        assertEquals(3, first.currentMembers());
        assertEquals(6, first.maxMembers());
        assertEquals(200_000L, first.rallyCapacity());
        assertEquals(100_000L, first.remainingCapacity());
        assertEquals(100_000L, first.currentTroops());
        assertEquals(Duration.ofSeconds(270), first.countdown());

        BearRallyCandidate second = candidates.get(1);
        assertEquals("PlayerTwo", second.hostName());
        assertEquals(1, second.currentMembers());
        assertEquals(6, second.maxMembers());
        assertEquals(150_000L, second.rallyCapacity());
        assertEquals(50_000L, second.remainingCapacity());
        assertEquals(100_000L, second.currentTroops());
        assertEquals(Duration.ofSeconds(135), second.countdown());
    }

    @Test
    void requestsMultipleMatchesAndDropsDuplicateOrInvalidHits() {
        ImageSearchResultData first = ImageSearchResultData.hit(600, 300, 95.0, 20, 40);
        ImageSearchResultData duplicate = ImageSearchResultData.hit(604, 304, 94.0, 20, 40);
        ImageSearchResultData invalid = ImageSearchResultData.hit(600, 50, 96.0, 20, 40);

        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> {
                    assertEquals(8, config.getMaxResults());
                    return List.of(duplicate, invalid, first, ImageSearchResultData.miss());
                },
                (tl, br) -> {
                    if (tl.getX() == 281) return "Host";
                    if (tl.getX() == 626) return "1/6";
                    if (tl.getX() == 284) return "50K/100K";
                    return "04:00";
                });

        List<BearRallyCandidate> candidates = scanner.scanCandidates(
                Instant.parse("2026-08-19T10:00:00Z"));

        assertEquals(1, candidates.size());
        assertEquals(first.getMatchedArea(), candidates.get(0).joinButtonArea());
    }

    @Test
    void anchorsOcrRegionsFromMatchedTemplateTopEdge() {
        ImageSearchResultData button = ImageSearchResultData.hit(600, 300, 95.0, 20, 40);
        List<PointData> observedTopLefts = new ArrayList<>();
        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> List.of(button),
                (tl, br) -> {
                    observedTopLefts.add(tl);
                    if (tl.getX() == 281) return "Host";
                    if (tl.getX() == 626) return "1/6";
                    if (tl.getX() == 284) return "50K/100K";
                    return "04:00";
                });

        scanner.scanCandidates(Instant.parse("2026-08-19T10:00:00Z"));

        assertEquals(280 + dev.frostguard.engine.nav.CommonGameAreas.BEAR_TRAP_INITIATOR_DY1,
                observedTopLefts.get(0).getY());
    }

    @Test
    void aFreshScanDoesNotRetainCandidatesThatDisappeared() {
        AtomicInteger scans = new AtomicInteger();
        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> scans.getAndIncrement() == 0
                        ? List.of(ImageSearchResultData.hit(600, 300, 95.0, 20, 40))
                        : List.of(),
                BearRallyScannerTest::validCardText);

        assertEquals(1, scanner.scanCandidates(Instant.now()).size());
        assertTrue(scanner.scanCandidates(Instant.now()).isEmpty());
    }

    @Test
    void aFreshScanUsesTheReplacementCardsCurrentPosition() {
        AtomicInteger scans = new AtomicInteger();
        BearRallyScanner scanner = new BearRallyScanner(
                (template, config) -> scans.getAndIncrement() == 0
                        ? List.of(
                                ImageSearchResultData.hit(600, 300, 95.0, 20, 40),
                                ImageSearchResultData.hit(600, 500, 95.0, 20, 40))
                        : List.of(ImageSearchResultData.hit(600, 300, 95.0, 20, 40)),
                BearRallyScannerTest::validCardText);

        List<BearRallyCandidate> staleScan = scanner.scanCandidates(Instant.now());
        List<BearRallyCandidate> freshScan = scanner.scanCandidates(Instant.now());

        assertEquals(2, staleScan.size());
        assertEquals(1, freshScan.size());
        assertEquals(300, freshScan.get(0).joinButtonPoint().getY());
    }

    private static String validCardText(PointData topLeft, PointData bottomRight) {
        if (topLeft.getX() == 281) return "Host";
        if (topLeft.getX() == 626) return "1/6";
        if (topLeft.getX() == 284) return "50K/100K";
        return "04:00";
    }
}
