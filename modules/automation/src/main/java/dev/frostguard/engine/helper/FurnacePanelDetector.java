package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.CommonGameAreas;

/** Identifies the Furnace detail entry, never the final upgrade confirmation. */
public final class FurnacePanelDetector {
    public static final int THRESHOLD = 90;

    private FurnacePanelDetector() {}

    @FunctionalInterface
    public interface Matcher {
        ImageSearchResultData locate(TemplatesEnum template, AreaData area, int threshold);
    }

    /** Both controls must be evaluated against the same captured frame. */
    public static Evidence inspect(Matcher matcher) {
        ImageSearchResultData title = matcher.locate(TemplatesEnum.FURNACE_PANEL_TITLE,
                CommonGameAreas.FURNACE_PANEL_TITLE, THRESHOLD);
        ImageSearchResultData upgrade = title.isFound()
                ? matcher.locate(TemplatesEnum.FURNACE_PANEL_UPGRADE,
                        CommonGameAreas.FURNACE_PANEL_UPGRADE, THRESHOLD)
                : null;
        return new Evidence(title, upgrade);
    }

    public record Evidence(ImageSearchResultData title, ImageSearchResultData upgrade) {
        public boolean panelVisible() {
            return title.isFound();
        }

        public boolean actionable() {
            return panelVisible() && upgrade != null && upgrade.hasMatchedArea();
        }

        @Override
        public String toString() {
            return "Furnace threshold=" + THRESHOLD + " title=" + title
                    + " upgrade=" + (upgrade == null ? "not-searched" : upgrade);
        }
    }
}
