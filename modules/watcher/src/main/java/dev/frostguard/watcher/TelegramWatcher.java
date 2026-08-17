package dev.frostguard.watcher;

import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Always-on standalone Telegram watcher — the ONLY Telegram long-poll process.
 *
 * This process runs permanently (Windows Startup / HKCU\Run) and is the sole
 * consumer of the Telegram Bot API's getUpdates endpoint. The main bot app no
 * longer polls Telegram; instead it exposes a local HTTP command server on
 * 127.0.0.1:{localPort} that this watcher forwards app-level commands to.
 *
 * Watcher-owned commands (handled here regardless of app state):
 *   /launch          — start the main bot JAR
 *   /launch_headless — start the main bot JAR without UI
 *   /kill            — terminate the main bot JAR process
 *   /wstatus         — report whether the bot process is alive
 *   /whelp           — list all commands
 *
 * Everything else is forwarded to http://127.0.0.1:{localPort}/command.
 * Callback queries (inline keyboard taps) are also forwarded to the app.
 *
 * Single-instance guarantee: a FileLock in the selected workspace's watcher
 * directory prevents duplicate watcher processes for that workspace.
 *
 * Configuration: &lt;workspace&gt;/watcher/telegram-watcher.properties
 *   token=<BotFather token>
 *   chatId=<your Telegram numeric chat-ID>
 *   botJarPath=<absolute path to frostguard-x.x.x.jar>
 *   localPort=8765
 */
public class TelegramWatcher {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWatcher.class);
    private static final String API_BASE         = "https://api.telegram.org/bot";
    private static final int    LONG_POLL_TIMEOUT = 30;
    private static final String APP_LAUNCHER_PROPERTY = "frostguard.launcher";
    private static final String WATCHER_LAUNCHER_PROPERTY = "frostguard.watcher.launcher";
    private static final String NATIVE_SMOKE_ARGUMENT = "--frostguard-native-smoke-test";

    public static Path configFilePath() {
        return WorkspacePaths.current().watcherConfig();
    }

    // ── entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        WorkspacePaths workspace = WorkspacePaths.current();
        WorkspaceSession.initializeLayout(workspace);
        if (java.util.Arrays.asList(args).contains(NATIVE_SMOKE_ARGUMENT)) {
            loadConfig();
            Files.writeString(workspace.cache().resolve("native-watcher-smoke.properties"),
                    "channel=" + workspace.channel().directoryName() + "\n"
                            + "applicationId=" + System.getProperty(WorkspacePaths.APPLICATION_ID_PROPERTY, "") + "\n"
                            + "workspace=" + workspace.root() + "\n"
                            + "applicationDir=" + System.getProperty("user.dir") + "\n"
                            + "appLauncher=" + System.getProperty(APP_LAUNCHER_PROPERTY, "") + "\n"
                            + "watcherLauncher=" + System.getProperty(WATCHER_LAUNCHER_PROPERTY, "") + "\n",
                    StandardCharsets.UTF_8);
            return;
        }
        System.out.println("==============================================");
        System.out.println("  Frostguard – Telegram Watcher");
        System.out.println("  Config: " + configFilePath());
        System.out.println("==============================================");

        // ── Single-instance lock ───────────────────────────────────────────────
        Path lockFilePath = WorkspacePaths.current().watcherLock();
        Files.createDirectories(lockFilePath.getParent());
        FileChannel lockChannel = FileChannel.open(lockFilePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fileLock = lockChannel.tryLock();
        if (fileLock == null) {
            System.err.println("[ERROR] Another instance of the Telegram Watcher is already running.");
            System.err.println("        If you are sure no other instance is running, delete: " + lockFilePath);
            System.exit(0);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { fileLock.release(); lockChannel.close(); } catch (Exception ignored) {}
        }));

        // ── Load config ───────────────────────────────────────────────────────
        Properties cfg  = loadConfig();
        String token    = cfg.getProperty("token",      "").trim();
        String chatId   = cfg.getProperty("chatId",     "").trim();
        String jarPath  = cfg.getProperty("botJarPath", "").trim();
        int    localPort;
        try {
            localPort = Integer.parseInt(
                    cfg.getProperty("localPort",
                            String.valueOf(WorkspacePaths.current().defaultLocalPort())).trim());
        } catch (NumberFormatException e) {
            localPort = WorkspacePaths.current().defaultLocalPort();
        }

        if (token.isBlank()) {
            System.err.println("[ERROR] 'token' is not set in " + configFilePath());
            System.exit(1);
        }
        if (jarPath.isBlank()) {
            System.err.println("[ERROR] 'botJarPath' is not set in " + configFilePath());
            System.exit(1);
        }

        long allowedChatId = 0;
        if (!chatId.isBlank()) {
            try {
                allowedChatId = Long.parseLong(chatId);
            } catch (NumberFormatException e) {
                System.err.println("[WARN] chatId '" + chatId + "' is not a valid number.");
            }
        }

        new TelegramWatcher(token, allowedChatId, jarPath, localPort).run();
    }

    // ── instance fields ───────────────────────────────────────────────────────

    private final String        token;
    private final long          allowedChatId;
    private final File          botJar;
    private final File          botJarDir;
    private final File          botLauncher;
    private final int           localPort;
    private final String        workspaceId;
    private final HttpClient    httpClient;
    private final ObjectMapper  mapper = new ObjectMapper();

    private long              lastUpdateId  = -1;
    private volatile Process  botProcess    = null;
    private final ReentrantLock launchLock  = new ReentrantLock();

    private static final long BACKOFF_INITIAL_MS = 5_000;
    private static final long BACKOFF_MAX_MS     = 60_000;
    private long currentBackoffMs = BACKOFF_INITIAL_MS;

    private TelegramWatcher(String token, long allowedChatId, String jarPath, int localPort) {
        this.token         = token;
        this.allowedChatId = allowedChatId;
        this.botJar        = new File(jarPath);
        this.botJarDir     = botJar.getParentFile();
        String launcherPath = System.getProperty(APP_LAUNCHER_PROPERTY, "").trim();
        this.botLauncher   = launcherPath.isBlank() ? null : new File(launcherPath);
        this.localPort     = localPort;
        this.workspaceId   = WorkspacePaths.current().identity();
        this.httpClient    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── polling loop ──────────────────────────────────────────────────────────

    private void run() {
        logger.info("Watcher started. Sole Telegram poller. Forwarding app commands to port {}.", localPort);
        System.out.println("[INFO] Watcher running. Send /whelp for the command list.");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String url = API_BASE + token
                        + "/getUpdates?timeout=" + LONG_POLL_TIMEOUT
                        + "&allowed_updates=%5B%22message%22%2C%22callback_query%22%5D"
                        + (lastUpdateId >= 0 ? "&offset=" + (lastUpdateId + 1) : "");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(LONG_POLL_TIMEOUT + 10))
                        .GET().build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    if (currentBackoffMs != BACKOFF_INITIAL_MS) {
                        logger.info("Network recovered.");
                    }
                    currentBackoffMs = BACKOFF_INITIAL_MS;

                    JsonNode root = mapper.readTree(response.body());
                    if (root.path("ok").asBoolean()) {
                        for (JsonNode update : root.path("result")) {
                            // Advance offset BEFORE processing — prevents replay on crash
                            long uid = update.path("update_id").asLong(-1);
                            if (uid >= 0) lastUpdateId = uid;
                            processUpdate(update);
                        }
                    }
                } else {
                    logger.warn("Unexpected HTTP {}: {}", response.statusCode(), response.body());
                    Thread.sleep(5_000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ConnectException | java.nio.channels.UnresolvedAddressException e) {
                logger.warn("Network unavailable — retrying in {}s", currentBackoffMs / 1000);
                try { Thread.sleep(currentBackoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                currentBackoffMs = Math.min(currentBackoffMs * 2, BACKOFF_MAX_MS);
            } catch (Exception e) {
                logger.error("Poll error [{}]: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // ── update dispatch ───────────────────────────────────────────────────────

    private void processUpdate(JsonNode update) {
        // ── Inline keyboard button taps → forward to app ──────────────────────
        JsonNode cq = update.path("callback_query");
        if (!cq.isMissingNode()) {
            long chatId = cq.path("message").path("chat").path("id").asLong(-1);
            if (chatId < 0) return;
            if (allowedChatId != 0 && chatId != allowedChatId) return;
            forwardCallbackToApp(
                    cq.path("id").asText(""),
                    chatId,
                    cq.path("message").path("message_id").asLong(-1),
                    cq.path("data").asText("noop"));
            return;
        }

        // ── Regular text message ──────────────────────────────────────────────
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        long chatId = message.path("chat").path("id").asLong(-1);
        if (chatId < 0) return;

        if (allowedChatId != 0 && chatId != allowedChatId) {
            logger.debug("Rejected message from unauthorized chat-ID {}", chatId);
            sendMessage(chatId, "⛔ Unauthorized. Your chat ID is: " + chatId);
            return;
        }

        String text = message.path("text").asText("").trim().toLowerCase();

        // ── Watcher-owned commands ────────────────────────────────────────────
        if (text.startsWith("/launch_headless")) {
            handleLaunch(chatId, true);
        } else if (text.startsWith("/launch")) {
            handleLaunch(chatId, false);
        } else if (text.startsWith("/kill")) {
            handleKill(chatId);
        } else if (text.startsWith("/wstatus")) {
            handleWStatus(chatId);
        } else if (text.startsWith("/whelp")) {
            sendMessage(chatId, buildHelpMessage());
        } else {
            // Everything else → forward to the running bot app
            forwardToApp(chatId, text);
        }
    }

    // ── action handlers ───────────────────────────────────────────────────────

    private void handleLaunch(long chatId, boolean headless) {
        if (!launchLock.tryLock()) {
            sendMessage(chatId, "⏳ Launch already in progress, please wait…");
            return;
        }
        try {
            if (isBotProcessAlive()) {
                sendMessage(chatId, "ℹ️ Bot app is already running.");
                return;
            }
            if ((botLauncher == null || !botLauncher.isFile()) && !botJar.exists()) {
                sendMessage(chatId, "❌ JAR not found at:\n`" + botJar.getAbsolutePath()
                        + "`\nCheck the path in the Telegram config panel.");
                return;
            }
            try {
                String javaExe = ProcessHandle.current().info().command().orElse("java");
                java.util.List<String> command = botLaunchCommand(
                        botLauncher == null ? null : botLauncher.toPath(),
                        Path.of(javaExe), botJar.toPath(), WorkspacePaths.current(), headless);
                ProcessBuilder pb = new ProcessBuilder(command);
                
                // Use the parent of the bot jar as the working directory, unless it's in a 'target' dir
                // (to support Maven multi-module dev workflows where the root is one level up).
                File workDir;
                if (botLauncher != null && botLauncher.isFile()) {
                    workDir = botLauncher.getParentFile();
                    pb.environment().put("FROSTGUARD_WORKSPACE", WorkspacePaths.current().root().toString());
                    pb.environment().put("FROSTGUARD_CHANNEL",
                            WorkspacePaths.current().channel().directoryName());
                } else {
                    workDir = botJarDir;
                    if ("target".equalsIgnoreCase(botJarDir.getName())
                            && botJarDir.toPath().endsWith(Path.of("modules", "desktop", "target"))) {
                        workDir = botJarDir.getParentFile().getParentFile().getParentFile();
                    }
                }
                pb.directory(workDir);

                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                botProcess = pb.start();
                logger.info("Launched bot process PID {}", botProcess.pid());
                sendMessage(chatId, "▶️ Bot app launched! (PID " + botProcess.pid() + ")\n"
                        + "Use /startbot to start the automation once the app has loaded.");
            } catch (IOException e) {
                logger.error("Failed to launch bot: {}", e.getMessage());
                sendMessage(chatId, "❌ Failed to launch bot: " + e.getMessage());
            }
        } finally {
            launchLock.unlock();
        }
    }

    static java.util.List<String> botLaunchCommand(Path nativeLauncher, Path javaExecutable,
            Path botJar, WorkspacePaths workspace, boolean headless) {
        java.util.List<String> command = new java.util.ArrayList<>();
        if (nativeLauncher != null) {
            command.add(nativeLauncher.toAbsolutePath().normalize().toString());
        } else {
            command.add(javaExecutable.toString());
            command.add("-D" + WorkspacePaths.WORKSPACE_PROPERTY + "=" + workspace.root());
            command.add("-D" + WorkspacePaths.CHANNEL_PROPERTY + "="
                    + workspace.channel().directoryName());
            command.add("-jar");
            command.add(botJar.toAbsolutePath().normalize().toString());
        }
        if (headless) {
            command.add("--headless");
        }
        return command;
    }

    private void handleKill(long chatId) {
        if (!isBotProcessAlive()) {
            sendMessage(chatId, "ℹ️ Bot app is not running.");
            return;
        }
        
        // First try to kill our tracked process
        if (botProcess != null && botProcess.isAlive()) {
            long pid = botProcess.pid();
            botProcess.destroyForcibly();
            botProcess = null;
            logger.info("Killed tracked bot process PID {}", pid);
            sendMessage(chatId, "⏹️ Bot app has been terminated.");
            return;
        }
        
        // Try to get the PID from the bot's command server
        Long remotePid = fetchWorkspacePid();
        
        // If we got a PID from the server, kill that process
        if (remotePid != null) {
            final long pidToKill = remotePid;
            boolean killed = ProcessHandle.of(pidToKill)
                    .map(ph -> {
                        ph.destroyForcibly();
                        logger.info("Killed bot process PID {} (from /pid endpoint)", pidToKill);
                        return true;
                    })
                    .orElse(false);
            
            if (killed) {
                botProcess = null;
                sendMessage(chatId, "⏹️ Bot app has been terminated (PID " + pidToKill + ").");
                return;
            }
        }
        
        // Fallback: search for and kill any running bot process
        logger.debug("Searching for bot process to kill. Jar name: {}", botJar.getName());
        
        ProcessHandle foundProcess = ProcessHandle.allProcesses()
                .filter(ph -> {
                    String cmd = ph.info().command().orElse("");
                    if (!cmd.toLowerCase().contains("java")) return false;
                    
                    String cmdLine = ph.info().commandLine().orElse("");
                    boolean matches = cmdLine.contains(botJar.getName())
                        || cmdLine.contains("dev.frostguard.app.bootstrap.Main")
                        || cmdLine.contains("dev.frostguard.app.bootstrap.FXApp")
                        || cmdLine.contains("dev.frostguard.app.bootstrap.HeadlessApp");
                    matches = matches && cmdLine.contains(WorkspacePaths.current().root().toString());
                    
                    if (matches) {
                        logger.debug("Found matching process PID {}: {}", ph.pid(), cmdLine);
                    }
                    return matches;
                })
                .findFirst()
                .orElse(null);
        
        if (foundProcess != null) {
            long pid = foundProcess.pid();
            foundProcess.destroyForcibly();
            botProcess = null; // Clear our reference
            logger.info("Killed bot process PID {}", pid);
            sendMessage(chatId, "⏹️ Bot app has been terminated (PID " + pid + ").");
        } else {
            // The network check says it's running, but we can't find the process
            logger.warn("Bot appears to be running (network check passed) but process not found");
            sendMessage(chatId, "⚠️ Bot app is running but the process could not be found.\n"
                    + "It may be running under a different user or with restricted access.\n"
                    + "Try closing it manually or restarting your system.");
        }
    }

    private void handleWStatus(long chatId) {
        if (isBotProcessAlive()) {
            String pid = botProcess != null ? " (PID " + botProcess.pid() + ")" : "";
            sendMessage(chatId, "✅ Bot app is *running*" + pid + ".");
        } else {
            sendMessage(chatId, "🔴 Bot app is *not running*. Send /launch to start it.");
        }
    }

    // ── forwarding ────────────────────────────────────────────────────────────

    /**
     * Forward a text command to the bot app's local command server.
     * On ConnectException the app is not running — inform the user.
     */
    private void forwardToApp(long chatId, String text) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("type",   "message");
            body.put("chatId", chatId);
            body.put("text",   text);
            body.put("workspaceId", workspaceId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + "/command"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.warn("App command server replied HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (ConnectException e) {
            logger.info("Bot app not running — command '{}' not forwarded", text);
            sendMessage(chatId, "🔴 Bot app is not running.\nSend /launch to start it.");
        } catch (Exception e) {
            logger.error("forwardToApp failed: {}", e.getMessage());
            sendMessage(chatId, "❌ Could not reach the bot app: " + e.getMessage());
        }
    }

    /** Forward an inline-keyboard callback to the bot app's local command server. */
    private void forwardCallbackToApp(String callbackId, long chatId, long messageId, String data) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("type",       "callback");
            body.put("chatId",     chatId);
            body.put("messageId",  messageId);
            body.put("callbackId", callbackId);
            body.put("data",       data);
            body.put("workspaceId", workspaceId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + "/command"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            logger.info("Bot app not running — callback '{}' not forwarded", data);
            answerCallbackQuery(callbackId, "❌ Bot app is not running");
        } catch (Exception e) {
            logger.error("forwardCallbackToApp failed: {}", e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isBotProcessAlive() {
        // First check our tracked process reference
        if (botProcess != null) {
            if (botProcess.isAlive()) {
                logger.debug("Bot process alive via tracked reference PID {}", botProcess.pid());
                return true;
            }
            botProcess = null; // clear stale reference
        }
        
        if (fetchWorkspacePid() != null) {
            logger.debug("Bot process alive via network check on port {}", localPort);
            return true;
        }
        
        // Fallback: search for any running bot process
        boolean found = ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().map(c -> c.toLowerCase().contains("java")).orElse(false))
                .filter(ph -> {
                    String cmd = ph.info().commandLine().orElse("");
                    boolean matchesApp = cmd.contains(botJar.getName())
                        || cmd.contains("dev.frostguard.app.bootstrap.Main")
                        || cmd.contains("dev.frostguard.app.bootstrap.FXApp")
                        || cmd.contains("dev.frostguard.app.bootstrap.HeadlessApp");
                    return matchesApp && cmd.contains(WorkspacePaths.current().root().toString());
                })
                .findFirst()
                .isPresent();
        
        if (found) {
            logger.debug("Bot process alive via process search");
        } else {
            logger.debug("Bot process not found via any method");
        }
        return found;
    }

    private Long fetchWorkspacePid() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + localPort + "/pid"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.debug("PID endpoint on port {} replied HTTP {}", localPort, response.statusCode());
                return null;
            }
            JsonNode json = mapper.readTree(response.body());
            if (!workspaceId.equals(json.path("workspaceId").asText())) {
                logger.warn("Ignoring command server on port {} because it belongs to another workspace", localPort);
                return null;
            }
            long pid = json.path("pid").asLong(-1);
            return pid > 0 ? pid : null;
        } catch (ConnectException connectionRefused) {
            logger.debug("No command server listening on port {}", localPort);
            return null;
        } catch (Exception failure) {
            logger.debug("Could not read matching PID from port {}: {}", localPort, failure.getMessage());
            return null;
        }
    }

    private static String buildHelpMessage() {
        return  "╔══════════════════════════╗\n"
              + "║   🤖  Frostguard  •  Help   ║\n"
              + "╚══════════════════════════╝\n\n"
              + "🚀 *Process Control* _(watcher – always on)_\n"
              + "`/launch`           — Start the bot app (with UI)\n"
              + "`/launch_headless`  — Start the bot app without UI\n"
              + "`/kill`             — Force-close the bot app\n"
              + "`/wstatus`          — Check if the bot app is running\n\n"
              + "⚙️ *Automation Control* _(requires app running)_\n"
              + "`/startbot`    — Begin the automation routines\n"
              + "`/stopbot`     — Pause the automation routines\n"
              + "`/status`      — Show whether automation is running\n"
              + "`/reboot`      — Restart bot + emulators\n\n"
              + "📊 *Monitoring & Info*\n"
              + "`/screenshot`  — Capture & send emulator screen\n"
              + "`/queue`       — Task queue (schedule / remove / run)\n"
              + "`/stats`       — Bot activity statistics per profile\n"
              + "`/logs`        — Download the Frostguard log\n"
              + "`/profiles`    — View & toggle profiles on/off\n\n"
              + "❓ *Help*\n"
              + "`/help`        — Show this message\n"
              + "`/whelp`       — Show help (works even if app is closed)\n\n"
              + "💡 _Tip: use the keyboard buttons below for quick access._\n"
              + "_If the app is closed, use /launch first, then /startbot._";
    }

    /** Send a Telegram message with the persistent reply keyboard. */
    private void sendMessage(long chatId, String text) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("chat_id",    chatId);
            body.put("text",       text);
            body.put("parse_mode", "Markdown");

            // Persistent reply keyboard — identical to TelegramBotService.sendMessage()
            ArrayNode keyboard = mapper.createArrayNode();
            ArrayNode row1 = mapper.createArrayNode();
            row1.add(mapper.createObjectNode().put("text", "📸 SCREENSHOT"));
            row1.add(mapper.createObjectNode().put("text", "📋 QUEUE"));
            row1.add(mapper.createObjectNode().put("text", "📈 STATS"));
            keyboard.add(row1);
            ArrayNode row2 = mapper.createArrayNode();
            row2.add(mapper.createObjectNode().put("text", "▶️ START BOT"));
            row2.add(mapper.createObjectNode().put("text", "⏹️ STOP BOT"));
            keyboard.add(row2);
            ArrayNode row3 = mapper.createArrayNode();
            row3.add(mapper.createObjectNode().put("text", "👥 PROFILES"));
            row3.add(mapper.createObjectNode().put("text", "📄 LOGS"));
            row3.add(mapper.createObjectNode().put("text", "🔄 REBOOT"));
            keyboard.add(row3);
            ArrayNode row4 = mapper.createArrayNode();
            row4.add(mapper.createObjectNode().put("text", "ℹ️ STATUS"));
            row4.add(mapper.createObjectNode().put("text", "❓ HELP"));
            keyboard.add(row4);

            ObjectNode markup = mapper.createObjectNode();
            markup.set("keyboard", keyboard);
            markup.put("resize_keyboard", true);
            markup.put("is_persistent",   true);
            body.set("reply_markup", markup);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("sendMessage failed: {}", e.getMessage());
        }
    }

    /** Answer a Telegram callback query (shows a brief pop-up on the user's device). */
    private void answerCallbackQuery(String callbackId, String notice) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("callback_query_id", callbackId);
            if (notice != null && !notice.isBlank()) body.put("text", notice);
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

    private static Properties loadConfig() throws IOException {
        Path cfg = configFilePath();
        if (!Files.exists(cfg)) {
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg,
                    "# Frostguard – Telegram Watcher configuration\n"
                  + "# Generated automatically. You can also edit this by hand.\n"
                  + "token=\n"
                  + "chatId=\n"
                  + "botJarPath=\n"
                  + "localPort=" + WorkspacePaths.current().defaultLocalPort() + "\n");
            System.out.println("[INFO] Created config template at: " + cfg);
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(cfg.toFile())) {
            props.load(fis);
        }
        return props;
    }
}
