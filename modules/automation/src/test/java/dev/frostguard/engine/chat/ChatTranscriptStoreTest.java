package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence level: automated tests.
 */
class ChatTranscriptStoreTest {

    @TempDir
    Path dir;

    private static ChatMessage msg(Instant at, String author, String body) {
        return new ChatMessage(at, "world", author, "INF", 0, body, "", List.of(),
                ChatMessage.Kind.TEXT, "");
    }

    private ChatTranscriptStore store() {
        return new ChatTranscriptStore(dir, ZoneOffset.UTC);
    }

    @Test
    void writesMessagesIntoTheDayFileTheyBelongTo() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();

        assertEquals(1, s.append(List.of(msg(at, "Nightjar", "rally in five"))));

        Path day = dir.resolve("chat-2026-08-21.jsonl");
        assertTrue(Files.exists(day), "expected a file named for the capture's day");
        assertTrue(Files.readString(day, StandardCharsets.UTF_8).contains("rally in five"));
    }

    @Test
    void aMessageSeenAgainOnAnOverlappingScrollBackIsNotStoredTwice() throws IOException {
        // Each pass deliberately re-reads screens it already captured, so most arriving rows are
        // repeats. Without this the transcript would grow by the whole scroll-back every cycle.
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();

        assertEquals(1, s.append(List.of(msg(at, "Nightjar", "rally in five"))));
        assertEquals(0, s.append(List.of(msg(at.plusSeconds(90), "Nightjar", "rally in five"))));
    }

    @Test
    void unreadableRowsNeverReachTheTranscript() throws IOException {
        ChatMessage junk = new ChatMessage(Instant.parse("2026-08-21T22:15:00Z"), "world", "",
                "", 0, "", "", List.of(), ChatMessage.Kind.UNREADABLE, "");

        assertEquals(0, store().append(List.of(junk)));
    }

    @Test
    void restartDoesNotReAppendWhatIsAlreadyOnDisk() throws IOException {
        // The de-duplication window only ever holds this session's messages, so a fresh store has
        // to learn what the previous run already wrote before the next overlapping pass arrives.
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        store().append(List.of(msg(at, "Nightjar", "rally in five")));

        ChatTranscriptStore restarted = store();
        restarted.primeFromDisk();

        assertEquals(0, restarted.append(List.of(msg(at, "Nightjar", "rally in five"))));
    }

    @Test
    void readsBackRecentMessagesOldestFirst() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();
        s.append(List.of(msg(at, "Nightjar", "first"), msg(at.plusSeconds(60), "Marisol", "second")));

        List<ChatMessage> back = s.recent(10);

        assertEquals(2, back.size());
        assertEquals("first", back.get(0).body());
        assertEquals("second", back.get(1).body());
    }

    @Test
    void aTruncatedFinalLineCostsOneMessageRatherThanTheDay() throws IOException {
        Instant at = Instant.parse("2026-08-21T22:15:00Z");
        ChatTranscriptStore s = store();
        s.append(List.of(msg(at, "Nightjar", "intact")));
        // Append-only writing across a restart or power cut can leave a half-written last line.
        Files.writeString(dir.resolve("chat-2026-08-21.jsonl"), "{\"at\":\"2026-08-2",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<ChatMessage> back = s.recent(10);

        assertEquals(1, back.size());
        assertEquals("intact", back.get(0).body());
    }

    @Test
    void framesAreDeletedOnceTheirRowsAreStored() throws IOException {
        Path frame = Files.writeString(dir.resolve("world-1.png"), "x");

        ChatTranscriptStore.retireFrame(frame, true);

        assertFalse(Files.exists(frame), "a frame that was read has no reason to stay on disk");
    }

    @Test
    void aFrameIsKeptWhenReadingItFailedSoThereIsSomethingToLookAt() throws IOException {
        Path frame = Files.writeString(dir.resolve("world-2.png"), "x");

        ChatTranscriptStore.retireFrame(frame, false);

        assertTrue(Files.exists(frame), "a failed capture must leave its frame for diagnosis");
    }

    @Test
    void purgeRemovesWholeDaysPastTheRetentionWindow() throws IOException {
        ChatTranscriptStore s = store();
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant fresh = Instant.now();
        s.append(List.of(msg(old, "Nightjar", "ancient")));
        s.append(List.of(msg(fresh, "Marisol", "today")));

        assertEquals(1, s.purgeOlderThan(30));
        assertFalse(Files.exists(s.fileFor(old)));
        assertTrue(Files.exists(s.fileFor(fresh)));
    }

    @Test
    void reportsItsOwnSizeForTheStartupFigure() throws IOException {
        ChatTranscriptStore s = store();
        s.append(List.of(msg(Instant.parse("2026-08-21T22:15:00Z"), "Nightjar", "rally in five")));

        assertTrue(s.sizeBytes() > 0);
        assertEquals("2.0 KB", ChatTranscriptStore.humanSize(2048));
        assertEquals("512 B", ChatTranscriptStore.humanSize(512));
    }
}
