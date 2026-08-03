package dev.frostguard.app.panel.misc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class GiftCodeClient {

    static final String SOURCE_URL = "https://gift-code-api.whiteout-bot.com/giftcode_api.php";
    private static final String API_KEY = "super_secret_bot_token_nobody_will_ever_find";
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    GiftCodeClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
    }

    GiftCodeClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    List<GiftCodeEntry> fetchActiveCodes() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(SOURCE_URL))
                .timeout(Duration.ofSeconds(15))
                .header("X-API-Key", API_KEY)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Gift code source returned HTTP " + response.statusCode());
        }
        return parseResponse(response.body());
    }

    List<GiftCodeEntry> parseResponse(String json) throws IOException {
        JsonNode codes = objectMapper.readTree(json).path("codes");
        if (!codes.isArray()) {
            throw new IOException("Gift code source response has no codes array");
        }

        Map<String, GiftCodeEntry> unique = new LinkedHashMap<>();
        for (JsonNode item : codes) {
            GiftCodeEntry entry = parseEntry(item.asText(""));
            if (entry != null) {
                unique.putIfAbsent(entry.code(), entry);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private GiftCodeEntry parseEntry(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int split = value.lastIndexOf(' ');
        if (split < 1) {
            return new GiftCodeEntry(value, null);
        }
        String code = value.substring(0, split).trim();
        String rawDate = value.substring(split + 1).trim();
        try {
            return new GiftCodeEntry(code, LocalDate.parse(rawDate, SOURCE_DATE));
        } catch (DateTimeParseException ignored) {
            return new GiftCodeEntry(value, null);
        }
    }

    record GiftCodeEntry(String code, LocalDate discoveredOn) {
        String displayDate() {
            return discoveredOn == null ? "Discovery date unknown" : "Discovered " + SOURCE_DATE.format(discoveredOn);
        }
    }
}
