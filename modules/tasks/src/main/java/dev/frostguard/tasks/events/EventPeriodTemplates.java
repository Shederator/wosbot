package dev.frostguard.tasks.events;

/**
 * Named event-period templates that are not installed yet. A detector stays
 * unrecognized until the matching art is added.
 */
public final class EventPeriodTemplates {

    public static final String MERCENARY_COMPLETION = "mercenary-completion";
    public static final String MERCENARY_START_COUNTDOWN = "mercenary-start-countdown";
    public static final String MERCENARY_END_COUNTDOWN = "mercenary-end-countdown";
    public static final String HERO_MISSION_COMPLETION = "hero-mission-completion";
    public static final String HERO_MISSION_START_COUNTDOWN = "hero-mission-start-countdown";
    public static final String JOURNEY_COMPLETION = "journey-of-light-completion";
    public static final String JOURNEY_START_COUNTDOWN = "journey-of-light-start-countdown";
    public static final String MYRIAD_COMPLETION = "myriad-bazaar-completion";
    public static final String MYRIAD_START_COUNTDOWN = "myriad-bazaar-start-countdown";
    public static final String FISHING_COMPLETION = "fishing-completion";
    public static final String FISHING_START_COUNTDOWN = "fishing-start-countdown";
    public static final String TUNDRA_COMPLETION = "tundra-truck-completion";

    private EventPeriodTemplates() {
    }

    public static boolean installed(String templateName) {
        return false;
    }
}
