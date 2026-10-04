package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.convert.CompactGameNumberParser;
import dev.frostguard.vision.convert.GameTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BearRallyScanner {

    private static final Logger log = LoggerFactory.getLogger(BearRallyScanner.class);
    private static final int SCREEN_WIDTH = 720;
    private static final int SCREEN_HEIGHT = 1280;
    private static final int MAX_VISIBLE_RALLIES = 8;
    private static final int DUPLICATE_HIT_TOLERANCE = 8;

    @FunctionalInterface
    public interface PatternLocator {
        List<ImageSearchResultData> locate(TemplatesEnum template, TemplateSearchHelper.SearchConfig config);
    }

    @FunctionalInterface
    public interface TextExtractor {
        String extract(PointData topLeft, PointData bottomRight);
    }

    private final PatternLocator patternLocator;
    private final TextExtractor textExtractor;

    public BearRallyScanner(EmulatorController emulator, BotOcrEngine ocrEngine, TemplateSearchHelper searchHelper) {
        this.patternLocator = searchHelper != null ? searchHelper::locateAllPatterns : (t, c) -> List.of();
        BotOcrEngine frameOcr = ocrEngine != null ? ocrEngine.reusingLastFrame() : null;
        this.textExtractor = frameOcr != null ? (tl, br) -> {
            try {
                return frameOcr.extractText(null, tl, br);
            } catch (Exception e) {
                return null;
            }
        } : (tl, br) -> null;
    }

    public BearRallyScanner(PatternLocator patternLocator, TextExtractor textExtractor) {
        this.patternLocator = patternLocator != null ? patternLocator : (t, c) -> List.of();
        this.textExtractor = textExtractor != null ? textExtractor : (tl, br) -> null;
    }

    /**
     * Scans the current screen for bear rally candidates.
     * Extracts all joinable cards and parses their details from the same frame.
     */
    public List<BearRallyCandidate> scanCandidates(Instant now) {
        List<BearRallyCandidate> candidates = new ArrayList<>();

        // Use the default matching params but maybe higher confidence.
        List<ImageSearchResultData> joinButtons = patternLocator.locate(
                TemplatesEnum.BEAR_JOIN_PLUS_ICON,
                TemplateSearchHelper.SearchConfig.builder()
                        .withThreshold(80)
                        .withMaxResults(MAX_VISIBLE_RALLIES)
                        .build()
        );

        if (joinButtons == null || joinButtons.isEmpty()) {
            return candidates;
        }

        // Process from top to bottom
        List<ImageSearchResultData> sortedButtons = joinButtons.stream()
                .filter(BearRallyScanner::isUsableHit)
                .sorted(Comparator.comparingInt(img -> img.getPoint().getY()))
                .toList();

        PointData previousAcceptedPoint = null;
        for (ImageSearchResultData btn : sortedButtons) {
            PointData p = btn.getPoint();
            if (previousAcceptedPoint != null
                    && Math.abs(previousAcceptedPoint.getX() - p.getX()) <= DUPLICATE_HIT_TOLERANCE
                    && Math.abs(previousAcceptedPoint.getY() - p.getY()) <= DUPLICATE_HIT_TOLERANCE) {
                log.debug("Ignoring duplicate Bear join-button match at {}", p);
                continue;
            }
            // OCR offsets are calibrated from the template's top-left edge, while match points are centers.
            int anchorY = btn.hasMatchedArea() ? btn.getMatchedArea().topLeft().getY() : p.getY();
            PointData anchor = new PointData(p.getX(), anchorY);
            if (!hasValidOcrGeometry(anchorY)) {
                log.debug("Ignoring Bear join-button match with out-of-bounds OCR geometry at {}", p);
                continue;
            }
            previousAcceptedPoint = p;
            AreaData joinButtonArea = btn.hasMatchedArea()
                    ? btn.getMatchedArea()
                    : new AreaData(p, p);

            // Reconstruct full card AreaData for debugging
            AreaData cardArea = new AreaData(
                new PointData(0, Math.max(0, anchorY + CommonGameAreas.BEAR_TRAP_COUNTDOWN_DY1 - 10)),
                new PointData(SCREEN_WIDTH - 1, Math.min(SCREEN_HEIGHT - 1, anchorY + 60))
            );

            String hostName = readHostName(anchor);
            String membersRaw = readMembers(anchor);
            String capacityRaw = readCapacity(anchor);
            String countdownRaw = readCountdown(anchor);

            // Parse Capacity: "Remaining / Total"
            long remaining = -1, total = -1;
            if (capacityRaw != null && capacityRaw.contains("/")) {
                String[] parts = capacityRaw.split("/", 2);
                remaining = CompactGameNumberParser.parseCompactNumber(parts[0]);
                total = CompactGameNumberParser.parseCompactNumber(parts[1]);
            }

            // Parse Members: "Current / Max"
            int currentMem = -1, maxMem = -1;
            if (membersRaw != null && membersRaw.contains("/")) {
                String[] parts = membersRaw.split("/", 2);
                currentMem = (int) CompactGameNumberParser.parseCompactNumber(parts[0]);
                maxMem = (int) CompactGameNumberParser.parseCompactNumber(parts[1]);
            }

            // Parse Countdown
            Duration cd = GameTimeUtils.parseMinutesSeconds(countdownRaw);

            // Calculate current troops based on remaining and total.
            long currentTroops = -1;
            if (total != -1 && remaining != -1 && total >= remaining) {
                currentTroops = total - remaining;
            }

            BearRallyCandidate candidate = new BearRallyCandidate(
                p, joinButtonArea, cardArea, hostName, currentMem, maxMem,
                currentTroops, total, remaining, cd, now, true
            );

            log.info("Scanned Bear candidate at y={}: members={}/{}, capacity={}, remaining={}, countdown={}s",
                    p.getY(), currentMem, maxMem, total, remaining,
                    cd == null ? -1 : cd.getSeconds());
            candidates.add(candidate);
        }

        return candidates;
    }

    private static boolean isUsableHit(ImageSearchResultData hit) {
        return hit != null && hit.isFound() && hit.getPoint() != null;
    }

    private static boolean hasValidOcrGeometry(int anchorY) {
        int minY = Math.min(
                Math.min(CommonGameAreas.BEAR_TRAP_INITIATOR_DY1, CommonGameAreas.BEAR_TRAP_MEMBERS_DY1),
                Math.min(CommonGameAreas.BEAR_TRAP_CAPACITY_DY1, CommonGameAreas.BEAR_TRAP_COUNTDOWN_DY1));
        int maxY = Math.max(
                Math.max(CommonGameAreas.BEAR_TRAP_INITIATOR_DY2, CommonGameAreas.BEAR_TRAP_MEMBERS_DY2),
                Math.max(CommonGameAreas.BEAR_TRAP_CAPACITY_DY2, CommonGameAreas.BEAR_TRAP_COUNTDOWN_DY2));
        return anchorY + minY >= 0 && anchorY + maxY <= SCREEN_HEIGHT
                && CommonGameAreas.BEAR_TRAP_INITIATOR_X1 >= 0
                && CommonGameAreas.BEAR_TRAP_COUNTDOWN_X2 <= SCREEN_WIDTH;
    }

    private String readHostName(PointData btnPoint) {
        AreaData area = new AreaData(
            new PointData(CommonGameAreas.BEAR_TRAP_INITIATOR_X1, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_INITIATOR_DY1),
            new PointData(CommonGameAreas.BEAR_TRAP_INITIATOR_X2, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_INITIATOR_DY2)
        );
        return textExtractor.extract(area.topLeft(), area.bottomRight());
    }

    private String readMembers(PointData btnPoint) {
        AreaData area = new AreaData(
            new PointData(CommonGameAreas.BEAR_TRAP_MEMBERS_X1, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_MEMBERS_DY1),
            new PointData(CommonGameAreas.BEAR_TRAP_MEMBERS_X2, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_MEMBERS_DY2)
        );
        return textExtractor.extract(area.topLeft(), area.bottomRight());
    }

    private String readCapacity(PointData btnPoint) {
        AreaData area = new AreaData(
            new PointData(CommonGameAreas.BEAR_TRAP_CAPACITY_X1, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_CAPACITY_DY1),
            new PointData(CommonGameAreas.BEAR_TRAP_CAPACITY_X2, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_CAPACITY_DY2)
        );
        return textExtractor.extract(area.topLeft(), area.bottomRight());
    }

    private String readCountdown(PointData btnPoint) {
        AreaData area = new AreaData(
            new PointData(CommonGameAreas.BEAR_TRAP_COUNTDOWN_X1, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_COUNTDOWN_DY1),
            new PointData(CommonGameAreas.BEAR_TRAP_COUNTDOWN_X2, btnPoint.getY() + CommonGameAreas.BEAR_TRAP_COUNTDOWN_DY2)
        );
        return textExtractor.extract(area.topLeft(), area.bottomRight());
    }
}
