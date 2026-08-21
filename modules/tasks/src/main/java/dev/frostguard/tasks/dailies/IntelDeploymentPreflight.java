package dev.frostguard.tasks.dailies;

final class IntelDeploymentPreflight {

    static final long MAX_TRAVEL_SECONDS_EXCLUSIVE = 5 * 60;

    private IntelDeploymentPreflight() {}

    static Decision assess(long travelSeconds) {
        if (travelSeconds <= 0) {
            return new Decision(false, "travel time was unreadable");
        }
        if (travelSeconds >= MAX_TRAVEL_SECONDS_EXCLUSIVE) {
            return new Decision(false, "travel time " + travelSeconds
                    + "s reaches the 5 minute Intel safety limit");
        }
        return new Decision(true, "travel time is plausible");
    }

    record Decision(boolean allowed, String evidence) {}
}
