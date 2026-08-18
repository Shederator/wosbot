package dev.frostguard.app.panel.misc;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class GiftCodeRedeemer {

    private static final URI REDEEM_URI = URI.create(
            "https://wos-giftcode-api.centurygame.com/api/gift_code");
    private static final String SIGNING_SECRET = "tB87#kPtkxqOS2";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    GiftCodeRedeemer() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
    }

    GiftCodeRedeemer(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    RedeemResult redeem(String playerId, String region, String giftCode) {
        if (!isDigits(playerId) || !isDigits(region) || giftCode == null || giftCode.isBlank()) {
            return RedeemResult.failed("Invalid player ID, region, or gift code");
        }

        try {
            JsonNode response = post(signed(requestFields(
                    playerId, region, giftCode, Instant.now().getEpochSecond())));
            return classify(response.path("msg").asText("Unknown redemption response"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeemResult.retryable("Redemption interrupted");
        } catch (Exception exception) {
            return RedeemResult.retryable(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    Map<String, String> requestFields(String playerId, String region, String giftCode, long epochSeconds) {
        return Map.of(
                "fid", playerId,
                "cdk", giftCode,
                "kid", region,
                "time", String.valueOf(epochSeconds));
    }

    Map<String, String> signed(Map<String, String> fields) {
        Map<String, String> sorted = new LinkedHashMap<>();
        fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        String payload = sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("sign", md5(payload + SIGNING_SECRET));
        signed.putAll(fields);
        return signed;
    }

    static RedeemResult classify(String rawMessage) {
        String message = rawMessage == null || rawMessage.isBlank() ? "Unknown response" : rawMessage.trim();
        String normalized = message.toUpperCase().replace('_', ' ');
        if (normalized.startsWith("SUCCESS")) {
            return new RedeemResult(message, RedeemOutcome.REDEEMED, true);
        }
        if (normalized.startsWith("RECEIVED") || normalized.contains("SAME TYPE EXCHANGE")
                || normalized.contains("ALREADY")) {
            return new RedeemResult(message, RedeemOutcome.ALREADY_REDEEMED, true);
        }
        if (normalized.contains("EXPIRED") || normalized.contains("NOT FOUND")
                || normalized.contains("LIMIT") || normalized.contains("REQUIREMENT")
                || normalized.contains("SPEND MORE") || normalized.contains("RECHARGE MONEY")
                || normalized.contains("TIME ERROR")
                || normalized.contains("ROLE NOT EXIST") || normalized.contains("PLAYER NOT EXIST")) {
            return RedeemResult.failed(message);
        }
        return RedeemResult.retryable(message);
    }

    private JsonNode post(Map<String, String> fields) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(REDEEM_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://wos-giftcode.centurygame.com")
                .header("Referer", "https://wos-giftcode.centurygame.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 Chrome/134 Safari/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(fields)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("gift_code returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String formBody(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 is unavailable", impossible);
        }
    }

    private boolean isDigits(String value) {
        return value != null && value.matches("\\d+");
    }

    enum RedeemOutcome {
        REDEEMED,
        ALREADY_REDEEMED,
        FAILED,
        RETRYABLE_ERROR
    }

    record RedeemResult(String message, RedeemOutcome outcome, boolean terminal) {
        static RedeemResult failed(String message) {
            return new RedeemResult(message, RedeemOutcome.FAILED, true);
        }

        static RedeemResult retryable(String message) {
            return new RedeemResult(message, RedeemOutcome.RETRYABLE_ERROR, false);
        }
    }
}
