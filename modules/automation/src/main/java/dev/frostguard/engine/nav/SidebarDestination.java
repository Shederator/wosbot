package dev.frostguard.engine.nav;

import dev.frostguard.api.configs.TemplatesEnum;

/** Destinations whose sidebar rows have stable visual identity. */
public enum SidebarDestination {
    RESEARCH_CENTER(SidebarSection.CITY, TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER,
            SidebarRowAction.GO),
    ARENA(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_ARENA, SidebarRowAction.GO),
    PET_ADVENTURE(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_PET_ADVENTURE,
            SidebarRowAction.GO),
    LAND_OF_HEROES(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_LAND_OF_HEROES,
            SidebarRowAction.CLAIM, SidebarRowAction.GO),
    LIFE_ESSENCE(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_LIFE_ESSENCE,
            SidebarRowAction.CLAIM, SidebarRowAction.GO),
    CRYSTAL_LABORATORY(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_CRYSTAL_LABORATORY,
            SidebarRowAction.GO),
    LIGHTHOUSE_INTEL(SidebarSection.DAILY, TemplatesEnum.SIDEBAR_DAILY_LIGHTHOUSE_INTEL,
            OpeningPolicy.WILDERNESS_INTEL, SidebarRowAction.GO),
    TUNDRA_TREK_SUPPLIES(SidebarSection.DAILY, TemplatesEnum.TUNDRA_TREK_SUPPLIES,
            SidebarRowAction.CLAIM, SidebarRowAction.GO);

    private final SidebarSection section;
    private final TemplatesEnum rowIcon;
    private final OpeningPolicy openingPolicy;
    private final SidebarRowAction[] actions;

    SidebarDestination(SidebarSection section, TemplatesEnum rowIcon, SidebarRowAction... actions) {
        this(section, rowIcon, OpeningPolicy.SIDEBAR_ACTION, actions);
    }

    SidebarDestination(SidebarSection section, TemplatesEnum rowIcon,
                       OpeningPolicy openingPolicy, SidebarRowAction... actions) {
        this.section = section;
        this.rowIcon = rowIcon;
        this.openingPolicy = openingPolicy;
        this.actions = actions.clone();
    }

    public SidebarSection section() {
        return section;
    }

    public TemplatesEnum rowIcon() {
        return rowIcon;
    }

    /** Describes how the destination is opened after its sidebar row is validated. */
    public OpeningPolicy openingPolicy() {
        return openingPolicy;
    }

    public SidebarRowAction[] actions() {
        return actions.clone();
    }

    public enum OpeningPolicy {
        SIDEBAR_ACTION,
        WILDERNESS_INTEL
    }
}
