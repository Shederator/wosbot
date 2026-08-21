package dev.frostguard.tasks.dailies;

final class IntelDeploymentPreflight {

    static final long MAX_TRAVEL_SECONDS = 30 * 60;

    private IntelDeploymentPreflight() {}

    static Decision assess(long travelSeconds) {
        if (travelSeconds <= 0) {
            return new Decision(false, "travel time was unreadable");
        }
        if (travelSeconds > MAX_TRAVEL_SECONDS) {
            return new Decision(false, "travel time " + travelSeconds
                    + "s exceeds the 30 minute Intel safety limit");
        }
        return new Decision(true, "travel time is plausible");
    }

    record Decision(boolean allowed, String evidence) {}
}
