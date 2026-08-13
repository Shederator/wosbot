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
 * <p><b>Sender/message structure.</b> Each capture now parses the OCR'd
 * feed into actual {@code {sender, text}} pairs at capture time (see
 * {@link #parseMessages}), not as a flat list of lines left for some
 * downstream process to guess boundaries from later - that guessing is
 * exactly what was merging different people's messages together. A line
 * matching {@link #SENDER_LINE_PATTERN} opens a new message; every
 * following line (handles wrapped multi-line messages) belongs to it,
 * until the next sender line or end of capture.
 *
 * <p>Live-verified 2026-08-06 against real saved captures from this
 * account's own history (frames + OCR'd lines under
 * {@code telemetry/chat/frames/} and {@code chat.jsonl}) - not guessed.
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

    // matt, 2026-08-06: OCR region now EXCLUDES the avatar column (x < ~112
    // on a live capture - avatar art + rank badge). Feeding player-portrait
    // images into Tesseract as if they were text was a real source of the
    // garbage glyphs polluting every capture. Sender name + message bubble
    // both live to the right of the avatar, so this loses nothing.
    private static final int FEED_LEFT = 112;
    private static final int FEED_RIGHT = 710;

    private static final int MAX_SCROLL_BACK = 8;

    /**
     * matt, 2026-08-06: two real bugs fixed here, found by comparing this
     * task's actual saved output against a live screenshot of the real chat
     * UI:
     * (1) TesseractOcrProvider hardcoded "eng" regardless of what any caller
     *     configured. Tried eng+chi_sim first (chi_sim being the only extra
     *     language pack already bundled) but live evidence was net-negative:
     *     small UI icons/badges started getting misread AS Chinese glyphs
     *     (a stray "全" appearing in otherwise-Portuguese/English text,
     *     verified against real captures) - CJK's huge glyph set is more
     *     prone to false-positive-matching an icon shape than English's
     *     small character set is. Reverted to eng-only. Translation for
     *     genuinely non-English text happens downstream in
     *     chat_summarize.py instead (Google Translate, not local OCR
     *     language packs) - see that file for why that's the more robust
     *     place for it.
     * (2) TesseractOcrProvider's recognizeText() unconditionally stripped
     *     ALL newlines before returning, which is exactly right for a
     *     single HUD value but was quietly flattening this task's entire
     *     multi-message chat panel into one run-on string with no line
     *     boundaries at all - that's the direct cause of captures coming
     *     back as one giant garbled blob mixing several people's messages.
     *     preserveLineBreaks(true) keeps Tesseract's real line segmentation.
     */
    private static final TesseractSettingsData CHAT_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .pageAnalysis(PageAnalysis.UNIFORM_BLOCK)
                    .stripBackground(false)
                    .language("eng")
                    .preserveLineBreaks(true)
                    .build();

    /**
     * A sender-HEADER line (the bubble label above a message) looks like
     * "VIP4 [INF]Abu Ibrahim" or "[INF]Abu Ibrahim" or "Abu Ibrahim" -
     * short, no sentence-like punctuation, often with a VIP tier and/or a
     * bracketed alliance tag. Message text, by contrast, is free-form
     * prose. Bracket/paren accepted interchangeably on either side
     * ("[INF)Mrs_Lasanha" was observed live - Tesseract mixing them up on
     * a real capture) since that's an OCR-glyph-confusion issue, not a
     * different UI element.
     */
    private static final java.util.regex.Pattern SENDER_HEADER_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(VIP\\d+\\s*)?([\\[(][A-Za-z0-9]{2,5}[\\])]\\s*)?[\\p{L}0-9_.'\\- ]{2,24}$");

    /**
     * matt, 2026-08-06: added after live evidence the header-only pattern
     * above was missing a second, equally common format - the small gray
     * "reply preview" strip under a bubble, which renders inline as
     * "Name: message" on ONE line rather than name-then-text on separate
     * lines (e.g. "Kratos: Mas agora k so criar tropa..." seen live,
     * completely missed by the header pattern since it never appears alone
     * on its own line). This is checked as a distinct, self-contained
     * message: sender is the part before the colon, text is everything
     * after - it does not open a new multi-line block the way a header
     * does, since a reply preview is always exactly one line.
     */
    private static final java.util.regex.Pattern INLINE_SENDER_PATTERN =
            java.util.regex.Pattern.compile(
                    "^([\\p{L}0-9_.'\\- ]{2,24}):\\s+(.+)$");

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

        int channelCount = (includeWorld ? 1 : 0) + (includeAlliance ? 1 : 0) + (includePersonal ? 1 : 0);
        logInfo("ChatCaptureRoutine | Captured " + totalNew + " new frame(s) across " + channelCount + " channel(s).");

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
        List<ChatMessage> newFrontier = null;

        int saved = 0;
        for (int i = 0; i < MAX_SCROLL_BACK; i++) {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                logWarning("ChatCaptureRoutine | Could not capture a frame for " + channel + "; stopping this channel.");
                break;
            }
            BufferedImage image = TesseractOcrProvider.toBufferedImage(frame);

            String rawText;
            try {
                // Direct call, bypassing readStringValue()'s indirection -
                // that path doesn't expose preserveLineBreaks/language, and
                // this task already holds its own frame to pass through.
                rawText = TesseractOcrProvider.recognizeText(
                        frame,
                        new PointData(FEED_LEFT, FEED_TOP), new PointData(FEED_RIGHT, FEED_BOTTOM),
                        CHAT_TEXT_SETTINGS);
            } catch (Exception e) {
                logWarning("ChatCaptureRoutine | OCR failed for " + channel + ": " + e.getMessage());
                rawText = null;
            }

            List<String> lines = cleanLines(rawText);
            List<ChatMessage> messages = parseMessages(lines);
            String signature = signatureOf(messages);

            if (i == 0) {
                newFrontier = messages;
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

            if (!messages.isEmpty()) {
                saveFrame(channel, i, image, messages, signature);
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

    /** One parsed chat message: who sent it (best-effort) and what it says. */
    private record ChatMessage(String sender, String text) {}

    /**
     * Groups OCR'd lines into {sender, text} pairs. A line matching
     * {@link #SENDER_LINE_PATTERN} starts a new message; subsequent lines
     * (handles a message that wraps across multiple physical lines) are
     * appended to that message's text until the next sender line appears.
     * Lines before the first recognized sender (page furniture, a message
     * cut off by scroll position) are dropped rather than guessed at.
     */
    private List<ChatMessage> parseMessages(List<String> lines) {
        List<ChatMessage> out = new ArrayList<>();
        String currentSender = null;
        StringBuilder currentText = null;

        for (String line : lines) {
            var inlineMatch = INLINE_SENDER_PATTERN.matcher(line);
            if (inlineMatch.matches()) {
                // Self-contained "Name: text" (reply-preview strip) - closes
                // whatever multi-line header block was open, then stands as
                // its own complete message immediately.
                if (currentSender != null && currentText != null && currentText.length() > 0) {
                    out.add(new ChatMessage(currentSender, currentText.toString().trim()));
                }
                out.add(new ChatMessage(inlineMatch.group(1).trim(), inlineMatch.group(2).trim()));
                currentSender = null;
                currentText = null;
            } else if (SENDER_HEADER_PATTERN.matcher(line).matches()) {
                if (currentSender != null && currentText != null && currentText.length() > 0) {
                    out.add(new ChatMessage(currentSender, currentText.toString().trim()));
                }
                currentSender = line;
                currentText = new StringBuilder();
            } else if (currentSender != null) {
                if (currentText.length() > 0) {
                    currentText.append(' ');
                }
                currentText.append(line);
            }
            // else: text before any recognized sender - dropped, not guessed.
        }
        if (currentSender != null && currentText != null && currentText.length() > 0) {
            out.add(new ChatMessage(currentSender, currentText.toString().trim()));
        }
        return out;
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

    private String signatureOf(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(m.sender()).append(':').append(m.text()).append(" | ");
        }
        return sb.toString();
    }

    private void saveFrame(String channel, int scrollIndex, BufferedImage image, List<ChatMessage> messages, String signature) {
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

        List<Map<String, Object>> messageRows = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> mr = new LinkedHashMap<>();
            mr.put("sender", m.sender());
            mr.put("text", m.text());
            messageRows.add(mr);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        row.put("channel", channel);
        row.put("mode", mode);
        row.put("frame", baseDir().relativize(shot).toString().replace('\\', '/'));
        // matt, 2026-08-06: was a flat "lines" array with no sender/message
        // structure at all - that's what the downstream Python summarizer
        // was trying (and failing) to reconstruct with regex. Now the
        // capture itself owns that structure (see parseMessages above).
        row.put("messages", messageRows);
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
                // matt, 2026-08-06: "messages" is now a List<Map<String,Object>>
                // (one map per {sender, text} pair) - the old version assumed
                // every list item was a plain string and would have serialized
                // each message as its Java toString(), not valid JSON.
                if (item instanceof Map<?, ?> m) {
                    sb.append(toJson((Map<String, Object>) m));
                } else {
                    sb.append('"').append(escape(String.valueOf(item))).append('"');
                }
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
