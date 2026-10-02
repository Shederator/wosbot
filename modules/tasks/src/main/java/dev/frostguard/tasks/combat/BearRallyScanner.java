package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Finds every visible Bear Rally row and its optional green-plus control from one frame. */
final class BearRallyScanner {

    private static final int MAX_CARDS = 8;
    private static final int ROW_TOLERANCE = 90;

    @FunctionalInterface
    interface TextExtractor {
        String extract(PointData topLeft, PointData bottomRight);
    }

    record RallyRow(AreaData joinArea, int rowY, long cropFingerprint, String leaderText) {
        boolean joinable() {
            return joinArea != null;
        }
    }

    private final Supplier<List<ImageSearchResultData>> greenButtons;
    private final Supplier<List<ImageSearchResultData>> bearIcons;
    private final TextExtractor text;
    private final BufferedImage identityImage;

    BearRallyScanner(TemplateSearchHelper search, RawImageData raw) {
        TemplateSearchHelper.Frame frame = search.frame(raw);
        this.greenButtons = () -> frame.locateAllPatterns(
                TemplatesEnum.BEAR_JOIN_PLUS_ICON, search(80));
        this.bearIcons = () -> frame.locateAllPatterns(
                TemplatesEnum.BEAR_HUNT_IS_RUNNING, search(80));
        this.text = (topLeft, bottomRight) -> {
            try {
                return frame.extractText(
                        CommonOCRSettings.BEAR_RALLY_LEADER_SETTINGS, topLeft, bottomRight);
            } catch (Exception ignored) {
                return null;
            }
        };
        this.identityImage = frame.bufferedImage();
    }

    BearRallyScanner(
            Supplier<List<ImageSearchResultData>> greenButtons,
            Supplier<List<ImageSearchResultData>> bearIcons,
            TextExtractor text) {
        this.greenButtons = greenButtons;
        this.bearIcons = bearIcons;
        this.text = text;
        this.identityImage = null;
    }

    List<RallyRow> scanRows() {
        return scan(true);
    }

    /**
     * Rows with their visual identity but no leader text. Leader OCR takes far longer than the
     * input authorization budget, so authorizing frames are matched by fingerprint only.
     */
    List<RallyRow> scanRowsWithoutText() {
        return scan(false);
    }

    private List<RallyRow> scan(boolean readLeaders) {
        List<ImageSearchResultData> bears = safe(bearIcons.get());
        List<ImageSearchResultData> buttons = safe(greenButtons.get()).stream()
                .filter(BearRallyScanner::usable).toList();
        return bears.stream()
                .filter(BearRallyScanner::usable)
                .sorted(Comparator.comparingInt(hit -> hit.getPoint().getY()))
                .map(icon -> row(icon, nearestButton(icon, buttons), readLeaders))
                .toList();
    }

    List<RallyRow> scanJoinControls() {
        return scanRows().stream().filter(RallyRow::joinable).toList();
    }

    private ImageSearchResultData nearestButton(
            ImageSearchResultData icon,
            List<ImageSearchResultData> buttons) {
        return buttons.stream()
                .filter(button -> Math.abs(icon.getPoint().getY()
                        - button.getPoint().getY()) <= ROW_TOLERANCE)
                .min(Comparator.comparingInt(button -> Math.abs(
                        icon.getPoint().getY() - button.getPoint().getY())))
                .orElse(null);
    }

    private RallyRow row(ImageSearchResultData icon, ImageSearchResultData button, boolean readLeader) {
        int anchorY = icon.hasMatchedArea()
                ? icon.getMatchedArea().topLeft().getY()
                : icon.getPoint().getY();
        AreaData joinArea = button == null ? null : button.hasMatchedArea()
                ? button.getMatchedArea()
                : new AreaData(button.getPoint(), button.getPoint());
        return new RallyRow(
                joinArea,
                icon.getPoint().getY(),
                visualIdentity(anchorY),
                readLeader ? normalizeLeader(readLeader(anchorY)) : "");
    }

    private String readLeader(int anchorY) {
        int y1 = anchorY + CommonGameAreas.BEAR_RALLY_LEADER_DY1;
        int y2 = anchorY + CommonGameAreas.BEAR_RALLY_LEADER_DY2;
        if (y1 < 0 || y2 > 1280) {
            return null;
        }
        return text.extract(
                new PointData(CommonGameAreas.BEAR_RALLY_LEADER_X1, y1),
                new PointData(CommonGameAreas.BEAR_RALLY_LEADER_X2, y2));
    }

    /** Hashes the stable target crop; countdowns and animated hero portraits are intentionally out. */
    private long visualIdentity(int anchorY) {
        if (identityImage == null) {
            return 0L;
        }
        int left = 38;
        int right = Math.min(identityImage.getWidth() - 1, 225);
        int top = Math.max(0, anchorY - 102);
        int bottom = Math.min(identityImage.getHeight() - 1, anchorY + 70);
        long hash = 0xcbf29ce484222325L;
        for (int y = top; y <= bottom; y += 4) {
            for (int x = left; x <= right; x += 4) {
                hash ^= identityImage.getRGB(x, y) & 0x00ffffffL;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static String normalizeLeader(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
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
