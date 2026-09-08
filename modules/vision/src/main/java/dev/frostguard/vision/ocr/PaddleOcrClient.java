package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads a screen through the local PaddleOCR service, and says so plainly when it cannot.
 *
 * <p>Tesseract does page segmentation -- guess a layout, then read it -- which is the wrong shape
 * for a chat feed. Bubbles sit at arbitrary positions, carry ornaments and emoji that are not text,
 * and mix alphabets on one screen. Measured over twenty live alliance screens it read "VIPS" for
 * "VIP5" and "jdes na druhy ?cet" for "jdes na druhy ucet", made letters out of bubble decoration,
 * and lost one message entirely.
 *
 * <p>PaddleOCR finds the text regions with a model and reads each one after. On the same twenty
 * screens the diacritics came back right, every quoted-reply strip was found, and the decoration it
 * could not read scored 0.00 to 0.18 against 0.94 to 1.00 for real writing -- so the junk sorts
 * itself out on confidence, where the previous reader needed rules about glyph widths and colours
 * to guess at the same thing.
 *
 * <p>It is a separate process because the models take seconds to load and have to be loaded once.
 * That makes it a dependency that can be down, so every call here is allowed to fail: the caller is
 * expected to fall back to Tesseract rather than lose the pass. Nothing leaves the machine -- the
 * service listens on loopback and holds the models locally.
 */
public final class PaddleOcrClient implements ChatTextReader {

    @Override
    public String name() {
        return "OCR service";
    }


    /** Long enough for a full screen on a busy machine, short enough not to stall a pass. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Readers whose output should be Latin script and nothing else. */
    private static final java.util.Set<String> LATIN_MODELS =
            java.util.Set.of("en", "es", "pt", "fr", "de", "it", "pl", "id", "tr");

    /**
     * Whether a reading contains letters from a script the reader was not asked to read.
     *
     * <p>Accented Latin does not count -- "děkuji" and "decoração" are exactly what this is meant
     * to preserve. Only a genuinely different alphabet does.
     */
    /** Whether a reading contains anything that could be a word rather than a mark. */
    private static boolean hasLetterOrDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasForeignScript(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c > 127
                    && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN) {
                return true;
            }
        }
        return false;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String base;

    public PaddleOcrClient(String host, int port) {
        this.base = "http://" + host + ":" + port;
    }

    /** Whether the service is up. Cheap, and the only safe thing to ask before a pass. */
    @Override
    public boolean isUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The text on one region of a frame, or an empty list if the service could not answer.
     *
     * <p>Empty rather than an exception, because a reader being unavailable is an ordinary thing
     * that should cost the caller a fallback and not the whole capture.
     *
     * @param minConfidence rows the reader was less sure of than this are dropped. On this feed
     *                      real writing scores 0.94 and up and decoration scores under 0.2, so the
     *                      threshold has a wide gap to sit in.
     */
    @Override
    public List<TextLine> read(BufferedImage frame, int left, int top, int right, int bottom,
                               String lang, double minConfidence) {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(frame, "png", png);
            String url = base + "/ocr?lang=" + lang
                    + "&left=" + left + "&top=" + top + "&right=" + right + "&bottom=" + bottom;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(png.toByteArray()))
                    .build();
            HttpResponse<byte[]> response = http.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode body = MAPPER.readTree(response.body());
            boolean latinModel = LATIN_MODELS.contains(lang);
            List<TextLine> lines = new ArrayList<>();
            for (JsonNode n : body.path("lines")) {
                double confidence = n.path("conf").asDouble();
                if (confidence < minConfidence) {
                    continue;
                }
                String text = n.path("text").asText();
                // A Latin reader that returns Chinese has not read Chinese. It was shown a picture
                // -- an emoji, an icon, the corner of a bubble -- and offered the closest glyph it
                // knows. Live, that put "国国" into the middle of a Spanish sentence, which then
                // went to the translator in that state. Whatever script was asked for is the only
                // script the answer can be in.
                if (latinModel && hasForeignScript(text)) {
                    continue;
                }
                // Nothing but symbols is not writing. The feed is full of small marks the reader
                // will name if asked -- the translate button beside every bubble comes back as an
                // arrow, a rank badge as a star -- and left in they land in the middle of a
                // sentence: "pro nej je tady vzdycky <- misto".
                if (!hasLetterOrDigit(text)) {
                    continue;
                }
                lines.add(new TextLine(text,
                        n.path("left").asInt(), n.path("top").asInt(),
                        n.path("width").asInt(), n.path("height").asInt(),
                        (float) (confidence * 100)));
            }
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }
}
