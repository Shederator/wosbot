package dev.frostguard.app.panel.taskbuilder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBuilderRunLogTest {

    @Test
    void retainsCompletedRunUntilNextRunAndRejectsLateEntries() {
        TaskBuilderRunLog log = new TaskBuilderRunLog();
        long firstRun = log.begin(42L);
        assertTrue(log.append(firstRun, "first\nsecond"));
        log.finish();
        assertEquals("second\nfirst", log.text());

        long secondRun = log.begin(42L);
        assertEquals("", log.text());
        assertFalse(log.append(firstRun, "late line"));
        assertTrue(log.append(secondRun, "current line"));
        assertEquals("current line", log.text());
    }

    @Test
    void clearsOnProfileChangeAndRejectsOverlappingRuns() {
        TaskBuilderRunLog log = new TaskBuilderRunLog();
        long firstRun = log.begin(42L);
        assertEquals(-1, log.begin(43L));
        log.append(firstRun, "old profile");

        log.selectProfile(43L);
        assertEquals("", log.text());
        assertFalse(log.append(firstRun, "old run after switch"));
        log.finish();

        long secondRun = log.begin(43L);
        assertTrue(log.append(secondRun, "new profile"));
        assertEquals("new profile", log.text());
    }

    @Test
    void displaysNewestLineFirstAndDropsOldestWhenHistoryIsFull() {
        TaskBuilderRunLog log = new TaskBuilderRunLog();
        long run = log.begin(42L);
        for (int index = 0; index <= 500; index++) {
            assertTrue(log.append(run, "line " + index));
        }

        String[] visible = log.text().split("\n");
        assertEquals(500, visible.length);
        assertEquals("line 500", visible[0]);
        assertEquals("line 1", visible[499]);
    }
}
