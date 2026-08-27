package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Keeps the last so-many megabytes of read screens, so a pass can be read again without replaying
 * the game.
 *
 * <p>Reading a screen is destructive in the sense that matters: the pixels are the only complete
 * record, and once they are gone the only way to try a different threshold, a different language or
 * a repaired filter is to wait for the alliance to say those things again, which it will not. Every
 * improvement to the reader so far was measured against a corpus of saved frames, and each corpus
 * had to be captured by hand first.
 *
 * <p>It is off by default, and that is deliberate rather than cautious. This writes screenshots of
 * a person's chat to their disk. Somebody who wants that should say so; nobody should discover it.
 * At the default of zero nothing is written and nothing is left behind, which is what the capture
 * did before this class existed.
 *
 * <p>The budget is a disk figure rather than a count of frames because that is the thing a person
 * actually cares about bounding, and frame sizes vary by a factor of three with how much of the
 * screen is artwork. Oldest goes first: a frame's usefulness is that it can still be compared
 * against what the reader does today, and the newest ones are the ones a current problem is in.
 */
final class ChatFrameCache {

    private final Path dir;
    private final long budgetBytes;
    private final boolean keepEverything;

    /** A frame is roughly 400KB, so a megabyte figure lands within a frame or two of the truth. */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    ChatFrameCache(Path baseDir, int budgetMb) {
        this.dir = baseDir.resolve("frames");
        // A negative figure is the panel's "never delete". Kept as a distinct state rather than as
        // a very large budget so prune() can skip the walk entirely instead of measuring a
        // directory that is only going to grow.
        this.keepEverything = budgetMb < 0;
        this.budgetBytes = Math.max(0, budgetMb) * BYTES_PER_MB;
    }

    boolean isOn() {
        return keepEverything || budgetBytes > 0;
    }

    /**
     * Files one screen, then brings the directory back under budget.
     *
     * <p>Pruning after writing rather than before means the cache can momentarily exceed its budget
     * by one frame. Doing it the other way round would have to predict the encoded size of an image
     * that has not been encoded yet, and being 400KB over for the length of a write is not worth
     * that.
     */
    void keep(String channel, int scrollIndex, BufferedImage image) {
        if (!isOn()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path out = dir.resolve(channel + "-" + LocalDateTime.now().format(STAMP)
                    + "-" + scrollIndex + ".png");
            ImageIO.write(image, "png", out.toFile());
            prune();
        } catch (IOException e) {
            // A cache that cannot write is a cache that is not there. The pass has the frame in
            // memory and has already read it, so there is nothing here worth failing a capture for.
            throw new UncheckedFrameCacheFailure(e);
        }
    }

    /** Drops the oldest frames until the directory fits its budget. */
    private void prune() throws IOException {
        if (keepEverything) {
            return;
        }
        List<Path> frames = new ArrayList<>();
        try (var listing = Files.list(dir)) {
            listing.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(frames::add);
        }
        long total = 0;
        for (Path p : frames) {
            total += Files.size(p);
        }
        if (total <= budgetBytes) {
            return;
        }
        // By name, not by modified time: the name carries the capture stamp, and a file copied or
        // restored keeps its place in the sequence rather than jumping to the front of it.
        frames.sort(Comparator.comparing(p -> p.getFileName().toString()));
        // The newest frame is never pruned, even when it alone is over budget. A budget smaller
        // than one screen would otherwise mean the cache deletes every frame the moment it writes
        // it, and the setting would read as "keep 100MB" while keeping nothing at all -- an option
        // that silently does nothing is worse than an option that is honestly approximate.
        for (int i = 0; i < frames.size() - 1; i++) {
            if (total <= budgetBytes) {
                return;
            }
            long size = Files.size(frames.get(i));
            if (Files.deleteIfExists(frames.get(i))) {
                total -= size;
            }
        }
    }

    /** Signals that the cache could not write, without making a capture failure out of it. */
    static final class UncheckedFrameCacheFailure extends RuntimeException {
        UncheckedFrameCacheFailure(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
