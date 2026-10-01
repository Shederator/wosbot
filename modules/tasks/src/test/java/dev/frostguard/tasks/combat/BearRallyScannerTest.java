package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.ImageSearchResultData;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BearRallyScannerTest {

    @Test
    void returnsOnlyBearPlusControlsInStrictTopToBottomOrder() {
        ImageSearchResultData lowerBearButton = ImageSearchResultData.hit(620, 800, 96, 40, 40);
        ImageSearchResultData nonBearButton = ImageSearchResultData.hit(620, 500, 96, 40, 40);
        ImageSearchResultData upperBearButton = ImageSearchResultData.hit(620, 300, 96, 40, 40);
        BearRallyScanner scanner = new BearRallyScanner(
                () -> List.of(lowerBearButton, nonBearButton, upperBearButton),
                () -> List.of(
                        ImageSearchResultData.hit(100, 270, 90, 40, 40),
                        ImageSearchResultData.hit(100, 770, 90, 40, 40)),
                (topLeft, bottomRight) -> topLeft.getY() < 400 ? " Leader A " : "Leader B");

        List<BearRallyScanner.RallyRow> controls = scanner.scanJoinControls();

        assertEquals(List.of(270, 770), controls.stream()
                .map(BearRallyScanner.RallyRow::rowY).toList());
        assertEquals(List.of("Leader A", "Leader B"), controls.stream()
                .map(BearRallyScanner.RallyRow::leaderText).toList());
    }

    @Test
    void exposesNonJoinableBearRowsForZeroPlusScrollProof() {
        BearRallyScanner scanner = new BearRallyScanner(
                List::of,
                () -> List.of(
                        ImageSearchResultData.hit(100, 270, 90, 40, 40),
                        ImageSearchResultData.hit(100, 770, 90, 40, 40)),
                (topLeft, bottomRight) -> topLeft.getY() < 400 ? "A" : "B");

        List<BearRallyScanner.RallyRow> rows = scanner.scanRows();

        assertEquals(List.of(270, 770), rows.stream()
                .map(BearRallyScanner.RallyRow::rowY).toList());
        assertEquals(List.of(false, false), rows.stream()
                .map(BearRallyScanner.RallyRow::joinable).toList());
    }
}
