package dev.frostguard.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Minimal local HTTP server that receives forwarded Telegram commands from the
 * standalone TelegramWatcher process and dispatches them to TelegramBotService.
 *
 * Listens on 127.0.0.1:{port} — localhost only, never exposed to the network.
 *
 * Single endpoint: POST /command
 *
 * Text command payload:
 *   {"type":"message","chatId":12345,"text":"/startbot"}
 *
 * Callback query payload:
 *   {"type":"callback","chatId":12345,"messageId":456,"callbackId":"abc","data":"rem:1:2:0"}
 */
public class WatcherCommandServer {

    private static final Logger logger = LoggerFactory.getLogger(WatcherCommandServer.class);

    private final int               port;
    private final TelegramBotService botService;
    private final ObjectMapper       mapper = new ObjectMapper();
    private HttpServer               server;

    public WatcherCommandServer(int port, TelegramBotService botService) {
        this.port       = port;
        this.botService = botService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 10);
        server.createContext("/command", this::handleRequest);
        server.createContext("/pid", this::handlePidRequest);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        logger.info("WatcherCommandServer listening on 127.0.0.1:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            logger.info("WatcherCommandServer stopped.");
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0);
            exchange.close();
            return;
        }

        byte[] responseBytes;
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req  = mapper.readTree(body);
            String   type = req.path("type").asText("message");
            long   chatId = req.path("chatId").asLong(-1);

            if ("callback".equals(type)) {
                String callbackId = req.path("callbackId").asText("");
                long   messageId  = req.path("messageId").asLong(-1);
                String data       = req.path("data").asText("noop");
                botService.handleCallbackQuery(callbackId, chatId, messageId, data);
            } else {
                String text = req.path("text").asText("");
                botService.handleTextCommand(chatId, text);
            }

            responseBytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
        } catch (Exception e) {
            logger.error("WatcherCommandServer: error handling request: {}", e.getMessage(), e);
            responseBytes = ("{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, responseBytes.length);
        }

        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    /**
     * Simple GET /pid endpoint that returns the current process ID.
     * Used by the watcher to identify which process to kill.
     */
    private void handlePidRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, 0);
            exchange.close();
            return;
        }

        try {
            long pid = ProcessHandle.current().pid();
            String response = "{\"pid\":" + pid + "}";
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        } catch (Exception e) {
            logger.error("WatcherCommandServer: error handling PID request: {}", e.getMessage(), e);
            byte[] responseBytes = ("{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        }

        exchange.close();
    }
}
