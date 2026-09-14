package dev.frostguard.api.configs;

/** Operation selected by a Task Builder sidebar navigation step. */
public enum SidebarNavigationMode {
    SECTION("Open section"),
    DESTINATION("Go to destination");

    private final String displayName;

    SidebarNavigationMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
