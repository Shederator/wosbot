package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

/**
 * Captures World, Alliance, and Personal chat on a schedule for the Whiteout
 * dashboard.
 *
 * <p><b>Capture is deliberately separated from interpretation.</b> Chat is
 * multilingual, full of emoji and sticker-only messages, and Tesseract handles
 * that far worse than it handles the numeric HUD - so each capture saves the
 * raw frame alongside a best-effort OCR pass rather than OCR text alone. A
 * poor OCR result then costs nothing: the frame is still on disk to be read
 * properly later. The {@code CHAT_CAPTURE_MODE_STRING} setting (transcript vs.
 * summary) is likewise a preference recorded for whatever writes the dashboard
 * afterward - producing an actual summary needs an AI pass over the
 * transcript, which is not something this bot can do with OCR alone.
 *
 * <p><b>Diffing.</b> Each run starts at the newest messages and, per channel,
 * remembers a signature of what it captured. The next run compares its first
 * frame against that signature: identical means nothing changed and the run
 * stops immediately; different means it scrolls back through history, saving
 * each new frame, until it recognizes a frame it already captured last time
 * or hits a safety cap. This keeps repeat runs from re-saving the same
 * history every cycle.
 *
 * <p><b>NOT YET LIVE-VERIFIED.</b> Coordinates are carried over from the
 * measured 720x1280 chat screenshots used to build the original
 * {@code bg_chatcapture} custom-task prototype, plus a freshly measured
 * Personal tab position. The diff/dedup logic itself has not been run against
 * the live game - flagged honestly per matt's "scaffold it, then we test"
 * instruction, same as Cryptid Hosting was before its own live pass.
 */
public class ChatCaptureRoutine extends DelayedTask {

    private static final int DEFAULT_FREQUENCY_MINUTES = 30;

    /** Chat entry point: the globe/chat icon along the bottom of the World view. */
    private static final PointData CHAT_OPEN = new PointData(43, 1135);

    private static final PointData TAB_WORLD = new PointData(132, 116);
    private static final PointData TAB_ALLIANCE = new PointData(360, 117);
    /** Measured live this session, unlike World/Alliance which predate it. */
    private static final PointData TAB_PERSONAL = new PointData(588, 117);

    private static final PointData CHAT_CLOSE = new PointData(44, 40);

    /** Scrollable message area - excludes the tab header and the compose bar. */
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1150;
    private static final int FEED_X = 360;

    private static final int MAX_SCROLL_BACK = 8;

    /**
     * Message text is light on a dark navy panel. No character whitelist -
     * unlike the HUD there is no useful restriction for arbitrary chat, and
     * imposing one would mangle non-English text rather than improve it.
     */
    private static final TesseractSettingsData CHAT_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .pageAnalysis(PageAnalysis.UNIFORM_BLOCK)
                    .stripBackground(false)
                    .build();

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private int frequencyMinutes = DEFAULT_FREQUENCY_MINUTES;
    private boolean includeWorld = true;
    private boolean includeAlliance = true;
    private boolean includePersonal = false;
    private boolean filterNoise = true;
    private String mode = "TRANSCRIPT";

    public ChatCaptureRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time - the queue compares against LocalDateTime.now(); a UTC
        // instant here would silently defer the first run by the UTC offset.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "chat_capture";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    private void loadSettings() {
        Integer freq = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_FREQUENCY_MINUTES_INT, Integer.class);
        frequencyMinutes = freq != null && freq > 0 ? freq : DEFAULT_FREQUENCY_MINUTES;

        includeWorld = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_WORLD_BOOL, Boolean.class));
        includeAlliance = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL, Boolean.class));
        includePersonal = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_PERSONAL_BOOL, Boolean.class));
        filterNoise = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_FILTER_NOISE_BOOL, Boolean.class));

        String storedMode = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_MODE_STRING, String.class);
        mode = storedMode != null && !storedMode.isBlank() ? storedMode : "TRANSCRIPT";
    }

    @Override
    protected void execute() {
        loadSettings();

        if (!includeWorld && !includeAlliance && !includePersonal) {
            logInfo("ChatCaptureRoutine | No channels selected; nothing to capture.");
            reschedule(LocalDateTime.now().plusMinutes(frequencyMinutes));
            setRecurring(true);
            return;
        }

        logInfo("ChatCaptureRoutine | Opening chat.");
        tapPoint(CHAT_OPEN);
        sleepTask(1200L);

        int totalNew = 0;
        if (includeWorld) {
            totalNew += captureChannel("world", TAB_WORLD);
        }
        if (includeAlliance) {
            totalNew += captureChannel("alliance", TAB_ALLIANCE);
        }
        if (includePersonal) {
            totalNew += captureChannel("personal", TAB_PERSONAL);
        }

        tapPoint(CHAT_CLOSE);
        sleepTask(500L);

        logInfo("ChatCaptureRoutine | Captured " + totalNew + " new frame(s) across "
                + (includeWorld ? 1 : 0) + (includeAlliance ? 1 : 0) + (includePersonal ? 1 : 0) + " channel(s).");

        setRecurring(true);
        reschedule(LocalDateTime.now().plusMinutes(frequencyMinutes));
    }

    /**
     * Captures one channel from newest backward until either the diff catches
     * up to previously-seen content or the safety cap is hit. Returns the
     * number of genuinely new frames saved.
     */
    private int captureChannel(String channel, PointData tab) {
        tapPoint(tab);
        sleepTask(1000L);

        ChatDiffState previous = loadState(channel);
        Set<String> previousSignatures = previous.frameSignatures;
        Set<String> thisRunSignatures = new LinkedHashSet<>();
        List<String> newFrontier = null;

        int saved = 0;
        for (int i = 0; i < MAX_SCROLL_BACK; i++) {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                logWarning("ChatCaptureRoutine | Could not capture a frame for " + channel + "; stopping this channel.");
                break;
            }
            BufferedImage image = TesseractOcrProvider.toBufferedImage(frame);

            String rawText = readStringValue(
                    new PointData(0, FEED_TOP), new PointData(720, FEED_BOTTOM), CHAT_TEXT_SETTINGS);
            List<String> lines = cleanLines(rawText);
            String signature = signatureOf(lines);

            if (i == 0) {
                newFrontier = lines;
                if (signature.equals(previous.frontierSignature)) {
                    // Nothing has changed since last run's newest capture -
                    // stop immediately rather than re-walking history that is
                    // guaranteed to already be saved.
                    logInfo("ChatCaptureRoutine | " + channel + ": no new messages since last check.");
                    break;
                }
            } else if (previousSignatures.contains(signature)) {
                // Walked back into territory the previous run already
                // captured - everything before this point is already saved.
                break;
            }

            if (!lines.isEmpty()) {
                saveFrame(channel, i, image, lines, signature);
                thisRunSignatures.add(signature);
                saved++;
            }

            swipeUpThroughHistory();
        }

        // Persist this run's frontier (for the fast "nothing new" check) and
        // the signatures walked this run (for next run's "have I reached
        // already-seen content" check). If nothing new was found, keep the
        // previous state as-is rather than overwriting it with an empty walk.
        if (newFrontier != null && saved > 0) {
            saveState(channel, signatureOf(newFrontier), thisRunSignatures);
        }

        return saved;
    }

    private void swipeUpThroughHistory() {
        // Downward drag reveals content above the current view, i.e. older
        // messages - the opposite of how a page-down gesture reads.
        swipe(new PointData(FEED_X, FEED_TOP + 120), new PointData(FEED_X, FEED_BOTTOM - 120));
        sleepTask(700L);
    }

    /**
     * Splits OCR output into lines and, when the filter is on, drops any line
     * that has no letters or digits after stripping punctuation - the pattern
     * an emote/sticker-only message leaves behind. This is plain string
     * matching, not language understanding, so it is applied live rather than
     * just tagged as a preference for later.
     */
    private List<String> cleanLines(String rawText) {
        List<String> lines = new ArrayList<>();
        if (rawText == null) {
            return lines;
        }
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (filterNoise && trimmed.replaceAll("[^\\p{L}\\p{N}]", "").length() < 2) {
                continue;
            }
            lines.add(trimmed);
        }
        return lines;
    }

    private String signatureOf(List<String> lines) {
        return String.join(" | ", lines);
    }

    private void saveFrame(String channel, int scrollIndex, BufferedImage image, List<String> lines, String signature) {
        String stamp = LocalDateTime.now(ZoneOffset.UTC).format(FILE_STAMP);
        Path framesDir = baseDir().resolve("frames");
        Path shot = framesDir.resolve(channel + "-" + stamp + "-" + scrollIndex + ".png");

        try {
            Files.createDirectories(framesDir);
            ImageIO.write(image, "png", shot.toFile());
        } catch (IOException e) {
            logError("ChatCaptureRoutine | Frame save failed for " + channel + ": " + e.getMessage());
            return;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        row.put("channel", channel);
        row.put("mode", mode);
        row.put("frame", baseDir().relativize(shot).toString().replace('\\', '/'));
        row.put("lines", lines);
        row.put("signature", signature);
        appendRow(toJson(row));
    }

    // ── diff state persistence ──────────────────────────────────────────

    private record ChatDiffState(String frontierSignature, Set<String> frameSignatures) {}

    private Path stateFile(String channel) {
        return baseDir().resolve("state-" + channel + ".json");
    }

    private ChatDiffState loadState(String channel) {
        Path file = stateFile(channel);
        if (!Files.exists(file)) {
            return new ChatDiffState("", Set.of());
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String frontier = extractJsonString(json, "frontierSignature");
            Set<String> signatures = new LinkedHashSet<>(extractJsonStringArray(json, "frameSignatures"));
            return new ChatDiffState(frontier == null ? "" : frontier, signatures);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not read diff state for " + channel + ": " + e.getMessage());
            return new ChatDiffState("", Set.of());
        }
    }

    private void saveState(String channel, String frontierSignature, Set<String> frameSignatures) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"frontierSignature\":\"").append(escape(frontierSignature)).append("\",");
        sb.append("\"frameSignatures\":[");
        boolean first = true;
        for (String s : frameSignatures) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(s)).append('"');
        }
        sb.append("]}");
        try {
            Files.createDirectories(baseDir());
            Files.writeString(stateFile(channel), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not write diff state for " + channel + ": " + e.getMessage());
        }
    }

    /**
     * Deliberately not a real JSON parser - the state file is written by this
     * same class in a fixed shape, so a small hand-rolled reader is enough and
     * avoids pulling in a JSON dependency for two fields.
     */
    private static String extractJsonString(String json, String key) {
        var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> out = new ArrayList<>();
        var arrayMatch = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)]",
                java.util.regex.Pattern.DOTALL).matcher(json);
        if (!arrayMatch.find()) {
            return out;
        }
        var itemMatch = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arrayMatch.group(1));
        while (itemMatch.find()) {
            out.add(unescape(itemMatch.group(1)));
        }
        return out;
    }

    private Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }

    private void appendRow(String json) {
        try {
            Files.createDirectories(baseDir());
            Files.write(baseDir().resolve("chat.jsonl"),
                    (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logError("ChatCaptureRoutine | Could not append chat log: " + e.getMessage());
        }
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
            sb.append(toJsonValue(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(item))).append('"');
            }
            return sb.append(']').toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }

    /** Chat text is user-authored, so newlines and quotes must be escaped. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n")
                .replace("\t", " ");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
