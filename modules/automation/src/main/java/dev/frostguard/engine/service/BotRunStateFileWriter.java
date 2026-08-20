package dev.frostguard.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.engine.listener.BotStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Writes automation queue run-state to telemetry/bot-run-state.json on every
 * transition, so external processes can read "is the queue currently
 * running" synchronously off disk — no HTTP round-trip, no Telegram
 * dependency (TelegramBotService's local command server only starts when a
 * real bot token is configured, which is usually not the case).
 *
 * "if the application's closed, I do not want it starting
 * up. And if I manually stop the bot, I do not want it turning on." The
 * 2h full-capture sync (run-full-capture.js) needs a reliable signal for
 * this so it only restores a queue that was ALREADY running before it briefly
 * stopped Bearguard for the scrape — never a fresh, unsolicited start.
 *
 * Registered unconditionally at boot (FXApp/HeadlessApp), independent of any
 * Telegram configuration. Writes an initial "not running" snapshot immediately
 * on registration so a stale file from a previous session (which might claim
 * running=true) never survives past this boot.
 */
public class BotRunStateFileWriter implements BotStateListener {

    private static final Logger logger = LoggerFactory.getLogger(BotRunStateFileWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path OUT_FILE =
            Paths.get(System.getProperty("user.dir"), "telemetry", "bot-run-state.json");

    public BotRunStateFileWriter() {
        write(false);
    }

    @Override
    public void onEngineStateTransition(BotStateData snapshot) {
        write(snapshot != null && snapshot.isOperational());
    }

    @Override
    public void onEngineStarting() {
        write(true);
    }

    @Override
    public void onEngineStopped() {
        write(false);
    }

    private void write(boolean running) {
        try {
            Files.createDirectories(OUT_FILE.getParent());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("queueRunning", running);
            node.put("updatedAt", Instant.now().toString());
            node.put("pid", ProcessHandle.current().pid());
            Files.write(OUT_FILE, node.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("BotRunStateFileWriter: failed to write {}: {}", OUT_FILE, e.getMessage());
        }
    }
}
