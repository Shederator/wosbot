package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
