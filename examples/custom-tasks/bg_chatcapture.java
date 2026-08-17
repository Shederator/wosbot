package dev.frostguard.engine.listener.task.impl;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.CustomTaskService;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

/**
 * Captures World and Alliance chat on a schedule for the Whiteout dashboard.
 *
 * <p><b>Capture is deliberately separated from interpretation.</b> Chat is
 * multilingual, full of emoji and sticker-only messages, and Tesseract handles
 * that badly - far worse than it handles the numeric HUD. So each run saves the
 * raw frame AND a best-effort OCR pass, and records both. A poor OCR result
 * then costs nothing: the message is still on disk to be re-read properly
 * later. Discarding the frame and keeping only mangled text would lose it for
 * good.
 *
 * <p>Runs inside the bot's own task queue, so it never contends with the
 * emulator the way an external scraper would.
 *
 * <p><b>Not yet verified against the live game.</b> Coordinates below were
 * measured from a real 720x1280 chat screenshot, but this task has not been
 * executed end to end - the emulator was in use. Treat the navigation taps as
 * unproven until a live run confirms them.
 */
public class bg_chatcapture extends DelayedTask implements CustomTaskConfigurable {

    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(20);
    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Chat entry point: the globe/chat strip along the bottom of the World view. */
    private static final PointData CHAT_OPEN = new PointData(43, 1135);

    /** Channel tabs across the top of the chat panel. */
    private static final PointData TAB_WORLD = new PointData(132, 116);
    private static final PointData TAB_ALLIANCE = new PointData(360, 117);

    private static final PointData CHAT_CLOSE = new PointData(44, 40);

    /**
     * The scrollable message area. Excludes the header/tabs above and the
     * compose bar below so a scroll gesture cannot land on either.
     */
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1150;
    private static final int FEED_X = 360;

    /**
     * Message text is light on a dark navy panel. Left unrestricted - unlike
     * the HUD there is no useful character whitelist for arbitrary chat, and
     * imposing one would mangle non-English text rather than improve it.
     */
    private static final TesseractSettingsData CHAT_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .pageAnalysis(PageAnalysis.UNIFORM_BLOCK)
                    .stripBackground(false)
                    .build();

    private Duration interval = DEFAULT_INTERVAL;

    public bg_chatcapture(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time: the queue compares against LocalDateTime.now(), and a UTC
        // instant here would push the first run out by the UTC offset.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_chatcapture";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        Integer hours = settings.getFollowUpDelayHours();
        // The UI exposes hours, but chat is worth sampling more often than
        // that; treat the value as "every N * 20 minutes" and default to 20.
        interval = hours != null && hours > 0
                ? Duration.ofMinutes(20L * hours)
                : DEFAULT_INTERVAL;

        String first = settings.getFirstExecutionUtc();
        if (first != null && !first.isBlank()) {
            try {
                reschedule(LocalDateTime.parse(first, UTC_INPUT_FORMATTER)
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDateTime());
            } catch (RuntimeException e) {
                logWarning("bg_chatcapture | Unparseable first-execution time '" + first + "', starting now.");
            }
        }
    }

    @Override
    protected void execute() {
        logInfo("bg_chatcapture | Opening chat.");

        tapPoint(CHAT_OPEN);
        sleepTask(1200L);

        List<Map<String, Object>> captured = new ArrayList<>();
        captured.addAll(captureChannel("world", TAB_WORLD));
        captured.addAll(captureChannel("alliance", TAB_ALLIANCE));

        tapPoint(CHAT_CLOSE);
        sleepTask(500L);

        if (captured.isEmpty()) {
            logWarning("bg_chatcapture | Nothing captured - chat may not have opened. Skipping write.");
            scheduleNext();
            return;
        }

        for (Map<String, Object> row : captured) {
            appendRow(toJson(row));
        }
        logInfo("bg_chatcapture | Captured " + captured.size() + " chat frame(s).");
        scheduleNext();
    }

    /**
     * Captures one channel: select its tab, then walk backwards through
     * history. Scrolls up (older) first so a burst of new messages arriving
     * mid-capture cannot cause the same screen to be recorded twice.
     */
    private List<Map<String, Object>> captureChannel(String channel, PointData tab) {
        List<Map<String, Object>> rows = new ArrayList<>();

        tapPoint(tab);
        sleepTask(1000L);

        for (int i = 0; i < SCROLL_STEPS; i++) {
            String stamp = LocalDateTime.now(ZoneOffset.UTC).format(FILE_STAMP);
            Path shot = framesDir().resolve(channel + "-" + stamp + "-" + i + ".png");

            if (!saveFrame(shot)) {
                logWarning("bg_chatcapture | Could not save frame for " + channel + "; stopping this channel.");
                break;
            }

            String text = readStringValue(
                    new PointData(0, FEED_TOP),
                    new PointData(720, FEED_BOTTOM),
                    CHAT_TEXT_SETTINGS);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
            row.put("channel", channel);
            row.put("frame", framesDir().relativize(shot).toString());
            // Recorded verbatim, including whatever OCR got wrong. The frame is
            // the source of truth; this is a convenience index for searching.
            row.put("ocrText", text == null ? "" : text);
            rows.add(row);

            if (i < SCROLL_STEPS - 1) {
                swipeUpThroughHistory();
            }
        }
        return rows;
    }

    private void swipeUpThroughHistory() {
        // Downward drag = scroll back through older messages.
        swipe(new PointData(FEED_X, FEED_TOP + 120),
                new PointData(FEED_X, FEED_BOTTOM - 120));
        sleepTask(700L);
    }

    private static final int SCROLL_STEPS = 3;

    private boolean saveFrame(Path target) {
        try {
            Files.createDirectories(target.getParent());
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                return false;
            }
            // captureScreen hands back a raw framebuffer; TesseractOcrProvider
            // already owns the conversion (it handles both 16- and 32-bpp
            // layouts), so reuse it rather than decoding the buffer again here.
            BufferedImage image = TesseractOcrProvider.toBufferedImage(frame);
            if (image == null) {
                return false;
            }
            return ImageIO.write(image, "png", target.toFile());
        } catch (IOException | RuntimeException e) {
            logError("bg_chatcapture | Frame save failed: " + e.getMessage());
            return false;
        }
    }

    private Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }

    private Path framesDir() {
        return baseDir().resolve("frames");
    }

    private void appendRow(String json) {
        try {
            Files.createDirectories(baseDir());
            Files.write(baseDir().resolve("chat.jsonl"),
                    (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logError("bg_chatcapture | Could not append chat log: " + e.getMessage());
        }
    }

    private void scheduleNext() {
        setRecurring(true);
        reschedule(LocalDateTime.now().plus(interval));
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** Chat text is user-authored, so newlines and quotes must be escaped. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n")
                .replace("\t", " ");
    }
}
