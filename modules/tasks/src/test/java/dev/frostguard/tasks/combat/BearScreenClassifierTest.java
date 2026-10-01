package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BearScreenClassifierTest {

    @Test
    void dialogEvidenceWinsOverFormationAndWorldEvidence() {
        assertEquals(BearNavigationPolicy.Screen.MARCH_QUEUE_FULL,
                BearScreenClassifier.classify(evidence(true, true, true)));
    }

    @Test
    void verifiedBearAnchorRequiresPositiveEvidenceFromTheSameFrame() {
        assertEquals(BearNavigationPolicy.Screen.WORLD,
                BearScreenClassifier.classify(evidence(false, false, true)));
        BearScreenClassifier.Evidence verified = evidence(false, false, false);
        verified = new BearScreenClassifier.Evidence(
                false, // reconnect
                false, // appLoading
                false, // marchQueueFull
                false, // deployConfirmation
                false, // recallConfirmation
                false, // formation
                false, // rallyTimer
                false, // bearPanel
                false, // warList
                false, // autojoinPanel
                false, // allianceWar
                false, // allianceMenu
                false, // allianceTerritory
                false, // specialBuildings
                false, // petsOverview
                false, // petRazorback
                false, // petQuickUse
                false, // marchSidebar
                false, // sidebarOther
                true,  // worldRoot
                false, // activeBearIcon
                true,  // verifiedBearAnchor
                false);// transitioning
        assertEquals(BearNavigationPolicy.Screen.WORLD_AT_VERIFIED_BEAR,
                BearScreenClassifier.classify(verified));
    }

    private static BearScreenClassifier.Evidence evidence(
            boolean queueFull, boolean formation, boolean world) {
        return new BearScreenClassifier.Evidence(
                false, // reconnect
                false, // appLoading
                queueFull,
                false, // deployConfirmation
                false, // recallConfirmation
                formation,
                false, // rallyTimer
                false, // bearPanel
                false, // warList
                false, // autojoinPanel
                false, // allianceWar
                false, // allianceMenu
                false, // allianceTerritory
                false, // specialBuildings
                false, // petsOverview
                false, // petRazorback
                false, // petQuickUse
                false, // marchSidebar
                false, // sidebarOther
                world,
                false, // activeBearIcon
                false, // verifiedBearAnchor
                false);// transitioning
    }
}
