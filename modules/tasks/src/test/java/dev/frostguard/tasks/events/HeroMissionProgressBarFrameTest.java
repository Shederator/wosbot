package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class HeroMissionProgressBarFrameTest {
    private static final int FRAME_WIDTH = 720;
    private static final int FRAME_HEIGHT = 1280;
    private static final int BAR_ORIGIN_X = 116;
    private static final int BAR_ORIGIN_Y = 1032;

    @Test
    void recognizesCompleteProgressFromSavedFrame() throws Exception {
        assertEquals(HeroMissionProgressBar.State.COMPLETE,
                HeroMissionProgressBar.read(frameWithBar("hero_mission_progress_10.png")));
    }

    @Test
    void recognizesThreeAndFourAsInProgressFromSavedFrames() throws Exception {
        assertEquals(HeroMissionProgressBar.State.IN_PROGRESS,
                HeroMissionProgressBar.read(frameWithBar("hero_mission_progress_3.png")));
        assertEquals(HeroMissionProgressBar.State.IN_PROGRESS,
                HeroMissionProgressBar.read(frameWithBar("hero_mission_progress_4.png")));
    }

    @Test
    void recognizesAnUnstartedGreyTrackAsInProgress() throws Exception {
        BufferedImage unstarted = frameWithBar("hero_mission_progress_3.png");
        int track = new Color(117, 113, 149).getRGB();
        for (int x = 185; x <= 645; x++) {
            for (int y = 1041; y <= 1047; y++) {
                unstarted.setRGB(x, y, track);
            }
        }

        assertEquals(HeroMissionProgressBar.State.IN_PROGRESS, HeroMissionProgressBar.read(unstarted));
    }

    @Test
    void returnsUnknownForMissingInvalidAndAmbiguousBars() throws Exception {
        assertEquals(HeroMissionProgressBar.State.UNKNOWN, HeroMissionProgressBar.read(null));
        assertEquals(HeroMissionProgressBar.State.UNKNOWN,
                HeroMissionProgressBar.read(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        assertEquals(HeroMissionProgressBar.State.UNKNOWN,
                HeroMissionProgressBar.read(new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT,
                        BufferedImage.TYPE_INT_ARGB)));

        BufferedImage interrupted = frameWithBar("hero_mission_progress_10.png");
        int track = new Color(117, 113, 149).getRGB();
        for (int x = 400; x <= 420; x++) {
            for (int y = 1041; y <= 1047; y++) {
                interrupted.setRGB(x, y, track);
            }
        }
        assertEquals(HeroMissionProgressBar.State.UNKNOWN, HeroMissionProgressBar.read(interrupted));
    }

    @Test
    void toleratesASmallInterruptionInTheCompleteBar() throws Exception {
        BufferedImage interrupted = frameWithBar("hero_mission_progress_10.png");
        int track = new Color(117, 113, 149).getRGB();
        for (int x = 400; x <= 401; x++) {
            for (int y = 1041; y <= 1047; y++) {
                interrupted.setRGB(x, y, track);
            }
        }
        assertEquals(HeroMissionProgressBar.State.COMPLETE, HeroMissionProgressBar.read(interrupted));
    }

    private BufferedImage frameWithBar(String fixture) throws Exception {
        BufferedImage strip;
        String path = "/dev/frostguard/tasks/events/" + fixture;
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            strip = ImageIO.read(Objects.requireNonNull(stream, "Missing " + path));
        }

        BufferedImage frame = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < strip.getHeight(); y++) {
            for (int x = 0; x < strip.getWidth(); x++) {
                frame.setRGB(BAR_ORIGIN_X + x, BAR_ORIGIN_Y + y, strip.getRGB(x, y));
            }
        }
        return frame;
    }
}
