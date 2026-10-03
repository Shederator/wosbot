package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Reuses OCR only within the exact observation, never across sampled frames. */
final class BearRallyFrameScan {
    private final Function<RawImageData, List<BearRallyScanner.RallyRow>> scanner;
    private BearFrameStream.Snapshot<RawImageData> observed;
    private List<BearRallyScanner.RallyRow> rows = List.of();

    BearRallyFrameScan(Function<RawImageData, List<BearRallyScanner.RallyRow>> scanner) {
        this.scanner = Objects.requireNonNull(scanner);
    }

    List<BearRallyScanner.RallyRow> rows(BearFrameStream.Snapshot<RawImageData> frame) {
        Objects.requireNonNull(frame);
        if (frame != observed) {
            rows = frame.screen() == BearNavigationPolicy.Screen.WAR_LIST
                    ? List.copyOf(scanner.apply(frame.frame())) : List.of();
            observed = frame;
        }
        // This is evidence reuse, not freshness authorization. The input boundary still checks age/sequence.
        return rows;
    }
}
