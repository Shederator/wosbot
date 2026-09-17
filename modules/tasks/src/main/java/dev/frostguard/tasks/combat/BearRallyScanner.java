package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.convert.CompactGameNumberParser;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Parses visible Bear rally cards from one immutable frame. */
final class BearRallyScanner {

    private static final int MAX_CARDS = 8;
    private static final int ROW_TOLERANCE = 90;

    @FunctionalInterface
    interface TextExtractor {
        String extract(PointData topLeft, PointData bottomRight);
    }

    private final Supplier<List<ImageSearchResultData>> greenButtons;
    private final Supplier<List<ImageSearchResultData>> bearIcons;
    private final TextExtractor text;

    BearRallyScanner(TemplateSearchHelper search) {
        TemplateSearchHelper.Frame frame = search.captureFrame();
        this.greenButtons = () -> frame.locateAllPatterns(
                TemplatesEnum.BEAR_JOIN_PLUS_ICON, search(80));
        this.bearIcons = () -> frame.locateAllPatterns(
                TemplatesEnum.BEAR_HUNT_IS_RUNNING, search(80));
        this.text = (topLeft, bottomRight) -> {
            try {
                var settings = topLeft.getX() == CommonGameAreas.BEAR_RALLY_MEMBERS_X1
                        ? CommonOCRSettings.BEAR_RALLY_MEMBERS_SETTINGS
                        : topLeft.getX() == CommonGameAreas.BEAR_RALLY_TROOPS_X1
                                ? CommonOCRSettings.BEAR_RALLY_CAPACITY_SETTINGS
                                : CommonOCRSettings.BEAR_RALLY_COUNTDOWN_SETTINGS;
                return frame.extractText(settings, topLeft, bottomRight);
            } catch (Exception ignored) {
                return null;
            }
        };
    }

    BearRallyScanner(
            Supplier<List<ImageSearchResultData>> greenButtons,
            Supplier<List<ImageSearchResultData>> bearIcons,
            TextExtractor text) {
        this.greenButtons = greenButtons;
        this.bearIcons = bearIcons;
        this.text = text;
    }

    List<BearRallyCandidate> scanCandidates(Instant observedAt) {
        List<ImageSearchResultData> bears = safe(bearIcons.get());
        List<BearRallyCandidate> candidates = new ArrayList<>();
        safe(greenButtons.get()).stream()
                .filter(BearRallyScanner::usable)
                .sorted(Comparator.comparingInt(hit -> hit.getPoint().getY()))
                .forEach(button -> parse(button, bears, observedAt).ifPresent(candidates::add));
        return candidates;
    }

    private Optional<BearRallyCandidate> parse(
            ImageSearchResultData button,
            List<ImageSearchResultData> bears,
            Instant observedAt) {
        int rowY = button.getPoint().getY();
        boolean bear = bears.stream().filter(BearRallyScanner::usable)
                .anyMatch(icon -> Math.abs(icon.getPoint().getY() - rowY) <= ROW_TOLERANCE);
        if (!bear) {
            return Optional.empty();
        }
        int anchorY = button.hasMatchedArea()
                ? button.getMatchedArea().topLeft().getY()
                : rowY;
        String membersText = read(anchorY,
                CommonGameAreas.BEAR_RALLY_MEMBERS_X1, CommonGameAreas.BEAR_RALLY_MEMBERS_X2,
                CommonGameAreas.BEAR_RALLY_MEMBERS_DY1, CommonGameAreas.BEAR_RALLY_MEMBERS_DY2);
        String troopsText = read(anchorY,
                CommonGameAreas.BEAR_RALLY_TROOPS_X1, CommonGameAreas.BEAR_RALLY_TROOPS_X2,
                CommonGameAreas.BEAR_RALLY_TROOPS_DY1, CommonGameAreas.BEAR_RALLY_TROOPS_DY2);
        String countdownText = read(anchorY,
                CommonGameAreas.BEAR_RALLY_COUNTDOWN_X1, CommonGameAreas.BEAR_RALLY_COUNTDOWN_X2,
                CommonGameAreas.BEAR_RALLY_COUNTDOWN_DY1, CommonGameAreas.BEAR_RALLY_COUNTDOWN_DY2);

        long[] members = ratio(membersText);
        long[] troops = ratio(troopsText);
        Duration countdown = GameTimeUtils.parseMinutesSeconds(countdownText);
        if (members == null || troops == null || countdown == null
                || members[0] > Integer.MAX_VALUE || members[1] > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        AreaData buttonArea = button.hasMatchedArea()
                ? button.getMatchedArea()
                : new AreaData(button.getPoint(), button.getPoint());
        BearRallyCandidate candidate = new BearRallyCandidate(
                buttonArea, rowY, true, BearRallyCandidate.JoinButton.GREEN,
                (int) members[0], (int) members[1], troops[0], troops[1],
                countdown, observedAt);
        return candidate.accepts(0) ? Optional.of(candidate) : Optional.empty();
    }

    private String read(int anchorY, int x1, int x2, int dy1, int dy2) {
        int y1 = anchorY + dy1;
        int y2 = anchorY + dy2;
        if (y1 < 0 || y2 > 1280) {
            return null;
        }
        return text.extract(new PointData(x1, y1), new PointData(x2, y2));
    }

    private static long[] ratio(String raw) {
        if (raw == null || !raw.contains("/")) {
            return null;
        }
        String[] parts = raw.replace(" ", "").split("/", 2);
        long current = CompactGameNumberParser.parse(parts[0]);
        long maximum = CompactGameNumberParser.parse(parts[1]);
        return current >= 0 && maximum > 0 && current <= maximum
                ? new long[] { current, maximum }
                : null;
    }

    private static boolean usable(ImageSearchResultData hit) {
        return hit != null && hit.isFound() && hit.getPoint() != null;
    }

    private static List<ImageSearchResultData> safe(List<ImageSearchResultData> hits) {
        return hits == null ? List.of() : hits;
    }

    private static TemplateSearchHelper.SearchConfig search(int threshold) {
        return TemplateSearchHelper.SearchConfig.builder()
                .withThreshold(threshold)
                .withMaxResults(MAX_CARDS)
                .build();
    }
}
