package dev.frostguard.tasks.city;

import java.util.Locale;

final class ResearchDialogClassifier {

    private ResearchDialogClassifier() {}

    static ResearchDialogState classify(boolean researchButtonFound, int goButtonCount,
                                        String requirementText) {
        if (researchButtonFound) {
            return ResearchDialogState.STARTABLE;
        }
        String normalized = requirementText == null
                ? ""
                : requirementText.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        boolean researchCenterMentioned = normalized.contains("research center");

        if (goButtonCount == 1 && researchCenterMentioned) {
            return ResearchDialogState.CENTER_CAPPED;
        }
        if (goButtonCount >= 1) {
            return ResearchDialogState.PREREQUISITE_BLOCKED;
        }
        return ResearchDialogState.UNKNOWN;
    }

    enum ResearchDialogState {
        STARTABLE,
        CENTER_CAPPED,
        PREREQUISITE_BLOCKED,
        UNKNOWN
    }
}
