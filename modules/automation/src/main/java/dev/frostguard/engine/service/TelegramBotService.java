package dev.frostguard.engine.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.schedule.TaskQueue;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * Telegram Bot integration service.
 *
 * Polls the Telegram Bot API using long-polling and reacts to commands from an
 * authorised chat ID. Runs entirely on a background daemon virtual thread —
 * no inbound port / firewall change required.
 *
 * Supported commands (case-insensitive):
 * /start or /startbot → launch the bot automation
 * /stop or /stopbot → stop the bot automation
 * /status → reply with current running state
 */
public class TelegramBotService implements BotStateListener {

    static final String CURRENT_LOG_PATH = "log/frostguard.log";

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotService.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    // ── singleton ────────────────────────────────────────────────────────────
    private static TelegramBotService instance;

    public static synchronized TelegramBotService getInstance() {
        if (instance == null) {
            instance = new TelegramBotService();
        }
        return instance;
    }

    // ── state ─────────────────────────────────────────────────────────────────
    private volatile boolean botCurrentlyRunning = false;

    private String token;
    private long   allowedChatId;
    private WatcherCommandServer commandServer;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tracks the Telegram message_id of the last /queue page sent per profile-ID,
     * so button actions can edit that message in-place instead of sending a new
     * one.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastQueueMsgIds = new java.util.concurrent.ConcurrentHashMap<>();

    private TelegramBotService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ScheduleService.obtain().addEngineObserver(this);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the local command server that receives forwarded Telegram commands
     * from the TelegramWatcher process.
     *
     * @param token         Telegram Bot API token (used to send replies)
     * @param allowedChatId The only Telegram chat-ID that is allowed to issue commands
     */
    public synchronized void start(String token, long allowedChatId) {
        if (commandServer != null) {
            logger.info("TelegramBotService already running – restarting with new credentials");
            stop();
        }
        if (token == null || token.isBlank()) {
            logger.warn("TelegramBotService: token is blank, not starting");
            return;
        }
        this.token         = token.trim();
        this.allowedChatId = allowedChatId;

        int port = readLocalPort();
        commandServer = new WatcherCommandServer(port, this);
        try {
            commandServer.start();
        } catch (IOException e) {
            logger.error("Failed to start WatcherCommandServer on port {}: {}", port, e.getMessage());
            commandServer = null;
            return;
        }

        // Register /commands in Telegram's command menu
        Thread.ofVirtual().start(this::registerBotCommands);

        logger.info("TelegramBotService started (chat-ID: {}, local-port: {})", allowedChatId, port);
    }

    /** Stop the command server gracefully. */
    public synchronized void stop() {
        if (commandServer != null) {
            commandServer.stop();
            commandServer = null;
        }
        logger.info("TelegramBotService stopped");
    }

    public boolean isRunning() {
        return commandServer != null && commandServer.isRunning();
    }

    /** Read localPort from the shared watcher properties file (default 8765). */
    private static int readLocalPort() {
        try {
            Path cfg = Paths.get(System.getProperty("user.home"), ".frostguard", "telegram-watcher.properties");
            if (Files.exists(cfg)) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(cfg.toFile())) { props.load(fis); }
                return Integer.parseInt(props.getProperty("localPort", "8765").trim());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(TelegramBotService.class)
                    .warn("Could not read localPort from config, defaulting to 8765: {}", e.getMessage());
        }
        return 8765;
    }

    // ── public command dispatch (called by WatcherCommandServer) ─────────────

    /**
     * Dispatch a text command forwarded from TelegramWatcher.
     * The {@code text} parameter is already lower-cased and trimmed.
     */
    public void handleTextCommand(long chatId, String text) {
        if (text.startsWith("/start") || text.startsWith("/startbot") || text.contains("start bot")) {
            if (botCurrentlyRunning) {
                sendMessage(chatId, "⚙️ Bot is already running.");
            } else {
                Thread.ofVirtual().start(() -> {
                    ScheduleService.obtain().launchEngine();
                    sendMessage(chatId, "▶️ Bot started.");
                });
            }
        } else if (text.startsWith("/stop") || text.startsWith("/stopbot") || text.contains("stop bot")) {
            if (!botCurrentlyRunning) {
                sendMessage(chatId, "⏹️ Bot is not currently running.");
            } else {
                Thread.ofVirtual().start(() -> {
                    // Changed by pernerch | Date: 2026-07-04 | Why: use Telegram-specific stop policy instead of generic stop path.
                    ScheduleService.obtain().haltEngineFromTelegram();
                    sendMessage(chatId, "⏹️ Bot stopped.");
                });
            }
        } else if (text.startsWith("/status") || text.contains("status")) {
            sendMessage(chatId, botCurrentlyRunning ? "✅ Bot is *running*." : "🔴 Bot is *stopped*.");
        } else if (text.startsWith("/screenshot") || text.contains("screenshot")) {
            sendMessage(chatId, "📸 Capturing screenshot...");
            Thread.ofVirtual().start(() -> sendScreenshot(chatId));
        } else if (text.startsWith("/queue") || text.contains("queue")) {
            Thread.ofVirtual().start(() -> handleTaskQueue(chatId));
        } else if (text.startsWith("/stats") || text.contains("stats")) {
            Thread.ofVirtual().start(() -> handleStatsCommand(chatId));
        } else if (text.startsWith("/logs") || text.contains("logs")) {
            Thread.ofVirtual().start(() -> handleLogsCommand(chatId));
        } else if (text.startsWith("/profiles") || text.contains("profiles")) {
            Thread.ofVirtual().start(() -> handleProfilesCommand(chatId, -1L));
        } else if (text.startsWith("/reboot") || text.contains("reboot")) {
            Thread.ofVirtual().start(() -> handleRebootCommand(chatId));
        } else if (text.startsWith("/help") || text.contains("help")) {
            sendMessage(chatId, buildHelpMessage());
        } else {
            logger.debug("TelegramBotService: unrecognised command '{}' from chat {}", text, chatId);
        }
    }

    /**
     * Dispatch an inline-keyboard callback forwarded from TelegramWatcher.
     * Runs on a virtual thread so the watcher HTTP response is returned immediately.
     */
    public void handleCallbackQuery(String callbackId, long chatId, long messageId, String data) {
        Thread.ofVirtual().start(() -> handleCallback(callbackId, chatId, messageId, data));
    }

    // ── (processUpdate removed — commands arrive via WatcherCommandServer) ────

    // ── Register commands in Telegram's /commands menu ────────────────────────

    private void registerBotCommands() {
        try {
            ArrayNode cmds = objectMapper.createArrayNode();
            cmds.add(objectMapper.createObjectNode().put("command", "launch").put("description",
                    "Start the bot app (with UI)"));
            cmds.add(objectMapper.createObjectNode().put("command", "launch_headless").put("description",
                    "Start the bot app without UI"));
            cmds.add(objectMapper.createObjectNode().put("command", "kill").put("description",
                    "Force-close the bot app"));
            cmds.add(objectMapper.createObjectNode().put("command", "wstatus").put("description",
                    "Check if the bot app is running"));
            cmds.add(objectMapper.createObjectNode().put("command", "startbot").put("description",
                    "Start the automation routines"));
            cmds.add(objectMapper.createObjectNode().put("command", "stopbot").put("description",
                    "Stop the automation routines"));
            cmds.add(objectMapper.createObjectNode().put("command", "status").put("description",
                    "Show automation status"));
            cmds.add(objectMapper.createObjectNode().put("command", "screenshot").put("description",
                    "Capture & send emulator screen"));
            cmds.add(objectMapper.createObjectNode().put("command", "queue").put("description",
                    "Task queue with schedule/remove/run"));
            cmds.add(objectMapper.createObjectNode().put("command", "stats").put("description",
                    "Bot activity statistics per profile"));
            cmds.add(objectMapper.createObjectNode().put("command", "logs").put("description",
                    "Download log files"));
            cmds.add(objectMapper.createObjectNode().put("command", "profiles").put("description",
                    "View & toggle profiles on/off"));
            cmds.add(objectMapper.createObjectNode().put("command", "reboot").put("description",
                    "Restart bot + emulators"));
            cmds.add(objectMapper.createObjectNode().put("command", "help").put("description", "Show help message"));

            ObjectNode body = objectMapper.createObjectNode();
            body.set("commands", cmds);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/setMyCommands"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Telegram bot commands registered successfully");
            } else {
                logger.warn("setMyCommands failed: HTTP {} - {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            logger.error("registerBotCommands failed: {}", e.getMessage());
        }
    }

    // ── Help message ──────────────────────────────────────────────────────────

    private static String buildHelpMessage() {
        return "╔══════════════════════════╗\n"
                + "║   🤖  Frostguard  •  Help   ║\n"
                + "╚══════════════════════════╝\n"
                + "\n"
                + "🚀 *Process Control* _(watcher – always on)_\n"
                + "`/launch`           — Start the bot app (with UI)\n"
                + "`/launch_headless`  — Start the bot app without UI\n"
                + "`/kill`             — Force-close the bot app\n"
                + "`/wstatus`          — Check if the bot app is running\n"
                + "\n"
                + "⚙️ *Automation Control* _(requires app running)_\n"
                + "`/startbot`    — Begin the automation routines\n"
                + "`/stopbot`     — Pause the automation routines\n"
                + "`/status`      — Show whether automation is running\n"
                + "`/reboot`      — Restart bot + emulators\n"
                + "\n"
                + "📊 *Monitoring & Info*\n"
                + "`/screenshot`  — Capture & send emulator screen\n"
                + "`/queue`       — Task queue (schedule / remove / run)\n"
                + "`/stats`       — Bot activity statistics per profile\n"
                + "`/logs`        — Download the Frostguard log\n"
                + "`/profiles`    — View & toggle profiles on/off\n"
                + "\n"
                + "❓ *Help*\n"
                + "`/help`        — Show this message\n"
                + "`/whelp`       — Show help (works even if app is closed)\n"
                + "\n"
                + "💡 _Tip: use the keyboard buttons below for quick access._\n"
                + "_If the app is closed, use /launch first, then /startbot._";
    }

    // ── Screenshot ────────────────────────────────────────────────────────────

    private void sendScreenshot(long chatId) {
        try {
            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            if (profiles == null || profiles.isEmpty()) {
                sendMessage(chatId, "❌ No profiles configured.");
                return;
            }
            Optional<AccountDescriptor> enabledProfile = profiles.stream()
                    .filter(AccountDescriptor::getEnabled)
                    .findFirst();
            if (enabledProfile.isEmpty()) {
                sendMessage(chatId, "❌ No enabled profiles found.");
                return;
            }
            String emulatorNumber = enabledProfile.get().getEmulatorNumber();
            EmulatorController emuManager = EmulatorController.getInstance();

            RawImageData raw = null;
            try {
                raw = emuManager.captureScreen(emulatorNumber);
            } catch (Exception firstAttempt) {
                // ADB device cache may be stale — restart ADB server and retry once
                logger.warn("Screenshot first attempt failed ({}), restarting ADB and retrying…",
                        firstAttempt.getMessage());
                sendMessage(chatId, "⏳ Device not ready — reconnecting ADB…");
                try {
                    emuManager.restartAdbServer();
                    Thread.sleep(2000); // give ADB time to re-discover devices
                    raw = emuManager.captureScreen(emulatorNumber);
                } catch (Exception retryFailed) {
                    logger.error("Screenshot retry also failed: {}", retryFailed.getMessage());
                    sendMessage(chatId, "❌ Screenshot failed after ADB restart. "
                            + "Is the emulator running?\n\nError: " + retryFailed.getMessage());
                    return;
                }
            }

            if (raw == null || raw.getData() == null || raw.getData().length == 0) {
                sendMessage(chatId, "❌ Failed to capture screenshot. Is the emulator running?");
                return;
            }
            byte[] pngBytes = rawImageToPng(raw);
            if (pngBytes == null) {
                sendMessage(chatId, "❌ Failed to encode screenshot.");
                return;
            }
            sendPhoto(chatId, pngBytes);
        } catch (Exception e) {
            logger.error("TelegramBotService: screenshot failed: {}", e.getMessage());
            sendMessage(chatId, "❌ Screenshot error: " + e.getMessage());
        }
    }

    private byte[] rawImageToPng(RawImageData raw) {
        try {
            int width = raw.getWidth();
            int height = raw.getHeight();
            int bpp = raw.getBpp(); // bits per pixel: 32 (RGBA) or 16 (RGB565)
            byte[] data = raw.getData();

            logger.debug("rawImageToPng: {}x{}, bpp={}, dataLen={}", width, height, bpp, data.length);

            BufferedImage img;
            if (bpp == 32 || bpp == 4) {
                // RGBA_8888 — 4 bytes per pixel
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int idx = i * 4;
                    if (idx + 3 >= data.length)
                        break;
                    int r = data[idx] & 0xFF;
                    int g = data[idx + 1] & 0xFF;
                    int b = data[idx + 2] & 0xFF;
                    int a = data[idx + 3] & 0xFF;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                img.setRGB(0, 0, width, height, pixels, 0, width);
            } else if (bpp == 16) {
                // RGB_565 — 2 bytes per pixel
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int idx = i * 2;
                    if (idx + 1 >= data.length)
                        break;
                    int lo = data[idx] & 0xFF;
                    int hi = data[idx + 1] & 0xFF;
                    int rgb565 = (hi << 8) | lo;
                    int r = ((rgb565 >> 11) & 0x1F) * 255 / 31;
                    int g = ((rgb565 >> 5) & 0x3F) * 255 / 63;
                    int b = (rgb565 & 0x1F) * 255 / 31;
                    pixels[i] = (r << 16) | (g << 8) | b;
                }
                img.setRGB(0, 0, width, height, pixels, 0, width);
            } else {
                // Fallback: assume 3 bytes per pixel (RGB)
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int idx = i * 3;
                    if (idx + 2 >= data.length)
                        break;
                    int r = data[idx] & 0xFF;
                    int g = data[idx + 1] & 0xFF;
                    int b = data[idx + 2] & 0xFF;
                    pixels[i] = (r << 16) | (g << 8) | b;
                }
                img.setRGB(0, 0, width, height, pixels, 0, width);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            logger.error("TelegramBotService: PNG encoding failed: {}", e.getMessage());
            return null;
        }
    }

    private void sendPhoto(long chatId, byte[] pngBytes) {
        try {
            String boundary = "TelegramBotBoundary" + System.currentTimeMillis();
            byte[] header = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                    + chatId + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"photo\"; filename=\"screenshot.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[header.length + pngBytes.length + footer.length];
            System.arraycopy(header, 0, body, 0, header.length);
            System.arraycopy(pngBytes, 0, body, header.length, pngBytes.length);
            System.arraycopy(footer, 0, body, header.length + pngBytes.length, footer.length);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/sendPhoto"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("TelegramBotService: sendPhoto HTTP {}: {}", response.statusCode(), response.body());
                sendMessage(chatId, "❌ Telegram rejected the photo (HTTP " + response.statusCode() + ")");
            }
        } catch (Exception e) {
            logger.error("TelegramBotService: failed to send photo: {}", e.getMessage());
        }
    }

    // ── Telegram send helper ──────────────────────────────────────────────────

    private void sendMessage(long chatId, String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");

            ArrayNode keyboard = objectMapper.createArrayNode();

            ArrayNode row1 = objectMapper.createArrayNode();
            row1.add(objectMapper.createObjectNode().put("text", "📸 SCREENSHOT"));
            row1.add(objectMapper.createObjectNode().put("text", "📋 QUEUE"));
            row1.add(objectMapper.createObjectNode().put("text", "📈 STATS"));
            keyboard.add(row1);

            ArrayNode row2 = objectMapper.createArrayNode();
            row2.add(objectMapper.createObjectNode().put("text", "▶️ START BOT"));
            row2.add(objectMapper.createObjectNode().put("text", "⏹️ STOP BOT"));
            keyboard.add(row2);

            ArrayNode row3 = objectMapper.createArrayNode();
            row3.add(objectMapper.createObjectNode().put("text", "👥 PROFILES"));
            row3.add(objectMapper.createObjectNode().put("text", "📄 LOGS"));
            row3.add(objectMapper.createObjectNode().put("text", "🔄 REBOOT"));
            keyboard.add(row3);

            ArrayNode row4 = objectMapper.createArrayNode();
            row4.add(objectMapper.createObjectNode().put("text", "ℹ️ STATUS"));
            row4.add(objectMapper.createObjectNode().put("text", "❓ HELP"));
            keyboard.add(row4);

            ObjectNode replyMarkup = objectMapper.createObjectNode();
            replyMarkup.set("keyboard", keyboard);
            replyMarkup.put("resize_keyboard", true);
            replyMarkup.put("is_persistent", true);

            body.set("reply_markup", replyMarkup);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("TelegramBotService: failed to send message: {}", e.getMessage());
        }
    }

    /**
     * Validate that a token is well-formed by calling getMe.
     *
     * @param token the token to test
     * @return the bot username if the token is valid, or an error string starting
     *         with "ERROR:"
     */
    public String testToken(String token) {
        if (token == null || token.isBlank()) {
            return "ERROR: token is empty";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token.trim() + "/getMe"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("ok").asBoolean()) {
                return "@" + root.path("result").path("username").asText("?");
            } else {
                return "ERROR: " + root.path("description").asText("invalid token");
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ── BotStateListener ──────────────────────────────────────────────────────

    @Override
    public void onEngineStateTransition(BotStateData botState) {
        if (botState != null) {
            botCurrentlyRunning = Boolean.TRUE.equals(botState.getRunning());
        }
    }

    // =========================================================================
    // ── /queue : Task Queue status with inline keyboard controls ─────────────
    // =========================================================================

    /** Number of tasks displayed per page in the queue view. */
    private static final int QUEUE_PAGE_SIZE = 5;

    private void handleTaskQueue(long chatId) {
        try {
            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            if (profiles == null || profiles.isEmpty()) {
                sendMessage(chatId, "❌ No profiles configured.");
                return;
            }
            for (AccountDescriptor profile : profiles) {
                TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
                sendQueuePage(chatId, -1L, profile, queue, 0);
            }
        } catch (Exception e) {
            logger.error("handleTaskQueue failed: {}", e.getMessage(), e);
            sendMessage(chatId, "❌ Error fetching queue: " + e.getMessage());
        }
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Sends (messageId == \-1) or edits (messageId >= 0) a beautifully formatted
     * paginated task\-queue message for one profile, using MarkdownV2.
     *
     * Layout
     * ─ Polished text body: header, status badges, task rows with inline timers
     * ─ Keyboard: 1 row per task → wide [📅 Name · Timer] + narrow [🗑] [▶️]
     * ─ Bottom row: navigation + 🔄 Refresh
     */
    private void sendQueuePage(long chatId, long messageId,
            AccountDescriptor profile, TaskQueue queue, int page) {
        List<TaskEntry> all = buildTaskEntries(profile.getId(), queue);
        int total = all.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / QUEUE_PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * QUEUE_PAGE_SIZE;
        int to = Math.min(from + QUEUE_PAGE_SIZE, total);
        List<TaskEntry> slice = all.subList(from, to);

        boolean queueActive = (queue != null);
        boolean running = queueActive && queue.isActive();
        long executing = all.stream().filter(t -> t.isExecuting).count();
        long scheduled = all.stream().filter(t -> t.isScheduled && !t.isExecuting).count();

        // ── Message text (MarkdownV2) ─────────────────────────────────────────
        StringBuilder sb = new StringBuilder();

        // ── Header ──
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📋  *").append(escV2(profile.getName())).append("*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // ── Status badges ──
        sb.append(queueActive ? "🟢 Active" : "🔴 Inactive");
        sb.append("    ").append(running ? "▶️ Running" : "⏸ Paused").append("\n");
        if (executing > 0 || scheduled > 0) {
            if (executing > 0)
                sb.append("🔄 ").append(executing).append(" running");
            if (executing > 0 && scheduled > 0)
                sb.append("    ");
            if (scheduled > 0)
                sb.append("🟡 ").append(scheduled).append(" queued");
            sb.append("\n");
        }
        sb.append("\n");

        // ── Task rows ──
        for (int i = 0; i < slice.size(); i++) {
            TaskEntry t = slice.get(i);
            String name = t.taskEnum.getName();
            String displayName = name.length() > 26 ? name.substring(0, 25) + "…" : name;

            // Line 1: status icon + bold task name
            sb.append(t.statusIcon).append("  *").append(escV2(displayName)).append("*\n");
            // Line 2: timer + last run (clean inline layout)
            sb.append("      ⏱  `").append(t.nextStr).append("`")
                    .append("   ·   last: _").append(escV2(t.lastStr)).append("_\n");

            if (i < slice.size() - 1)
                sb.append("\n");
        }

        // ── Footer ──
        String ts = LocalDateTime.now().format(TIME_FMT);
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📄 _").append(escV2("Page " + (page + 1) + " / " + totalPages
                + "  ·  " + (from + 1) + "–" + to + " of " + total))
                .append("_   ⏱ _").append(escV2(ts)).append("_");

        // ── Inline keyboard ───────────────────────────────────────────────────
        // Telegram gives EQUAL width to all buttons in one row (API constraint).
        // Workaround: 2 rows per task.
        // Row 1: full-width task label [icon TaskName · Timer 📅]
        // Row 2: two action buttons [🗑 Remove] [▶️ Run]
        ArrayNode kb = objectMapper.createArrayNode();
        long pid = profile.getId();

        for (TaskEntry t : slice) {
            String name = t.taskEnum.getName();
            String shortName = name.length() > 20 ? name.substring(0, 19) + "…" : name;
            int tid = t.taskEnum.getId();

            // Row 1 – full-width label button (tapping opens schedule picker)
            String label = t.statusIcon + "  " + shortName + "  ·  " + t.nextStr;
            ArrayNode labelRow = objectMapper.createArrayNode();
            labelRow.add(objectMapper.createObjectNode()
                    .put("text", label)
                    .put("callback_data", "sch:" + pid + ":" + tid + ":" + page));
            kb.add(labelRow);

            // Row 2 – two equal action buttons (50 / 50)
            ArrayNode actRow = objectMapper.createArrayNode();
            actRow.add(objectMapper.createObjectNode()
                    .put("text", "🗑  Remove")
                    .put("callback_data", "rem:" + pid + ":" + tid + ":" + page));
            actRow.add(objectMapper.createObjectNode()
                    .put("text", "▶️  Run")
                    .put("callback_data", "exe:" + pid + ":" + tid + ":" + page));
            kb.add(actRow);
        }

        // ── Navigation + Refresh row ──────────────────────────────────────────
        ArrayNode nav = objectMapper.createArrayNode();
        if (totalPages > 1 && page > 0) {
            nav.add(objectMapper.createObjectNode()
                    .put("text", "◄ Prev")
                    .put("callback_data", "pg:" + pid + ":" + (page - 1)));
        }
        nav.add(objectMapper.createObjectNode()
                .put("text", "🔄 Refresh")
                .put("callback_data", "pg:" + pid + ":" + page));
        if (totalPages > 1 && page < totalPages - 1) {
            nav.add(objectMapper.createObjectNode()
                    .put("text", "Next ►")
                    .put("callback_data", "pg:" + pid + ":" + (page + 1)));
        }
        kb.add(nav);

        ObjectNode markup = objectMapper.createObjectNode();
        markup.set("inline_keyboard", kb);

        sendOrEditQueueMessage(chatId, messageId, profile.getId(), sb.toString(), markup);
    }

    /** Send new or edit existing queue message; captures message_id on send. */
    private void sendOrEditQueueMessage(long chatId, long messageId,
            long profileId, String text, ObjectNode markup) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "MarkdownV2");
            body.set("reply_markup", markup);

            String endpoint;
            if (messageId < 0) {
                endpoint = "sendMessage";
            } else {
                body.put("message_id", messageId);
                endpoint = "editMessageText";
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/" + endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                if (messageId < 0) {
                    try {
                        JsonNode root = objectMapper.readTree(resp.body());
                        if (root.path("ok").asBoolean()) {
                            long newId = root.path("result").path("message_id").asLong(-1);
                            if (newId > 0)
                                lastQueueMsgIds.put(profileId, newId);
                        }
                    } catch (Exception ignore) {
                    }
                }
            } else {
                logger.warn("sendOrEditQueueMessage HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            logger.error("sendOrEditQueueMessage failed: {}", e.getMessage());
        }
    }

    /** Collects and sorts all task entries for a profile. */
    private List<TaskEntry> buildTaskEntries(long profileId, TaskQueue queue) {
        List<TaskEntry> list = new ArrayList<>();
        for (TpDailyTaskEnum task : TpDailyTaskEnum.values()) {
            if (!isStandardQueueTask(task)) {
                continue;
            }
            TaskStateData state = TaskManagementService.shared().lookupTaskState(profileId, task.getId());
            boolean scheduled = (queue != null) && queue.isTaskQueued(task);
            boolean executing = (state != null) && state.isExecuting();
            list.add(new TaskEntry(task, state, scheduled, executing));
        }
        list.sort((a, b) -> {
            int pa = a.isExecuting ? 0 : a.isScheduled ? 1 : 2;
            int pb = b.isExecuting ? 0 : b.isScheduled ? 1 : 2;
            if (pa != pb)
                return Integer.compare(pa, pb);
            if (a.nextDt != null && b.nextDt != null)
                return a.nextDt.compareTo(b.nextDt);
            if (a.nextDt != null)
                return -1;
            if (b.nextDt != null)
                return 1;
            return a.taskEnum.getName().compareTo(b.taskEnum.getName());
        });
        return list;
    }

    static boolean isStandardQueueTask(TpDailyTaskEnum task) {
        return task != TpDailyTaskEnum.CUSTOM_TASK;
    }

    // ── Callback handling ─────────────────────────────────────────────────────

    private void handleCallback(String callbackId, long chatId, long messageId, String data) {
        if (data == null || "noop".equals(data)) {
            answerCallbackQuery(callbackId, "");
            return;
        }
        String[] parts = data.split(":");
        if (parts.length < 1) {
            answerCallbackQuery(callbackId, "");
            return;
        }
        try {
            switch (parts[0]) {
                case "pg" -> {
                    // Navigation: edit the existing message to show a different page
                    if (parts.length < 3) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    answerCallbackQuery(callbackId, "");
                    long profileId = Long.parseLong(parts[1]);
                    int pageNum = Integer.parseInt(parts[2]);
                    AccountDescriptor profile = findProfile(profileId);
                    if (profile == null) {
                        sendMessage(chatId, "❌ Profile not found.");
                        return;
                    }
                    TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
                    sendQueuePage(chatId, messageId, profile, queue, pageNum);
                }
                case "rem" -> {
                    // format: rem:profileId:taskId:page
                    if (parts.length < 4) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    handleRemoveCallback(callbackId, chatId, messageId,
                            Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
                case "exe" -> {
                    // format: exe:profileId:taskId:page
                    if (parts.length < 4) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    handleExecuteCallback(callbackId, chatId, messageId,
                            Long.parseLong(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
                case "sch" -> {
                    // format: sch:profileId:taskId:page → open time-picker
                    if (parts.length < 4) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    answerCallbackQuery(callbackId, "");
                    showSchedulePicker(chatId, Long.parseLong(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
                case "scht" -> {
                    // format: scht:profileId:taskId:minutes:page → confirm schedule
                    if (parts.length < 5) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    handleScheduleAtCallback(callbackId, chatId,
                            Long.parseLong(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
                }
                case "prof_toggle" -> {
                    if (parts.length < 2) {
                        answerCallbackQuery(callbackId, "");
                        return;
                    }
                    handleProfileToggleCallback(callbackId, chatId, messageId, Long.parseLong(parts[1]));
                }
                case "log_dl" -> {
                    answerCallbackQuery(callbackId, "📥 Downloading...");
                    sendDocument(chatId, CURRENT_LOG_PATH);
                }
                default -> answerCallbackQuery(callbackId, "");
            }
        } catch (NumberFormatException e) {
            answerCallbackQuery(callbackId, "❌ Bad data");
            logger.warn("handleCallback: bad callback data '{}': {}", data, e.getMessage());
        }
    }

    private void handleRemoveCallback(String callbackId, long chatId, long messageId,
            long profileId, int taskId, int page) {
        try {
            TpDailyTaskEnum taskEnum = TpDailyTaskEnum.fromNumericId(taskId);
            AccountDescriptor profile = findProfile(profileId);
            if (profile == null) {
                answerCallbackQuery(callbackId, "❌ Profile not found");
                return;
            }
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
            if (queue == null) {
                answerCallbackQuery(callbackId, "❌ Queue not active — start the bot first");
                return;
            }
            if (!queue.isTaskQueued(taskEnum)) {
                answerCallbackQuery(callbackId, "ℹ️ Not scheduled — nothing to remove");
                return;
            }
            ScheduleService.obtain().evictTask(profileId, taskEnum);
            // Toast notification — appears as a brief popup over the keyboard
            answerCallbackQuery(callbackId, "🗑 Removed: " + taskEnum.getName());
            // Refresh the queue page in the same message so the user sees the updated state
            long editId = messageId > 0 ? messageId : lastQueueMsgIds.getOrDefault(profileId, -1L);
            sendQueuePage(chatId, editId, profile, queue, page);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackId, "❌ Unknown task");
        } catch (Exception e) {
            logger.error("handleRemoveCallback failed: {}", e.getMessage());
            answerCallbackQuery(callbackId, "❌ Remove failed");
        }
    }

    private void handleExecuteCallback(String callbackId, long chatId, long messageId,
            long profileId, int taskId, int page) {
        try {
            TpDailyTaskEnum taskEnum = TpDailyTaskEnum.fromNumericId(taskId);
            AccountDescriptor profile = findProfile(profileId);
            if (profile == null) {
                answerCallbackQuery(callbackId, "❌ Profile not found");
                return;
            }
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
            if (queue == null) {
                answerCallbackQuery(callbackId, "❌ Queue not active — start the bot first");
                return;
            }
            boolean recurring = queue.isTaskQueued(taskEnum);
            ScheduleService.obtain().persistDailyCompletion(profile, taskEnum, LocalDateTime.now());
            queue.runNow(taskEnum, recurring);
            // Toast notification
            answerCallbackQuery(callbackId, "▶️ Queued: " + taskEnum.getName()
                    + (recurring ? " (recurring)" : " (one-time)"));
            // Refresh the queue page in the same message
            long editId = messageId > 0 ? messageId : lastQueueMsgIds.getOrDefault(profileId, -1L);
            sendQueuePage(chatId, editId, profile, queue, page);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackId, "❌ Unknown task");
        } catch (Exception e) {
            logger.error("handleExecuteCallback failed: {}", e.getMessage());
            answerCallbackQuery(callbackId, "❌ Execute failed");
        }
    }

    private void showSchedulePicker(long chatId, long profileId, int taskId, int page) {
        try {
            TpDailyTaskEnum taskEnum = TpDailyTaskEnum.fromNumericId(taskId);
            AccountDescriptor profile = findProfile(profileId);
            if (profile == null) {
                sendMessage(chatId, "❌ Profile not found.");
                return;
            }

            // ── MarkdownV2 formatted message ──────────────────────────────────
            String text = "⏰ *Schedule Task*\n"
                    + "──────────────────────\n"
                    + "📌 *" + escV2(taskEnum.getName()) + "*\n"
                    + "👤 _" + escV2(profile.getName()) + "_\n"
                    + "──────────────────────\n"
                    + "Select a delay:";

            // Page number is threaded through scht so the queue message is refreshed
            // after the user picks a time. Format: scht:profileId:taskId:minutes:page
            String pfx = "scht:" + profileId + ":" + taskId + ":";
            ArrayNode kb = objectMapper.createArrayNode();

            // Row 1
            ArrayNode r1 = objectMapper.createArrayNode();
            r1.add(objectMapper.createObjectNode().put("text", "▶️  Now").put("callback_data", pfx + "0:" + page));
            r1.add(objectMapper.createObjectNode().put("text", "⌛  5 min").put("callback_data", pfx + "5:" + page));
            r1.add(objectMapper.createObjectNode().put("text", "⌛  15 min").put("callback_data", pfx + "15:" + page));
            kb.add(r1);

            // Row 2
            ArrayNode r2 = objectMapper.createArrayNode();
            r2.add(objectMapper.createObjectNode().put("text", "⌛  30 min").put("callback_data", pfx + "30:" + page));
            r2.add(objectMapper.createObjectNode().put("text", "⌛  1h").put("callback_data", pfx + "60:" + page));
            r2.add(objectMapper.createObjectNode().put("text", "⌛  2h").put("callback_data", pfx + "120:" + page));
            kb.add(r2);

            // Row 3
            ArrayNode r3 = objectMapper.createArrayNode();
            r3.add(objectMapper.createObjectNode().put("text", "⌛  4h").put("callback_data", pfx + "240:" + page));
            r3.add(objectMapper.createObjectNode().put("text", "⌛  8h").put("callback_data", pfx + "480:" + page));
            r3.add(objectMapper.createObjectNode().put("text", "❌  Cancel").put("callback_data", "noop"));
            kb.add(r3);

            ObjectNode markup = objectMapper.createObjectNode();
            markup.set("inline_keyboard", kb);
            sendOrEditQueueMessage(chatId, -1L, profileId, text, markup);
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Unknown task ID: " + taskId);
        } catch (Exception e) {
            logger.error("showSchedulePicker failed: {}", e.getMessage());
            sendMessage(chatId, "❌ Error: " + e.getMessage());
        }
    }

    private void handleScheduleAtCallback(String callbackId, long chatId,
            long profileId, int taskId, int minutes, int page) {
        try {
            TpDailyTaskEnum taskEnum = TpDailyTaskEnum.fromNumericId(taskId);
            AccountDescriptor profile = findProfile(profileId);
            if (profile == null) {
                answerCallbackQuery(callbackId, "❌ Profile not found");
                return;
            }
            TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profileId);
            if (queue == null) {
                answerCallbackQuery(callbackId, "❌ Queue not active — start the bot first");
                return;
            }

            LocalDateTime scheduledTime = (minutes == 0)
                    ? LocalDateTime.now()
                    : LocalDateTime.now().plusMinutes(minutes);
            boolean recurring = queue.isTaskQueued(taskEnum);

            DelayedTask dt = DelayedTaskRegistry.create(taskEnum, profile);
            if (dt == null) {
                answerCallbackQuery(callbackId, "❌ Cannot create task instance");
                return;
            }
            queue.dequeue(taskEnum);
            dt.reschedule(scheduledTime);
            dt.setRecurring(recurring);
            queue.enqueue(dt);

            ScheduleService.obtain().persistDailyCompletion(profile, taskEnum, scheduledTime);

            TaskStateData ts = new TaskStateData();
            ts.setProfileId(profileId);
            ts.setTaskId(taskId);
            ts.setScheduled(true);
            ts.setExecuting(false);
            ts.setNextExecutionTime(scheduledTime);
            TaskManagementService.shared().recordTaskState(profileId, ts);

            // Toast notification
            String whenStr = (minutes == 0) ? "now" : "in " + fmtMinutes(minutes);
            answerCallbackQuery(callbackId, "📅 " + taskEnum.getName() + " → " + whenStr
                    + (recurring ? " (recurring)" : " (one-time)"));
            // Refresh the stored queue page so the user sees the updated schedule
            long editId = lastQueueMsgIds.getOrDefault(profileId, -1L);
            sendQueuePage(chatId, editId, profile, queue, page);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackId, "❌ Unknown task");
        } catch (Exception e) {
            logger.error("handleScheduleAtCallback failed: {}", e.getMessage());
            answerCallbackQuery(callbackId, "❌ Schedule failed");
        }
    }

    // ── Advanced Commands & UI Utilities
    // ──────────────────────────────────────────

    private void sendOrEditMessage(long chatId, long messageId, String text, ObjectNode markup, String parseMode) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", parseMode);
            if (markup != null) {
                body.set("reply_markup", markup);
            }
            String endpoint = (messageId < 0) ? "sendMessage" : "editMessageText";
            if (messageId >= 0) {
                body.put("message_id", messageId);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/" + endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warn("sendOrEditMessage HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            logger.error("sendOrEditMessage failed: {}", e.getMessage());
        }
    }

    private void handleStatsCommand(long chatId) {
        try {
            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            if (profiles == null || profiles.isEmpty()) {
                sendMessage(chatId, "❌ No profiles configured.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("📈 *Bot Statistics*\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
            for (AccountDescriptor p : profiles) {
                if (!p.getEnabled())
                    continue;
                sb.append("✨ *").append(escV2(p.getName())).append("*\n");
                if (p.getCharacterName() != null && !p.getCharacterName().isEmpty()) {
                    sb.append("👤 _").append(escV2(p.getCharacterName())).append("_\n");
                }

                dev.frostguard.api.domain.ProfilesData stats = StatisticsService.obtain().loadMetrics(p);
                if (stats != null && stats.getCustomCounters() != null && !stats.getCustomCounters().isEmpty()) {
                    sb.append("\n📊 *Activity:*\n");
                    for (java.util.Map.Entry<String, Integer> entry : stats.getCustomCounters().entrySet()) {
                        sb.append("  ▫️ *").append(escV2(entry.getKey())).append("*: `")
                                .append(String.valueOf(entry.getValue())).append("`\n");
                    }
                }
                sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n\n");
            }
            if (sb.toString().endsWith("\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n")) {
                sb.setLength(sb.length() - 25);
            }
            sendOrEditMessage(chatId, -1L, sb.toString(), null, "MarkdownV2");
        } catch (Exception e) {
            logger.error("handleStatsCommand failed: {}", e.getMessage());
            sendMessage(chatId, "❌ Error retrieving stats.");
        }
    }

    private void handleProfilesCommand(long chatId, long messageId) {
        try {
            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            if (profiles == null || profiles.isEmpty()) {
                sendMessage(chatId, "❌ No profiles configured.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("👥 *Profiles*\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━\n");
            if (botCurrentlyRunning) {
                sb.append("⚠️ _Stop the bot to enable/disable profiles_\n\n");
            } else {
                sb.append("💡 _Tap below to toggle_\n\n");
            }
            ArrayNode kb = objectMapper.createArrayNode();
            for (AccountDescriptor p : profiles) {
                String status = p.getEnabled() ? "🟢 ON" : "🔴 OFF";
                sb.append(status).append(" \\· *").append(escV2(p.getName())).append("*\n");
                sb.append("      Emulator: `").append(escV2(p.getEmulatorNumber())).append("`\n\n");
                ArrayNode row = objectMapper.createArrayNode();
                row.add(objectMapper.createObjectNode()
                        .put("text", (p.getEnabled() ? "Disable 🔴 " : "Enable 🟢 ") + p.getName())
                        .put("callback_data", "prof_toggle:" + p.getId()));
                kb.add(row);
            }
            ObjectNode markup = objectMapper.createObjectNode();
            markup.set("inline_keyboard", kb);
            sendOrEditMessage(chatId, messageId, sb.toString(), markup, "MarkdownV2");
        } catch (Exception e) {
            logger.error("handleProfilesCommand failed: {}", e.getMessage());
        }
    }

    private void handleProfileToggleCallback(String callbackId, long chatId, long messageId, long profileId) {
        if (botCurrentlyRunning) {
            answerCallbackQuery(callbackId, "❌ Stop bot before toggling");
            return;
        }
        AccountDescriptor profile = findProfile(profileId);
        if (profile == null) {
            answerCallbackQuery(callbackId, "❌ Profile not found");
            return;
        }
        profile.setEnabled(!profile.getEnabled());
        ProfileService.obtain().persistAccount(profile);
        answerCallbackQuery(callbackId,
                "✅ " + profile.getName() + " " + (profile.getEnabled() ? "enabled" : "disabled"));
        handleProfilesCommand(chatId, messageId);
    }

    private void handleRebootCommand(long chatId) {
        try {
            sendMessage(chatId, "🔄 Restarting...");

            try {
                ScheduleService.obtain().haltEngine();
            } catch (Exception ex) {
                logger.warn("Reboot: stopBot soft handled: {}", ex.getMessage());
            }
            Thread.sleep(1500);

            List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
            if (profiles != null) {
                for (AccountDescriptor p : profiles) {
                    if (p.getEnabled()) {
                        String emu = p.getEmulatorNumber();
                        try {
                            if (emu != null && !emu.trim().isEmpty()) {
                                EmulatorController.getInstance().closeEmulator(emu);
                            }
                        } catch (Exception ex) {
                            logger.warn("Reboot: Emulator close soft handled: {}", ex.getMessage());
                        }
                        Thread.sleep(1000);
                    }
                }
            }

            ScheduleService.obtain().launchEngine();
            sendMessage(chatId, "✅ Restarted.");

        } catch (Exception e) {
            logger.error("handleRebootCommand failed", e);
            sendMessage(chatId, "❌ Reboot failed: " + e.getMessage());
        }
    }

    private void handleLogsCommand(long chatId) {
        String text = "📄 *Log Center*\n"
                + "━━━━━━━━━━━━━━━━━━━━━━\n"
                + "Select a log file to download:";

        ArrayNode kb = objectMapper.createArrayNode();
        ArrayNode row = objectMapper.createArrayNode();

        row.add(objectMapper.createObjectNode()
                .put("text", "📄 frostguard.log")
                .put("callback_data", "log_dl"));
        kb.add(row);

        ObjectNode markup = objectMapper.createObjectNode();
        markup.set("inline_keyboard", kb);

        sendOrEditMessage(chatId, -1L, text, markup, "MarkdownV2");
    }

    private void sendDocument(long chatId, String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                sendMessage(chatId, "❌ Log file not found at " + filePath);
                return;
            }
            String boundary = "TelegramBotBoundary" + System.currentTimeMillis();
            byte[] header = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                    + chatId + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"document\"; filename=\"" + file.getName() + "\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
            byte[] body = new byte[header.length + fileBytes.length + footer.length];
            System.arraycopy(header, 0, body, 0, header.length);
            System.arraycopy(fileBytes, 0, body, header.length, fileBytes.length);
            System.arraycopy(footer, 0, body, header.length + fileBytes.length, footer.length);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/sendDocument"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("sendDocument HTTP {}: {}", response.statusCode(), response.body());
                sendMessage(chatId, "❌ Telegram rejected the document.");
            }
        } catch (Exception e) {
            logger.error("sendDocument error: {}", e.getMessage());
            sendMessage(chatId, "❌ Error sending Document: " + e.getMessage());
        }
    }

    private AccountDescriptor findProfile(long profileId) {
        List<AccountDescriptor> all = ProfileService.obtain().fetchAllAccounts();
        if (all == null)
            return null;
        return all.stream()
                .filter(p -> p.getId() != null && p.getId() == profileId)
                .findFirst().orElse(null);
    }

    private void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("callback_query_id", callbackQueryId);
            if (text != null && !text.isBlank())
                body.put("text", text);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/answerCallbackQuery"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    private void sendMessageWithMarkup(long chatId, String text, ObjectNode markup) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            body.set("reply_markup", markup);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("sendMessageWithMarkup failed: {}", e.getMessage());
        }
    }

    /**
     * Escape for Telegram legacy Markdown v1 (used in simple sendMessage calls).
     */
    private static String escMd(String text) {
        if (text == null)
            return "";
        return text.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`").replace("[", "\\[");
    }

    /**
     * Escape all reserved characters for Telegram MarkdownV2.
     * Required before embedding any user-supplied or dynamic text in a MarkdownV2
     * message.
     */
    private static String escV2(String text) {
        if (text == null)
            return "";
        // Order matters: backslash must be first
        return text
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    private static String fmtMinutes(int minutes) {
        if (minutes < 60)
            return minutes + " min";
        int h = minutes / 60, m = minutes % 60;
        return (m == 0) ? h + "h" : h + "h " + m + "m";
    }

    // ── TaskEntry : display data for one task ─────────────────────────────────

    private static class TaskEntry {
        final TpDailyTaskEnum taskEnum;
        final boolean isScheduled;
        final boolean isExecuting;
        final LocalDateTime nextDt;
        final String statusIcon;
        final String lastStr;
        final String nextStr;

        TaskEntry(TpDailyTaskEnum taskEnum, TaskStateData state, boolean scheduled, boolean executing) {
            this.taskEnum = taskEnum;
            this.isScheduled = scheduled;
            this.isExecuting = executing;
            this.nextDt = (state != null) ? state.getNextExecutionTime() : null;
            LocalDateTime last = (state != null) ? state.getLastExecutionTime() : null;

            long secsUntil = (nextDt != null)
                    ? java.time.temporal.ChronoUnit.SECONDS.between(LocalDateTime.now(), nextDt)
                    : Long.MAX_VALUE;

            this.lastStr = formatAgo(last);
            this.nextStr = executing ? "Executing…"
                    : (nextDt == null) ? "Never"
                            : (secsUntil <= 0) ? "Ready"
                                    : fmtCountdown(secsUntil);

            this.statusIcon = executing ? "🔄"
                    : (scheduled && secsUntil <= 0) ? "🟢"
                            : scheduled ? "🟡"
                                    : (nextDt != null) ? "🔵"
                                            : "⬜";
        }

        private static String formatAgo(LocalDateTime dt) {
            if (dt == null)
                return "—";
            long s = java.time.temporal.ChronoUnit.SECONDS.between(dt, LocalDateTime.now());
            if (s < 0)
                return "Future";
            if (s < 60)
                return s + "s ago";
            if (s < 3600)
                return (s / 60) + "m ago";
            if (s < 86400)
                return (s / 3600) + "h " + ((s % 3600) / 60) + "m ago";
            return (s / 86400) + "d ago";
        }

        static String fmtCountdown(long secs) {
            if (secs < 60)
                return secs + "s";
            if (secs < 3600)
                return (secs / 60) + "m";
            if (secs < 86400)
                return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
            long d = secs / 86400;
            return d + "d " + ((secs % 86400) / 3600) + "h";
        }
    }
}
