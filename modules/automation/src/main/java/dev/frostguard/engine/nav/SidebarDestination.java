package dev.frostguard.engine.nav;

import dev.frostguard.api.configs.TemplatesEnum;

/** Destinations whose sidebar rows have stable visual identity. */
public enum SidebarDestination {
    ARENA(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_ARENA, 1),
    LAND_OF_HEROES(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_LAND_OF_HEROES, 1),
    LIFE_ESSENCE(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_LIFE_ESSENCE, 2);

    private final SidebarSection section;
    private final TemplatesEnum rowIcon;
    private final int scanSwipes;

    SidebarDestination(SidebarSection section, TemplatesEnum rowIcon, int scanSwipes) {
        this.section = section;
        this.rowIcon = rowIcon;
        this.scanSwipes = scanSwipes;
    }

    public SidebarSection section() {
        return section;
    }

    public TemplatesEnum rowIcon() {
        return rowIcon;
    }

    public int scanSwipes() {
        return scanSwipes;
    }
}
