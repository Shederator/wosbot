package dev.frostguard.engine.nav;

import java.util.Objects;

import dev.frostguard.api.domain.ImageSearchResultData;

/** Result of opening a sidebar section and searching it for a destination row. */
public record SidebarRowLookup(Status status, ImageSearchResultData rowIcon) {

    public SidebarRowLookup {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rowIcon, "rowIcon");
        if ((status == Status.FOUND) != rowIcon.isFound()) {
            throw new IllegalArgumentException("FOUND status must match the row icon result");
        }
    }

    public static SidebarRowLookup sidebarUnavailable() {
        return new SidebarRowLookup(Status.SIDEBAR_UNAVAILABLE, ImageSearchResultData.miss());
    }

    public static SidebarRowLookup fromRow(ImageSearchResultData rowIcon) {
        ImageSearchResultData result = rowIcon == null ? ImageSearchResultData.miss() : rowIcon;
        Status status = result.isFound() ? Status.FOUND : Status.DESTINATION_ABSENT;
        return new SidebarRowLookup(status, result);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    public enum Status {
        FOUND,
        DESTINATION_ABSENT,
        SIDEBAR_UNAVAILABLE
    }
}
