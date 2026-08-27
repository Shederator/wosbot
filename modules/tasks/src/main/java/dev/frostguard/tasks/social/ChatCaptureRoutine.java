package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.chat.ChatTranscriptStore;
import dev.frostguard.engine.chat.ChatTranslator;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Captures World, Alliance and Personal chat on a schedule and stores it as a readable transcript.
 *
 * <p><b>The frame is read whole, and position tells the rest.</b> Reading region by region meant
 * choosing the boundary of every sender line and every bubble before recognising them, and each of
 * those boundaries was an offset from an avatar edge that moves with crowns and rank badges. A few
 * pixels of drift clipped the tops of glyphs, so a line that plainly reads {@code [INF]Mini TyTy}
 * came back as {@code UNF jMini TyTy}; when a band landed high the sender line fell into the bubble
 * instead and the message was stored with no author. Recognised whole, the same frame reports every
 * line with where it sat, and the two kinds of line separate themselves by the column they start
 * in. Measured over 20 live frames that removed every invented name -- seven distinct senders, all
 * real players, against fourteen of which half were wreckage -- and it costs one recognition per
 * screen rather than a dozen.
 *
 * <p><b>Frames do not survive the pass.</b> Each screenshot is roughly 394KB, and the schedule this
 * task runs -- around thirty screens across three channels every twenty minutes -- lands near 6,500
 * frames a day, about 2.5GB. The same content as text is a few megabytes before de-duplication and
 * far less after. So a frame is deleted the moment its rows are safely stored, and kept only when
 * storing failed and the image is the sole remaining evidence.
 *
 * <p><b>Translation is network-only.</b> Frostguard ships to people who did not ask for a
 * translation stack, so nothing is downloaded and no key is needed; see {@link ChatTranslator}.
 */
public class ChatCaptureRoutine extends DelayedTask {

    private static final int DEFAULT_FREQUENCY_MINUTES = 30;
    private static final int DEFAULT_SCROLL_BACK = 30;
    private static final int DEFAULT_RETENTION_DAYS = 30;

    /** Distinct phrases held before the least-used are dropped. Chat repeats heavily, so a modest
     *  cache absorbs most of the traffic a pass would otherwise put on the network. */
    private static final int TRANSLATION_CACHE_SIZE = 5000;

    /**
     * Consecutive screens yielding nothing new before the walk gives up.
     *
     * <p>Two was too eager. A screen can legitimately yield nothing -- a run of stickers, a couple
     * of unreadable bubbles, a timestamp divider -- and stopping there cuts the scroll-back short
     * while reporting a clean finish. The pass is bounded by scrollBack anyway, so the cost of
     * being generous here is a few extra screens, and the cost of being wrong is silently
     * capturing a fraction of the history.
     */
    private static final int BARREN_SCREENS_BEFORE_STOP = 6;

    /** Chat entry point: the globe/chat icon along the bottom of the World view. */
    private static final PointData CHAT_OPEN = new PointData(43, 1135);

    private static final PointData TAB_WORLD = new PointData(132, 116);
    private static final PointData TAB_ALLIANCE = new PointData(360, 117);
    private static final PointData TAB_PERSONAL = new PointData(588, 117);
    private static final PointData CHAT_CLOSE = new PointData(44, 40);

    /** Scrollable message area -- excludes the tab header and the compose bar. */
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1150;
    private static final int FEED_X = 360;

    /** The feed as a rectangle, for reading the whole thing in one recognition. */
    /**
     * The strip of the feed that actually holds text.
     *
     * <p>It starts at the text column, not at the edge of the screen. Reading from x=0 put the
     * avatar, its rank badge and the bubble's own left border inside the recognition, and the
     * reader turned them into letters: "En 1:45 hora batalla" came back as "İd EntA4S hora
     * batalla", with the bubble edge fused onto the first word, and a bare "y" appeared as a
     * message of its own where a piece of bubble art sat alone on a line. None of that is text and
     * none of it needed reading.
     *
     * <p>Sender lines begin around x=139, so the column starts a little inside that and still
     * catches them. The right edge stops before the per-message translate control.
     */
    private static final int TEXT_COLUMN_LEFT = 132;
    private static final int TEXT_COLUMN_RIGHT = 700;

    private static final PointData FEED_TOP_LEFT = new PointData(TEXT_COLUMN_LEFT, FEED_TOP);
    private static final PointData FEED_BOTTOM_RIGHT = new PointData(TEXT_COLUMN_RIGHT, FEED_BOTTOM);

    /**
     * Reader configuration for a chat region.
     *
     * <p>English-only is deliberate and evidence-backed: adding chi_sim was net-negative live,
     * because CJK's much larger glyph set false-matches small UI icons and badges as characters,
     * putting stray glyphs into otherwise-Latin text. Foreign chat is handled downstream by
     * translating the read text rather than by widening the reader.
     */
    /**
     * A second reading, in the scripts the first one cannot see.
     *
     * <p>Loading every script at once measured worse, not better: with ten languages the reader
     * hedges across alphabets and mangles Latin it had been reading correctly, and sender lines
     * found on a fixed set of frames fell from 85 to 59 while the pass took two and a half times
     * as long. So the Latin languages stay the default and this is used only on the lines the
     * first pass could not make a word out of -- a Chinese message comes back from a Latin-only
     * reader as a handful of stray punctuation, which is easy to recognise and cheap to re-read.
     */
    private static final OcrSettingsData CHAT_CJK_SETTINGS =
            OcrSettingsData.assembler()
                    // A block, not a line, though it is handed one row. Measured both ways on a
                    // live Chinese bubble through the Tesseract CLI: as a block it reads, as a
                    // single line it returns an empty page. The reasoning that it "is" one line is
                    // sound and the reader disagrees, so the reader wins.
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    .language("chi_sim+jpn+kor")
                    .build();

    /**
     * The Cyrillic reader, spent only on rows whose Latin reading is the wrong shape.
     *
     * <p>Russian is one of the sixteen languages the game ships in. It cannot go in the pass above:
     * measured on a fixed set of frames, adding it there put a Cyrillic "м" into the middle of a
     * Spanish sentence and brought back a junk row the filters had removed, because the reader
     * hedges between two alphabets that share letterforms. Kept apart, it is only ever asked about
     * a line that already looks like Cyrillic read as Latin.
     */
    private static final OcrSettingsData CHAT_CYRILLIC_SETTINGS =
            OcrSettingsData.assembler()
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    .language("rus")
                    .build();

    private static final OcrSettingsData CHAT_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .textLayout(TextLayout.TEXT_BLOCK)
                    .stripBackground(false)
                    // Measured over 20 live alliance frames: reading with Spanish, Portuguese
                    // and Turkish alongside English lifted the text recognised by 19% and the
                    // sender lines found by 29% (66 to 85), and made a name error disappear that
                    // no amount of cleanup had shifted -- "JAthenaRyu" was English being forced
                    // onto Spanish text. Adding further languages measured WORSE, not better:
                    // ten of them dropped senders to 59 and cost 2.6x the time, because Tesseract
                    // hedges across alphabets and mangles Latin it was reading correctly. More
                    // scripts belong behind per-message script detection, not in this list.
                    .language("eng+spa+por+tur")
                    .preserveLineBreaks(true)
                    .build();

    /** Taps at the close control before falling back to Back. */
    private static final int CHAT_CLOSE_ATTEMPTS = 3;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private int frequencyMinutes = DEFAULT_FREQUENCY_MINUTES;
    private boolean includeWorld = true;
    private boolean includeAlliance = true;
    private boolean includePersonal = false;
    private String mode = "TRANSCRIPT";
    private int scrollBack = DEFAULT_SCROLL_BACK;

    /**
     * How long one channel gets before the walk gives up and leaves the rest for next time.
     *
     * <p>The real constraint is the schedule, not the screen count. Two channels at this budget fit
     * inside five minutes, which sits comfortably inside a half-hourly pass however slow reading
     * happens to be that day -- and a pass that overruns its own schedule is the failure that
     * compounds, because the next one starts late with more to catch up on.
     *
     * <p>Nothing is lost by stopping early that would not also have been lost by running long. The
     * walk reads newest first, so what it does not reach is the oldest end, and the next pass
     * starts from the newest again.
     */
    private static final long CHANNEL_TIME_BUDGET_MS = 150_000L;

    /**
     * As far back as a walk is ever allowed to go, whatever the clock says.
     *
     * <p>Purely a runaway guard behind the time budget. Reaching it means the duplicate check has
     * failed and the walk would never stop on its own.
     */
    private static final int SAFETY_SCREEN_LIMIT = 500;
    private int retentionDays = DEFAULT_RETENTION_DAYS;

    private ChatTranscriptStore store;

    /**
     * The local reading service, or null when it was never configured.
     *
     * <p>Held rather than made per pass so the HTTP client and its connection pool survive between
     * captures.
     */
    private dev.frostguard.vision.ocr.PaddleOcrClient paddle;

    /** SERVICE or JAVA: which reader this profile turns screens into text with. */
    private String readerChoice = "SERVICE";

    /** Reads in this process. Built once, because each model costs seconds to open. */
    private dev.frostguard.vision.ocr.OnnxOcrReader onnx;

    /**
     * Where the models are, whether this is a checkout or an installation.
     *
     * <p>It used to be {@code user.dir/tools/ocr/onnx} and nothing else, which is true when the
     * application is started from the top of a checkout and false the moment somebody installs it.
     * The installed layout puts them under {@code lib/ocr-models}, and the working directory is
     * wherever the shortcut happened to point. Walking up from the working directory looking for
     * either name is how the Tesseract data is already found, and this follows it rather than
     * inventing a second convention.
     *
     * @return the first directory holding the detector, or the checkout path so the failure names
     *         somewhere a person can actually look
     */
    private static java.nio.file.Path onnxDir() {
        if (resolvedModelDir != null) {
            return resolvedModelDir;
        }
        java.nio.file.Path here =
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (java.nio.file.Path up = here; up != null; up = up.getParent()) {
            for (java.nio.file.Path candidate : new java.nio.file.Path[] {
                    up.resolve("lib").resolve("ocr-models"),
                    up.resolve("tools").resolve("ocr").resolve("onnx")}) {
                if (dev.frostguard.vision.ocr.OnnxOcrReader.isAvailable(candidate)) {
                    resolvedModelDir = candidate;
                    return candidate;
                }
            }
        }
        return here.resolve("tools").resolve("ocr").resolve("onnx");
    }

    /** Found once. The walk touches the disk and the answer cannot change while this runs. */
    private static volatile java.nio.file.Path resolvedModelDir;

    /**
     * The reader this pass should use, or null when none can read.
     *
     * <p>The Java reader is preferred when asked for and falls back to the service rather than
     * failing: a missing model directory is a setup that has not been done, not a reason to lose a
     * pass of chat.
     */
    private dev.frostguard.vision.ocr.ChatTextReader reader() {
        if ("JAVA".equals(readerChoice)) {
            if (onnx == null && dev.frostguard.vision.ocr.OnnxOcrReader.isAvailable(onnxDir())) {
                try {
                    onnx = new dev.frostguard.vision.ocr.OnnxOcrReader(onnxDir());
                } catch (Exception e) {
                    logWarning("ChatCaptureRoutine | Could not open the in-process reader: "
                            + e.getMessage() + " -- falling back to the service.");
                }
            }
            if (onnx != null) {
                return onnx;
            }
            // Said once, and said loudly. The weights are not in the repository, so somebody who
            // clones it, builds it and turns chat capture on gets a transcript read by the
            // fallback while the settings screen says Java. Every accuracy figure that has been
            // measured is the in-process reader's; a pass that quietly used something else and
            // said nothing is worse than one that did not run, because it still produces a
            // transcript and the reader it names is wrong.
            if (!warnedAboutMissingModels) {
                warnedAboutMissingModels = true;
                logWarning("ChatCaptureRoutine | The in-process reader is selected but its models"
                        + " are not at " + onnxDir() + " -- reading with the fallback instead."
                        + " Accuracy will not match what the Java reader was measured at.");
            }
        }
        return paddle;
    }

    /** The missing-models warning is worth saying once a run, not once a screen. */
    private boolean warnedAboutMissingModels;

    /** Where the service listens. Loopback only -- nothing about this leaves the machine. */
    private static final String PADDLE_HOST = "127.0.0.1";
    private static final int PADDLE_PORT = 6975;
    private static final String PADDLE_LANG = "en";

    /**
     * Rows the reader was less sure of than this are not text.
     *
     * <p>Measured on twenty live screens: writing scores 0.94 to 1.00, and the ornaments, emoji and
     * frame corners it cannot read score 0.00 to 0.18. The threshold sits in the middle of a gap
     * wide enough that it does not have to be judged finely.
     */
    private static final double PADDLE_MIN_CONFIDENCE = 0.60;
    private ChatTranslator translator;

    public ChatCaptureRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time -- the queue compares against LocalDateTime.now(); a UTC instant here would
        // silently defer the first run by the UTC offset.
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

        String storedMode = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_MODE_STRING, String.class);
        mode = storedMode != null && !storedMode.isBlank() ? storedMode : "TRANSCRIPT";

        Integer back = profile.getConfig(ConfigurationKeyEnum.CHAT_CAPTURE_SCROLL_BACK_INT, Integer.class);
        scrollBack = back != null && back > 0 ? back : DEFAULT_SCROLL_BACK;

        Integer keep = profile.getConfig(ConfigurationKeyEnum.CHAT_TRANSCRIPT_RETENTION_DAYS_INT, Integer.class);
        retentionDays = keep != null && keep > 0 ? keep : DEFAULT_RETENTION_DAYS;

        boolean translate = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CHAT_TRANSLATE_TO_ENGLISH_BOOL, Boolean.class));
        translator = new ChatTranslator(translate, TRANSLATION_CACHE_SIZE);
        String choice = profile.getConfig(ConfigurationKeyEnum.CHAT_READER_STRING, String.class);
        readerChoice = choice == null || choice.isBlank() ? "SERVICE" : choice.trim().toUpperCase();
        // Separate transcripts, because the point of having two readers is to compare them and a
        // shared file would merge their answers into one indistinguishable feed.
        store = new ChatTranscriptStore(transcriptDir(readerChoice), ZoneId.systemDefault());
        Integer cacheMb = profile.getConfig(ConfigurationKeyEnum.CHAT_FRAME_CACHE_MB_INT,
                Integer.class);
        frameCache = new ChatFrameCache(baseDir(), cacheMb == null ? 0 : cacheMb);
        paddle = new dev.frostguard.vision.ocr.PaddleOcrClient(PADDLE_HOST, PADDLE_PORT);
    }

    @Override
    protected void execute() {
        // When this pass began, so the next one can be timed from here rather than from whenever
        // this one happens to finish.
        LocalDateTime began = LocalDateTime.now();
        loadSettings();

        if (!includeWorld && !includeAlliance && !includePersonal) {
            logInfo("ChatCaptureRoutine | No channels selected; nothing to capture.");
            setRecurring(true);
            reschedule(nextRunAfter(began));
            return;
        }

        // Learn what previous runs already wrote before the first overlapping screen arrives,
        // otherwise a restart re-appends the whole scroll-back it is about to re-read.
        try {
            store.primeFromDisk();
            int purged = store.purgeOlderThan(retentionDays);
            if (purged > 0) {
                logInfo("ChatCaptureRoutine | Dropped " + purged + " transcript day(s) past the "
                        + retentionDays + "-day window.");
            }
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not read the existing transcript: " + e.getMessage());
        }

        logInfo("ChatCaptureRoutine | Opening chat (" + mode + ", " + scrollBack + " screens back).");
        tapNear(CHAT_OPEN);
        sleepTask(1200L);

        int totalNew = 0;
        try {
            if (includeWorld) {
                totalNew += captureChannel("world", TAB_WORLD);
            }
            if (includeAlliance) {
                totalNew += captureChannel("alliance", TAB_ALLIANCE);
            }
            if (includePersonal) {
                totalNew += captureChannel("personal", TAB_PERSONAL);
            }
        } catch (RuntimeException failure) {
            // The queue retries a throwing task immediately and does not print the cause, so a
            // crash-loop shows up as the task restarting every few seconds with nothing to explain
            // it. Naming the failure here is the difference between a mystery and a stack trace.
            logError("ChatCaptureRoutine | Pass failed: " + failure, failure);
            throw failure;
        } finally {
            // Closing chat has to happen whether the pass succeeded, threw, or gave up. It used to
            // sit on the success path only, so a failure left the game parked on the chat window --
            // and the next task inherited it. Observed live: this routine crash-looped, and the
            // Intelligence routine that followed spent every attempt hunting a city building while
            // a chat panel filled the screen, reporting "Intel unreachable" for hours. A task that
            // cannot finish its own work still owes the next one a screen it recognises.
            closeChat();
        }

        int channelCount = (includeWorld ? 1 : 0) + (includeAlliance ? 1 : 0) + (includePersonal ? 1 : 0);
        String size;
        try {
            size = ChatTranscriptStore.humanSize(store.sizeBytes());
        } catch (IOException e) {
            size = "unknown";
        }
        logInfo("ChatCaptureRoutine | Stored " + totalNew + " new message(s) across " + channelCount
                + " channel(s). Transcript is now " + size + "; "
                + translator.cachedPhrases() + " phrase(s) cached.");

        setRecurring(true);
        reschedule(nextRunAfter(began));
    }

    /**
     * When the next pass is due, measured from when this one started.
     *
     * <p>Timed from the start rather than the finish because a pass is not instantaneous: it holds
     * the device for five minutes, and adding the interval to the end of that pushes every pass
     * later than the one before. Asked for every half hour, the passes drifted to thirty-six
     * minutes apart -- and would have kept drifting, an extra five minutes lost each time, until
     * the schedule bore no relation to what was set.
     *
     * <p>If a pass somehow overruns its own interval the next one is due immediately, which is the
     * honest answer: it is already late, and pretending otherwise would only make it later.
     */
    private LocalDateTime nextRunAfter(LocalDateTime began) {
        // Anchored to when this pass was due rather than to when it got going. Between the queue
        // picking the task up and the routine starting work there is half a minute of walking back
        // to the world map, and adding the interval to the later of those two moments pushes every
        // pass a little further out than the last -- half-hourly passes landed thirty-one minutes
        // apart, every time, for the same reason they used to land thirty-six.
        //
        // Measuring from the due time makes the cadence exact and self-correcting: a pass that
        // starts late still books the next one on the original beat rather than carrying its own
        // lateness forward.
        LocalDateTime anchor = dueAt != null ? dueAt : began;
        LocalDateTime due = anchor.plusMinutes(frequencyMinutes);
        LocalDateTime now = LocalDateTime.now();
        // A pass that overran its whole interval is already late; the next is due immediately, and
        // the beat restarts from here rather than from a time that has long gone.
        if (!due.isAfter(now)) {
            due = now;
        }
        dueAt = due;
        return due;
    }

    /** When this pass was due, so the next can be timed from the beat rather than from the work. */
    private LocalDateTime dueAt;

    /**
     * Walks one channel from the newest message backwards, storing what has not been seen before.
     *
     * @return how many genuinely new messages were stored
     */
    /**
     * Walks one channel: photograph the whole way back, then read what was photographed.
     *
     * <p>These used to be one loop -- capture a screen, read it, swipe, repeat -- which meant the
     * emulator was held for the reading as well as the scrolling. Reading is by far the larger
     * half: a screen takes about a second and a half to photograph and eight to fourteen seconds
     * to read, so nine tenths of the time the device was locked, it was locked waiting on OCR that
     * did not need it.
     *
     * <p>Split, the same two and a half minutes on the device buys ninety screens instead of
     * fifteen, and the reading happens afterwards from disk while the bot gets on with something
     * else. The cost is that the walk can no longer stop the moment it recognises a message it
     * already has -- knowing that requires reading -- so it photographs its whole budget and the
     * reader stops early instead. Frames past the point where the reader caught up are deleted
     * unread.
     */
    private int captureChannel(String channel, PointData tab) {
        List<Path> shots = "personal".equals(channel)
                ? photographConversations(tab)
                : photograph(channel, tab);
        if (shots.isEmpty()) {
            return 0;
        }
        handOffForReading(channel, shots);
        return 0;
    }

    /**
     * Photographs Personal, which is not a feed at all.
     *
     * <p>World and Alliance are one long conversation: open the tab and everything ever said is
     * behind a scroll. Personal is a list of people, and every message is a tap inside one of them.
     * Pointed at it, the walk that works for the other two scrolls a directory of contacts, decides
     * nothing is moving, and stops -- which is why "Personal capture" was a setting that captured
     * the names of the people who had written and none of what they wrote.
     *
     * <p>So this opens each conversation in turn, photographs it the way a feed is photographed,
     * and comes back out. The list is walked from the top: the game keeps it in order of most
     * recent message, so the conversations worth reading are the ones already at the top, and
     * stopping partway down costs the least interesting ones.
     *
     * <p>Every step checks that the screen actually changed. A tap that opens nothing, or a back
     * that lands somewhere unexpected, would otherwise leave the routine photographing whatever it
     * happens to be looking at and filing it as somebody's private messages.
     */
    private List<Path> photographConversations(PointData tab) {
        tapNear(tab);
        sleepTask(1200L);

        List<Path> shots = new ArrayList<>();
        Path dir = baseDir().resolve("pending")
                .resolve("personal-" + LocalDateTime.now().format(FILE_STAMP));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not make room for personal frames: "
                    + e.getMessage());
            return shots;
        }

        BufferedImage list = screenNow();
        if (list == null) {
            return shots;
        }
        long deadline = System.currentTimeMillis() + CHANNEL_TIME_BUDGET_MS;
        int opened = 0;
        int missed = 0;

        for (int row = 0; row < CONVERSATIONS_PER_PASS; row++) {
            if (System.currentTimeMillis() > deadline) {
                logInfo("ChatCaptureRoutine | personal: out of time after " + opened
                        + " conversation(s).");
                break;
            }
            int y = FIRST_ROW_CENTRE + row * ROW_PITCH;
            if (y > LIST_BOTTOM) {
                break;
            }

            tapNear(new PointData(ROW_TAP_X, y));
            sleepTask(1200L);
            BufferedImage inside = screenNow();
            if (inside == null || !feedMoved(list, inside)) {
                // Nothing opened, so there is nobody on this row. Whether a row is occupied was
                // first decided by measuring how much ink was in it, which does not work: on a
                // real list the sparsest occupied row -- a name, "Thanks", and a time -- came out
                // at 0.033 of its pixels and the gap between two rows at 0.028. Those are the same
                // number. Tapping is the test that actually answers the question, and the answer
                // it gives is not a threshold.
                if (++missed >= EMPTY_ROWS_BEFORE_STOPPING) {
                    break;
                }
                logInfo("ChatCaptureRoutine | personal: nothing on row " + (row + 1) + ".");
                continue;
            }
            missed = 0;

            opened++;
            int before = shots.size();
            photographOpenConversation(dir, shots, deadline);
            logInfo("ChatCaptureRoutine | personal: conversation " + opened + " -- "
                    + (shots.size() - before) + " screen(s).");

            tapNear(CHAT_CLOSE);
            sleepTask(1200L);
            BufferedImage back = screenNow();
            if (back == null || feedMoved(inside, back)) {
                list = back == null ? list : back;
            } else {
                // Still inside. One more back, and if that fails the pass ends here rather than
                // wandering through a stranger's messages tapping at things.
                tapNear(CHAT_CLOSE);
                sleepTask(1200L);
                BufferedImage second = screenNow();
                if (second == null || !feedMoved(inside, second)) {
                    logWarning("ChatCaptureRoutine | personal: could not get back to the list;"
                            + " stopping here.");
                    break;
                }
                list = second;
            }
        }

        logInfo("ChatCaptureRoutine | personal: photographed " + shots.size()
                + " screen(s) across " + opened + " conversation(s).");
        return shots;
    }

    /** Photographs one open conversation, newest first, scrolling back a little way. */
    private void photographOpenConversation(Path dir, List<Path> shots, long deadline) {
        BufferedImage previous = null;
        for (int i = 0; i < SCREENS_PER_CONVERSATION; i++) {
            if (System.currentTimeMillis() > deadline) {
                return;
            }
            BufferedImage image = screenNow();
            if (image == null) {
                return;
            }
            if (previous != null && !feedMoved(previous, image)) {
                // A short conversation reaches its beginning and stops moving. That is the end of
                // it, not a fault, so nothing is logged and nothing is retried.
                return;
            }
            try {
                Path out = dir.resolve(String.format("%04d.png", shots.size()));
                ImageIO.write(image, "png", out.toFile());
                shots.add(out);
            } catch (IOException e) {
                logWarning("ChatCaptureRoutine | Could not save a personal frame: "
                        + e.getMessage());
                return;
            }
            previous = image;
            swipeUpThroughHistory();
        }
    }

    private BufferedImage screenNow() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || !frame.isValid()) {
            return null;
        }
        return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
    }

    /**
     * How many conversations to open in one pass.
     *
     * <p>The list is in most-recent order, so the top is where anything new is. Six fits the time
     * budget with room for the scrolling inside each, and the seventh row is half off the screen
     * anyway.
     */
    private static final int CONVERSATIONS_PER_PASS = 6;

    /**
     * How far back to read inside one conversation.
     *
     * <p>Direct messages are short and the transcript already holds what was read last time, so
     * this only has to reach whatever arrived since. Four screens is a generous half hour of the
     * busiest private conversation anybody here has.
     */
    private static final int SCREENS_PER_CONVERSATION = 4;

    /** Middle of the first row in the conversation list, and the step down to the next. */
    private static final int FIRST_ROW_CENTRE = 396;
    private static final int ROW_PITCH = 140;

    /** Below this a row is off the bottom of the list. */
    private static final int LIST_BOTTOM = 1180;

    /** Rows are tapped on their text rather than their avatar, which has its own tap behaviour. */
    private static final int ROW_TAP_X = 400;

    /**
     * How many rows that open nothing before the list is taken to have ended.
     *
     * <p>Two rather than one, so a single tap that misses -- landing on a divider, or arriving
     * while the list is still settling -- does not cut the walk short of conversations that are
     * really there.
     */
    private static final int EMPTY_ROWS_BEFORE_STOPPING = 2;


    /**
     * Hands the photographs to the reader and returns, rather than waiting for it.
     *
     * <p>Splitting the walk freed the device but not the bot. Reading still ran on the task thread,
     * so the queue stayed blocked for as long as the reading took -- a pass photographed for five
     * minutes and then sat there for another twenty, and nothing else the bot was supposed to do
     * could start. Freeing the device is worth nothing if the schedule is still held.
     *
     * <p>Reading is now somebody else's problem: it happens on its own thread, off the queue, while
     * the bot gets on with the next task. Only one read runs at a time -- they share a transcript
     * file and a translator, and two at once would be racing over both -- so a pass whose reading
     * is still going when the next one photographs will queue behind it.
     */
    private void handOffForReading(String channel, List<Path> shots) {
        if (PENDING_READS.get() >= MAX_QUEUED_READS) {
            logWarning("ChatCaptureRoutine | " + channel + ": the reader is still working through"
                    + " earlier screens, so these " + shots.size() + " are being dropped rather"
                    + " than piled on. Reading is not keeping up with capturing.");
            clear(shots.get(0).getParent());
            return;
        }
        PENDING_READS.incrementAndGet();
        READER.submit(() -> {
            try {
                int stored = read(channel, shots);
                logInfo("ChatCaptureRoutine | " + channel + ": reading finished, " + stored
                        + " new message(s) stored.");
            } catch (RuntimeException e) {
                logError("ChatCaptureRoutine | " + channel + ": reading failed: " + e, e);
            } finally {
                PENDING_READS.decrementAndGet();
            }
        });
    }

    /**
     * The one thread that reads photographs, shared by every channel and every pass.
     *
     * <p>Single, not a pool. Readers append to the same transcript and share one translation cache,
     * and the OCR service is already using every core it is going to use -- a second reader would
     * contend for all three and finish no sooner.
     */
    private static final java.util.concurrent.ExecutorService READER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-reader");
                // Daemon so a half-read pass cannot keep the application from closing. The
                // photographs survive on disk either way.
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.atomic.AtomicInteger PENDING_READS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How far reading may fall behind capturing before screens start being dropped.
     *
     * <p>Dropping is the honest failure. The alternative is a queue that grows every pass, reading
     * screens that are hours stale while the live conversation goes uncaptured -- the transcript
     * would fall further behind with every pass and never recover.
     */
    private static final int MAX_QUEUED_READS = 2;

    /**
     * Photographs the way back through a channel, holding the device and nothing else.
     *
     * <p>Nothing here reads a word. The only judgement made is whether the feed is moving at all,
     * which is a comparison of two pictures and costs nothing -- and without it a screen that
     * refuses to scroll produces a hundred identical photographs.
     */
    private List<Path> photograph(String channel, PointData tab) {
        tapNear(tab);
        sleepTask(1000L);
        foldPinnedCard(channel);

        List<Path> shots = new ArrayList<>();
        // Stamped per pass, because the previous pass's photographs may still be being read and
        // clearing the directory out from under that reader would delete the screens mid-pass.
        Path dir = baseDir().resolve("pending")
                .resolve(channel + "-" + LocalDateTime.now().format(FILE_STAMP));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not make room for " + channel + " frames: "
                    + e.getMessage());
            return shots;
        }

        long deadline = System.currentTimeMillis() + CHANNEL_TIME_BUDGET_MS;
        BufferedImage previous = null;
        int stalled = 0;

        for (int i = 0; i < SAFETY_SCREEN_LIMIT && System.currentTimeMillis() < deadline; i++) {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null || !frame.isValid()) {
                logWarning("ChatCaptureRoutine | Could not capture a frame for " + channel
                        + "; stopping this channel.");
                break;
            }
            BufferedImage image =
                    dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
            try {
                Path out = dir.resolve(String.format("%04d.png", i));
                ImageIO.write(image, "png", out.toFile());
                shots.add(out);
            } catch (IOException e) {
                logWarning("ChatCaptureRoutine | Could not save a " + channel + " frame: "
                        + e.getMessage());
                break;
            }

            if (previous != null && !feedMoved(previous, image)) {
                stalled++;
                if (stalled >= STALLED_SCREENS_BEFORE_GIVING_UP) {
                    // Kept whatever the frame-cache setting says. A stall is the one failure this
                    // routine cannot diagnose from its own log: the message is always "something is
                    // sitting on top of the feed", and which something is exactly what is missing.
                    // Alliance stalled three times in a day here and every walk that hit it lost
                    // the rest of the conversation, with nothing left to look at afterwards.
                    keepForDiagnosis(channel, image);
                    logWarning("ChatCaptureRoutine | " + channel + ": the feed is not scrolling --"
                            + " something is sitting on top of it and swallowing the drag."
                            + " Stopping rather than photographing one screen repeatedly."
                            + " The frame it stopped on is in ocr-debug.");
                    break;
                }
                // Before believing it, try a different line down the screen. The drag runs down the
                // middle of the feed, and the middle is where the game puts stickers, images and
                // the cards it drops in -- anything that handles a gesture itself takes the drag
                // and the feed does not move. A drag that starts a couple of hundred pixels to the
                // side is over ordinary background instead, and the feed it is trying to move is
                // the same feed either way.
                swipeUpThroughHistory(stalled);
                previous = image;
                continue;
            } else {
                stalled = 0;
            }
            previous = image;
            swipeUpThroughHistory();
        }

        logInfo("ChatCaptureRoutine | " + channel + ": photographed " + shots.size()
                + " screen(s) in " + (CHANNEL_TIME_BUDGET_MS / 1000) + "s; reading them now.");
        return shots;
    }

    /**
     * Reads the saved screens, newest first, and stops as soon as it recognises history.
     *
     * <p>The device is free by the time this runs, so nothing here is racing anything. Frames it
     * never reaches are deleted along with the rest: they are older than the point the transcript
     * already covers, and keeping them would only mean reading them again next pass.
     */
    private int read(String channel, List<Path> shots) {
        dev.frostguard.vision.ocr.ChatTextReader engine = reader();
        boolean engineUp = engine != null && engine.isUp();
        logInfo("ChatCaptureRoutine | Reader: " + (engineUp ? engine.name() : "built-in"));

        // One object holds the walk, and the bench drives the same one. Two copies of this loop
        // is how a change measured at 90% clean scored 72% in production.
        ChatPass pass = new ChatPass(channel, body -> translator.toEnglish(body),
                CHAT_TEXT_SETTINGS, CHAT_CJK_SETTINGS, CHAT_CYRILLIC_SETTINGS, TEXT_COLUMN_RIGHT);
        pass.useKnownHistory(store::alreadyStored);

        // Personal is a stack of separate conversations rather than one feed. See the note on the
        // known-history stop below.
        boolean conversations = "personal".equals(channel);
        int screensRead = 0;
        boolean finished = false;
        try {
            for (Path shot : shots) {
                BufferedImage image;
                try {
                    image = ImageIO.read(shot.toFile());
                } catch (IOException e) {
                    logWarning("ChatCaptureRoutine | Could not reopen " + shot.getFileName()
                            + ": " + e.getMessage());
                    continue;
                }
                if (image == null) {
                    continue;
                }
                screensRead++;
                RawImageData frame = ChatFrames.toRaw(image);

                List<TextLine> lines;
                boolean fromService;
                try {
                    fromService = engineUp;
                    lines = fromService
                            ? engine.read(image, TEXT_COLUMN_LEFT, FEED_TOP, TEXT_COLUMN_RIGHT,
                                    FEED_BOTTOM, PADDLE_LANG, PADDLE_MIN_CONFIDENCE)
                            : java.util.List.of();
                    if (lines.isEmpty()) {
                        if (engineUp) {
                            logWarning("ChatCaptureRoutine | The OCR service returned nothing;"
                                    + " falling back to the built-in reader for the rest of this"
                                    + " pass.");
                            engineUp = false;
                        }
                        fromService = false;
                        lines = OcrEngine.recognizeLines(frame, FEED_TOP_LEFT, FEED_BOTTOM_RIGHT,
                                CHAT_TEXT_SETTINGS);
                    }
                } catch (OcrException e) {
                    logWarning("ChatCaptureRoutine | Could not read " + channel + " screen "
                            + screensRead + ": " + e.getMessage());
                    continue;
                }
                if (lines.isEmpty()) {
                    continue;
                }

                // Bound to this frame, because a row's box only means anything against the screen
                // it was measured on.
                pass.useRereader(fromService ? (l, t, r, b, language) ->
                        engine.read(image, l, t, r, b, language, PADDLE_MIN_CONFIDENCE) : null);
                ChatPass.Screen screen = pass.addScreen(frame, image, lines, fromService);
                cacheFrame(channel, screensRead, image);
                logInfo("ChatCaptureRoutine | " + channel + " screen " + screensRead + "/"
                        + shots.size() + ": " + screen.lines() + " line(s), " + screen.readable()
                        + " readable, " + screen.fresh() + " new.");
                // Stopping at known history is an optimisation for one long feed, where every
                // screen behind this one is older than the last. Personal is not one feed: these
                // frames are several separate conversations end to end, and the first of them is
                // usually quiet. Stopping there would mean the reader never reached the other
                // five, and the routine that just went to the trouble of opening them would have
                // photographed them for nothing. There are at most a couple of dozen frames, so
                // they are simply all read.
                if (!conversations && pass.reachedKnownHistory()) {
                    logInfo("ChatCaptureRoutine | " + channel + ": reached already-captured"
                            + " history after " + screensRead + " of " + shots.size()
                            + " screen(s).");
                    finished = true;
                    break;
                }
            }

            // Reading every screen photographed and still finding new messages means the walk did
            // not reach the end of what was said. It used to do that silently, which is why half a
            // day of passes lost messages without anybody noticing. Not a warning for Personal,
            // where reading every frame is the plan rather than a shortfall.
            if (!finished && !conversations) {
                logWarning("ChatCaptureRoutine | " + channel + ": read all " + shots.size()
                        + " photographed screen(s) and was still finding new messages. Anything"
                        + " older than that was not read this pass.");
            }
            return store.append(pass.messages());
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not write the transcript for " + channel
                    + ": " + e.getMessage());
            return 0;
        } finally {
            clear(shots.get(0).getParent());
        }
    }

    /** Drops the photographs once they have been read; they are worth nothing twice. */
    private void clear(Path dir) {
        try (var listing = Files.list(dir)) {
            for (Path p : listing.toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not clear " + dir + ": " + e.getMessage());
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not remove " + dir + ": " + e.getMessage());
        }
    }

    /** Three letters in a row is a word; anything less is the reader guessing at shapes. */
    private static final int LETTERS_THAT_MAKE_A_WORD = 3;

    /**
     * Whether the feed actually moved between two screens.
     *
     * <p>Compared on the message column alone. The rest of the screen carries animation -- falling
     * snow, a flickering brazier, a countdown -- that changes on every frame whether or not
     * anything scrolled, so a whole-screen comparison always says yes.
     */
    private static boolean feedMoved(BufferedImage before, BufferedImage after) {
        int changed = 0;
        int looked = 0;
        // Every fourth pixel in each direction. The question is whether the picture moved, which
        // does not need every pixel to answer, and this runs between two screenshots.
        for (int y = FEED_TOP; y < FEED_BOTTOM && y < after.getHeight(); y += 4) {
            for (int x = TEXT_COLUMN_LEFT; x < TEXT_COLUMN_RIGHT && x < after.getWidth(); x += 4) {
                looked++;
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                if (Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) > CHANNEL_NOISE
                        || Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) > CHANNEL_NOISE
                        || Math.abs((a & 0xFF) - (b & 0xFF)) > CHANNEL_NOISE) {
                    changed++;
                }
            }
        }
        return looked > 0 && changed / (double) looked >= MOVED_SHARE;
    }

    /** Below this a difference is compression noise, not a different picture. */
    private static final int CHANNEL_NOISE = 12;

    /**
     * How much of the message column has to differ before the feed counts as having moved.
     *
     * <p>A real scroll changes about a third of it. A stalled one changes almost nothing -- only a
     * ticking countdown inside a pinned card -- so this sits far below the first and well above the
     * second.
     */
    private static final double MOVED_SHARE = 0.05;

    /**
     * How many refusals before the feed is called stuck.
     *
     * <p>Three now rather than two, because a refusal is no longer the end of it -- each one moves
     * the drag to a different line down the screen, so this is the number of lines tried and not
     * the number of screens wasted. Two would give up having tried the middle and one side.
     */
    private static final int STALLED_SCREENS_BEFORE_GIVING_UP = 3;

    /**
     * Folds the alliance poll away, if one is pinned above the feed.
     *
     * <p>The poll is not merely in the way. It is pinned across the top of the feed, and the drag
     * that scrolls chat starts inside it -- so with a poll up, the drag does nothing at all. The
     * walk then photographs the same screen for its whole run, finds nothing new after the first,
     * and reports that it reached already-captured history. It looks exactly like a quiet channel,
     * and polls run for hours.
     *
     * <p>Folding it is a tap on the poll's own collapse control, which leaves the card as a badge
     * in the corner. It does not cast a vote: measured across the tap, the card's participant count
     * kept its "Have Not Participated" label and only moved as other players voted.
     *
     * <p>Checked by whether the feed actually changed. A tap that hit nothing leaves the screen
     * alone, and there is nothing to be done about that here -- but the scroll check below will
     * catch what it costs.
     */
    private void foldPinnedCard(String channel) {
        RawImageData before = emuManager.captureScreen(EMULATOR_NUMBER);
        if (before == null || !before.isValid() || !hasPinnedCard(before)) {
            return;
        }
        tapNear(POLL_COLLAPSE);
        sleepTask(1200L);
        logInfo("ChatCaptureRoutine | " + channel + ": folded the pinned poll away so the feed"
                + " can scroll.");
    }

    /** Whether the band above the feed is carrying the poll rather than messages. */
    private boolean hasPinnedCard(RawImageData frame) {
        try {
            List<TextLine> rows = OcrEngine.recognizeLines(frame,
                    new PointData(CARD_LEFT, CARD_TOP), new PointData(CARD_RIGHT, CARD_BOTTOM),
                    CHAT_TEXT_SETTINGS);
            for (TextLine row : rows) {
                if (POLL_CARD.matcher(row.text()).find()) {
                    return true;
                }
            }
        } catch (OcrException e) {
            // Not being able to tell means leaving it alone, which is what happened before this
            // existed. A tap made on a guess would be a tap on somebody's vote.
            logWarning("ChatCaptureRoutine | Could not check for a pinned poll: " + e.getMessage());
        }
        return false;
    }

    /** The poll's own collapse control, at the bottom right of the card. */
    private static final PointData POLL_COLLAPSE = new PointData(660, 422);

    /** The band the poll occupies when it is pinned. */
    private static final int CARD_LEFT = 20;
    private static final int CARD_TOP = 255;
    private static final int CARD_RIGHT = 710;
    private static final int CARD_BOTTOM = 470;

    /**
     * The poll's wording, which is what identifies it.
     *
     * <p>Its own labels rather than its colour or position: the card is drawn where a message could
     * be, and a player quoting the poll would match a looser test.
     */
    private static final java.util.regex.Pattern POLL_CARD = java.util.regex.Pattern.compile(
            "(?i)initiator|participants|vote in|single selection|have not particip");

    /**
     * Files a screen that read cleanly, when the profile asked for a cache.
     *
     * <p>Separate from {@link #keepUnstoredFrame}, which is evidence of a failure and is always
     * written. This is the opposite case -- the frame was read, so it is not needed -- kept only
     * because reading it again later is otherwise impossible.
     */
    private void cacheFrame(String channel, int scrollIndex, BufferedImage image) {
        if (frameCache == null || !frameCache.isOn()) {
            return;
        }
        try {
            frameCache.keep(channel, scrollIndex, image);
        } catch (ChatFrameCache.UncheckedFrameCacheFailure e) {
            // A cache that cannot write is a cache that is not there; the rows are already stored.
            logWarning("ChatCaptureRoutine | Could not cache the frame: " + e.getMessage());
        }
    }

    /** Keeps the last few hundred megabytes of read screens, when the profile asked for it. */
    private ChatFrameCache frameCache;

    /**
     * Keeps a frame whose rows could not be stored.
     *
     * <p>Successful frames are deleted as soon as their rows are safe. A failure is the one case
     * where the image is still the only evidence, and the standing rule is to dump the frame rather
     * than guess at what went wrong.
     */
    private void keepUnstoredFrame(String channel, int scrollIndex, BufferedImage image) {
        try {
            Path dir = baseDir().resolve("failed");
            Files.createDirectories(dir);
            Path out = dir.resolve(channel + "-" + LocalDateTime.now().format(FILE_STAMP)
                    + "-" + scrollIndex + ".png");
            ImageIO.write(image, "png", out.toFile());
            logWarning("ChatCaptureRoutine | Kept the unstored frame at " + out);
        } catch (IOException e) {
            logWarning("ChatCaptureRoutine | Could not keep the failed frame: " + e.getMessage());
        }
    }

    /**
     * Returns the game to a screen the next task will recognise.
     *
     * <p>Verified rather than assumed: a single blind tap on the close control is exactly the kind
     * of "it probably worked" step that strands the game somewhere unexpected. The chat panel can
     * also be more than one layer deep, so this taps and checks until a home anchor is visible.
     */
    private void closeChat() {
        for (int attempt = 1; attempt <= CHAT_CLOSE_ATTEMPTS; attempt++) {
            if (isOnAHomeScreen()) {
                return;
            }
            tapNear(CHAT_CLOSE);
            sleepTask(700L);
        }

        if (isOnAHomeScreen()) {
            return;
        }
        // Say so plainly rather than leaving the next task to discover it. pressBack carries the
        // quit-dialog guard, so it is the safer last resort than tapping more coordinates.
        logWarning("ChatCaptureRoutine | Chat did not close after " + CHAT_CLOSE_ATTEMPTS
                + " attempts; falling back to Back so the next task does not inherit this screen.");
        pressBack();
        sleepTask(700L);
        if (!isOnAHomeScreen()) {
            logWarning("ChatCaptureRoutine | Still not on a recognised screen after closing chat.");
        }
    }

    /** Either home screen counts: the next task navigates from whichever one it needs. */
    private boolean isOnAHomeScreen() {
        return templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                        SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.QUICK_SEARCH).isFound();
    }

    /**
     * How far one scroll step travels, as a fraction of the drag the feed height allows.
     *
     * <p>A full-height drag moves the feed by very nearly one screen, which sounds efficient and is
     * actually the bug: measured across consecutive captures, neighbouring frames shared only one
     * or two lines out of ten to fifteen. That is no margin at all. It holds while every message is
     * a line or two, and the moment somebody posts a wall of text the drag steps straight over it
     * and the message is gone with nothing to show it ever existed.
     *
     * <p>A little over half a screen buys back the overlap. The cost is duplicates, which cost
     * nothing: de-duplication keys on the message body, so a line seen twice is stored once.
     */
    private static final double SCROLL_STEP_FRACTION = 0.53;

    /**
     * How long the scroll drag takes.
     *
     * <p>This is the difference between a drag and a flick. A swipe sent with no duration is a
     * bare {@code input swipe}, which Android reads as a flick and answers with momentum
     * scrolling -- the feed keeps travelling after the finger lifts, by an amount that depends on
     * how quickly the emulator serviced the gesture rather than on anything asked for. That is
     * what an occasional step "going extra" looks like from the outside: most scrolls land where
     * they should and every so often one sails past a message, and no reduction in distance fixes
     * it because the distance was never the problem.
     *
     * <p>The same fault was found on the world map, where a pan meant to move one screen ended up
     * in unrelated rival cities, which is why the duration-aware overload exists at all. Chat had
     * simply never used it.
     */
    private static final int SCROLL_DRAG_MS = 700;

    private void swipeUpThroughHistory() {
        swipeUpThroughHistory(0);
    }

    /**
     * Drags the feed back through its history.
     *
     * <p>A downward drag reveals content above the current view, i.e. older messages -- the
     * opposite of how a page-down gesture reads.
     *
     * @param attempt 0 for the ordinary drag down the middle, higher to move the line sideways
     *                after the feed refused to move
     */
    private void swipeUpThroughHistory(int attempt) {
        int from = FEED_TOP + 120;
        int travel = (int) Math.round(((FEED_BOTTOM - 120) - from) * SCROLL_STEP_FRACTION);
        int x = FEED_X + RETRY_OFFSETS[Math.min(attempt, RETRY_OFFSETS.length - 1)];
        swipe(new PointData(x, from), new PointData(x, from + travel), SCROLL_DRAG_MS);
        sleepTask(700L);
    }

    /**
     * Where to put the drag on each try: the middle, then left of it, then right.
     *
     * <p>Both alternatives stay inside the text column, so they are over the feed and not over the
     * avatars on one edge or the translate controls on the other.
     */
    private static final int[] RETRY_OFFSETS = {0, -190, 190};

    /** Saves the frame a stall happened on, so the next one can be diagnosed rather than guessed. */
    private void keepForDiagnosis(String channel, BufferedImage frame) {
        try {
            Path dir = Paths.get(System.getProperty("user.dir"), "ocr-debug");
            Files.createDirectories(dir);
            Path out = dir.resolve("chat-stall-" + channel + "-"
                    + java.time.LocalDateTime.now().toString().replace(':', '-') + ".png");
            ImageIO.write(frame, "png", out.toFile());
        } catch (IOException | RuntimeException e) {
            logWarning("ChatCaptureRoutine | Could not save the stalled frame: " + e.getMessage());
        }
    }

    /**
     * Where a reader's transcript is kept.
     *
     * <p>One directory per reader. Screens are photographed once and could be read by either, so
     * the only thing that distinguishes their output is which reader produced it -- and a shared
     * file would lose exactly that.
     */
    static Path transcriptDir(String reader) {
        Path base = Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
        return "JAVA".equals(reader) ? base.resolveSibling("chat-java") : base;
    }

    private Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
    }

    /**
     * Drops what the pass never managed to attribute to anybody.
     *
     * <p>Scroll steps overlap by design, so a real message is seen on two or three screens and the
     * holding map prefers whichever copy carried a sender line. A message that reaches the end of a
     * pass still unattributed is therefore one that was never seen with a name on it at all, and
     * measured over a fixed twenty-screen set every one of those was either a game announcement --
     * an event card, a pinned notice -- or a strip of a message whose readable half was somewhere
     * else. None of them were anybody's words. Storing them puts unattributable text in a
     * transcript whose whole purpose is who said what.
     */
    private static java.util.List<ChatMessage> withAnAuthor(java.util.List<ChatMessage> messages) {
        java.util.List<ChatMessage> out = new java.util.ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (!m.author().isBlank()) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Puts back the "@" the reader lost, now that the whole pass has been walked.
     *
     * <p>Deliberately last. The names it matches against come from the sender lines of this same
     * pass, so it needs every screen read before it knows who is in the alliance -- a mention of
     * somebody whose own messages are further back in the history is only repairable once that far
     * back has been reached.
     */
    private static java.util.List<ChatMessage> repairMentions(java.util.List<ChatMessage> messages,
                                                              java.util.Set<String> roster) {
        for (ChatMessage m : messages) {
            if (!m.author().isBlank()) {
                roster.add(m.author());
            }
            roster.addAll(m.mentions());
        }
        java.util.List<ChatMessage> out = new java.util.ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            String repaired = ChatLineCleaner.repairLeadingMention(m.body(), roster);
            if (repaired.equals(m.body())) {
                out.add(m);
                continue;
            }
            // The English was made from the body as it read before the repair, so it carries the
            // same wreckage: "@Maki felicidades!" was stored correctly while its translation still
            // said "yy Maki congratulations!". Repaired the same way rather than translated again,
            // because the mangled "@" survives translation unchanged and a second lookup would buy
            // nothing but a request.
            String english = m.translated() == null || m.translated().isBlank()
                    ? m.translated()
                    : ChatLineCleaner.repairLeadingMention(m.translated(), roster);
            out.add(m.withBody(repaired).withTranslated(english));
        }
        return out;
    }

    /**
     * Files one message against what the pass has already read, treating a re-read as the same
     * message rather than a new one.
     *
     * <p>The exact key is tried first because it is a map lookup; the scan behind it only runs when
     * that misses, and only that message's own re-reads can match it. Where two copies disagree the
     * fuller one wins -- a message clipped by the bottom of the screen is still a correct reading of
     * its first half, so length is the honest tie-break -- except that an attributed copy always
     * beats an unattributed one, because a message read at the top of a screen has its sender line
     * above the frame edge and comes back with no author at all.
     */
    static void keep(java.util.LinkedHashMap<String, ChatMessage> collected, ChatMessage m) {
        String key = ChatLineCleaner.mergeKey(m.body());
        String match = collected.containsKey(key) ? key : null;
        if (match == null) {
            for (String seen : collected.keySet()) {
                if (ChatLineCleaner.sameMessage(seen, key)) {
                    match = seen;
                    break;
                }
            }
        }
        if (match == null) {
            collected.put(key, m);
            return;
        }
        ChatMessage held = collected.get(match);
        ChatMessage winner = better(m, held) ? m : held;
        ChatMessage other = winner == m ? held : m;
        // A message read on two screens is not read the same way twice, and the copy with the
        // fuller body is not always the copy that kept its mention: the long alliance call was
        // clipped on the screen where "@All" was legible and complete on the screen where it was
        // not, so whichever copy won, something was lost. The body comes from the fuller reading
        // and the mention from whichever reading kept one.
        if (!winner.body().startsWith("@") && other.body().startsWith("@")) {
            int end = other.body().indexOf(' ');
            String mention = end < 0 ? other.body() : other.body().substring(0, end);
            winner = winner.withBody(mention + " " + winner.body());
        }
        if (winner != held) {
            collected.remove(match);
            collected.put(key, winner);
        } else if (winner != collected.get(match)) {
            collected.put(match, winner);
        }
    }

    static boolean better(ChatMessage candidate, ChatMessage held) {
        if (held.author().isEmpty() != candidate.author().isEmpty()) {
            return held.author().isEmpty();
        }
        return candidate.body().length() > held.body().length();
    }

}
