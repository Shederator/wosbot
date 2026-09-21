package dev.frostguard.app.panel.taskbuilder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Keeps the latest Task Builder run's log history on the JavaFX thread. */
final class TaskBuilderRunLog {

    private static final int MAX_LINES = 500;

    private final Deque<String> lines = new ArrayDeque<>();
    private Long selectedProfileId;
    private long generation;
    private boolean active;

    long begin(long profileId) {
        if (active) return -1;
        selectedProfileId = profileId;
        active = true;
        generation++;
        lines.clear();
        return generation;
    }

    void selectProfile(Long profileId) {
        if (Objects.equals(selectedProfileId, profileId)) return;
        selectedProfileId = profileId;
        generation++;
        lines.clear();
    }

    boolean append(long runGeneration, String entry) {
        if (runGeneration != generation) return false;
        boolean changed = false;
        for (String line : entry.split("\\R")) {
            if (line.isEmpty()) continue;
            lines.addFirst(line);
            while (lines.size() > MAX_LINES) lines.removeLast();
            changed = true;
        }
        return changed;
    }

    void finish() {
        active = false;
    }

    String text() {
        return String.join("\n", lines);
    }
}
