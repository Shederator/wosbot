package dev.frostguard.tasks.combat;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved frames of the Challenge List's Daily Challenges chest, one with the
 * day's attempts unfinished and one finished. Everything above and below the
 * chest strip is blanked, since the rows carry other players' names and avatars.
 */
class ArenaDailyChestFrameTest {

    @Test
    void aFinishedCountMarksTheChestReady() throws Exception {
        assertTrue(ArenaRoutine.showsFinishedDailyChestCount(frame("5of5")));
    }

    @Test
    void anUnfinishedCountLeavesTheChestAlone() throws Exception {
        assertFalse(ArenaRoutine.showsFinishedDailyChestCount(frame("4of5")));
    }

    private BufferedImage frame(String count) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(
                "/arena/challenge-list-daily-chest-" + count + "-20261109.png")));
    }
}
