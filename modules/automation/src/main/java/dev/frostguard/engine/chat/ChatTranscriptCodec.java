package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One message to and from a single JSONL line.
 *
 * <p>Reading is deliberately forgiving: a line that will not parse returns {@code null} and the
 * caller skips it. The transcript is append-only across restarts and power cuts, so a truncated
 * final line is a normal thing to meet, and it must cost that one message rather than the day.
 */
public final class ChatTranscriptCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatTranscriptCodec() {
    }

    public static String toJson(ChatMessage m) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("at", m.capturedAt().toString());
        n.put("channel", m.channel());
        n.put("author", m.author());
        if (!m.allianceTag().isEmpty()) {
            n.put("tag", m.allianceTag());
        }
        if (m.vipLevel() > 0) {
            n.put("vip", m.vipLevel());
        }
        n.put("body", m.body());
        if (m.translated() != null && !m.translated().isBlank()) {
            n.put("en", m.translated());
        }
        if (!m.mentions().isEmpty()) {
            ArrayNode arr = n.putArray("mentions");
            m.mentions().forEach(arr::add);
        }
        if (m.hasQuote()) {
            n.put("quoted", m.quoted());
        }
        n.put("kind", m.kind().name());
        return n.toString();
    }

    public static ChatMessage fromJson(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(line);
            List<String> mentions = new ArrayList<>();
            if (n.has("mentions")) {
                n.get("mentions").forEach(x -> mentions.add(x.asText()));
            }
            return new ChatMessage(
                    Instant.parse(n.path("at").asText()),
                    n.path("channel").asText(""),
                    n.path("author").asText(""),
                    n.path("tag").asText(""),
                    n.path("vip").asInt(0),
                    n.path("body").asText(""),
                    n.path("en").asText(""),
                    mentions,
                    kindOf(n.path("kind").asText("TEXT")),
                    n.path("quoted").asText(""));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static ChatMessage.Kind kindOf(String raw) {
        try {
            return ChatMessage.Kind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ChatMessage.Kind.TEXT;
        }
    }
}
