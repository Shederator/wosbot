package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.tasks.city.ResearchDialogClassifier.ResearchDialogState;
import org.junit.jupiter.api.Test;

class ResearchDialogClassifierTest {

    @Test
    void researchButtonAlwaysMeansStartable() {
        assertEquals(ResearchDialogState.STARTABLE,
                ResearchDialogClassifier.classify(true, 0, ""));
    }

    @Test
    void singleResearchCenterRequirementMeansCenterCapped() {
        assertEquals(ResearchDialogState.CENTER_CAPPED,
                ResearchDialogClassifier.classify(false, 1, "Se Research Center Lv. 9 4"));
    }

    @Test
    void additionalRequirementPreventsCenterCapInference() {
        assertEquals(ResearchDialogState.PREREQUISITE_BLOCKED,
                ResearchDialogClassifier.classify(
                        false, 2, "Research Center Lv. 10 Wood Output II Lv. 1"));
    }

    @Test
    void nonCenterRequirementIsPrerequisiteBlocked() {
        assertEquals(ResearchDialogState.PREREQUISITE_BLOCKED,
                ResearchDialogClassifier.classify(false, 1, "Wood Output II Lv. 1"));
    }

    @Test
    void missingEvidenceRemainsUnknown() {
        assertEquals(ResearchDialogState.UNKNOWN,
                ResearchDialogClassifier.classify(false, 0, ""));
    }
}
