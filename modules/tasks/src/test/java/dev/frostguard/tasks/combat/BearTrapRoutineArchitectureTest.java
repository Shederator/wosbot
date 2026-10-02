package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BearTrapRoutineArchitectureTest {

    @Test
    void bearRoutineHasNoLegacyFixedSleepsOrProceduralTapHelpers() throws IOException {
        Path source = Path.of("src/main/java/dev/frostguard/tasks/combat/BearTrapRoutine.java");
        String java = Files.readString(source);

        assertFalse(java.contains("sleepTask("), "Bear waits must sample ordered frames");
        assertFalse(java.contains("selectFlag("), "formation input must use Bear frame authorization");
        assertFalse(java.contains("selectBearRallySetTimeMinutes("),
                "timer input must use Bear frame authorization");
        assertFalse(java.contains("readMarchQueueSnapshotSinglePass("),
                "march reads must use a caller-owned Bear frame");
        assertFalse(java.contains("dismissMarchQueueFullPopup("),
                "dialog dismissal must be a verified Bear transition");
        assertFalse(java.contains("TemplatesEnum.BEAR_HUNT_IS_RUNNING"),
                "execution-window decisions must not capture outside the Bear frame stream");
        assertFalse(java.contains("promoteCurrent("),
                "an inferred screen state must never be promoted into tap authorization");
        // The recorder is silent on a static screen; its only screenshot is the static-screen
        // sample inside the ordered realtime source, which still receives a frame sequence.
        assertEquals(1, occurrences(java, "emuManager.captureScreen("),
                "Bear may only screenshot through the ordered recorder's static-screen sample");
        assertEquals(1, occurrences(java, "() -> emuManager.captureScreen(EMULATOR_NUMBER))"),
                "the screenshot must be wired as the realtime source's static-screen sample");
        assertEquals(1, occurrences(java, "new BearRealtimeFrameSource("),
                "the live Bear session must own exactly one ordered recorder");
    }

    @Test
    void authorizingCallbacksDoNoOcrOrPersistence() throws IOException {
        String java = Files.readString(
                Path.of("src/main/java/dev/frostguard/tasks/combat/BearTrapRoutine.java"));
        for (String action : new String[] {"OPEN_JOIN_FORMATION", "SCROLL_RALLY_LIST", "DEPLOY_OWN_RALLY",
                "DEPLOY_JOIN"}) {
            String callback = callbackOf(java, action);
            // OCR and checkpoint writes take longer than the one-second authorization budget.
            assertFalse(callback.contains("scanRows()"), action + " callback must not read leader text");
            assertFalse(callback.contains("requireTacticalCheckpoint"),
                    action + " callback must not persist before its input");
            assertFalse(callback.contains("readPreflightScreen"), action + " callback must not run OCR");
        }
    }

    private static String callbackOf(String java, String action) {
        int start = java.indexOf("BearUiAction." + action + ",");
        assertTrue(start >= 0, action + " transition not found");
        int open = java.indexOf("authorization -> {", start) + "authorization -> ".length();
        int depth = 0;
        for (int index = open; index < java.length(); index++) {
            char character = java.charAt(index);
            if (character == '{') depth++;
            if (character == '}' && --depth == 0) return java.substring(open, index + 1);
        }
        throw new AssertionError(action + " callback is not closed");
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
