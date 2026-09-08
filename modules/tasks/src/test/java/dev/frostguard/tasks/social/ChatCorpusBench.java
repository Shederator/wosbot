package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.PaddleOcrClient;
import dev.frostguard.vision.ocr.TextLine;

/**
 * Walks a directory of saved screens through {@link ChatPass}, exactly as the live task does.
 *
 * <p>The point of this bench is that it contains no pipeline of its own. It reads frames off disk
 * instead of an emulator and prints the result instead of storing it, and everything between those
 * two ends is the shipping code. The previous bench had its own copy of the walk, the copies
 * drifted, and a change measured at 90% clean scored 72% the first time it met production -- not
 * because the measurement was careless but because it was measuring different code.
 *
 * <p>Reads through the OCR service when it is up, and the built-in reader when it is not, which is
 * also what production decides and on the same evidence.
 */
public final class ChatCorpusBench {

    // Taken from ChatCaptureRoutine, not chosen here. These had drifted to 250/1160 while the
    // routine read 175/1150, which is the same class of fault the bench exists to prevent: a
    // measurement of code that is not the code that ships.
    private static final int FEED_TOP = 175;
    private static final int FEED_BOTTOM = 1150;
    private static final int TEXT_COLUMN_LEFT = 132;
    private static final int TEXT_COLUMN_RIGHT = 700;
    private static final double MIN_CONFIDENCE = 0.60;

    private ChatCorpusBench() {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        boolean json = args.length > 1 && args[1].equals("json");
        File[] frames = dir.listFiles(f -> f.getName().matches("f[0-9]+[.]png"));
        if (frames == null || frames.length == 0) {
            System.out.println("no frames in " + dir);
            return;
        }
        java.util.Arrays.sort(frames, java.util.Comparator.comparing(File::getName));

        OcrSettingsData latin = settings("eng+spa+por+tur");
        OcrSettingsData cjk = settings("chi_sim+jpn+kor");
        OcrSettingsData cyrillic = settings("rus");

        // Which reader to measure, so the two can be run over the same frames and diffed. The
        // service is still the default; nothing changes unless it is asked for.
        dev.frostguard.vision.ocr.ChatTextReader service;
        if ("java".equalsIgnoreCase(System.getProperty("ocr.reader", ""))) {
            service = new dev.frostguard.vision.ocr.OnnxOcrReader(
                    java.nio.file.Path.of("C:/Bearguard/tools/ocr/onnx"));
        } else {
            service = new PaddleOcrClient("127.0.0.1", 6975);
        }
        boolean serviceUp = service.isUp();
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        out.println("reader: " + (serviceUp ? service.name() : "built-in"));

        // The real translator, not a stub. Stubbing it made every message on the review page look
        // untranslated, which is not what production stores and sent matt looking for a bug that
        // was in the bench.
        dev.frostguard.engine.chat.ChatTranslator translator =
                new dev.frostguard.engine.chat.ChatTranslator(true, 512);
        ChatPass pass = new ChatPass("alliance", translator::toEnglish,
                latin, cjk, cyrillic, TEXT_COLUMN_RIGHT);

        // Which screen each message was first seen on, so a review page can show a message beside
        // the picture it actually came from. Guessing that mapping by index, which an earlier page
        // did, produces a page that cannot be reasoned about.
        List<String> firstSeen = new ArrayList<>();
        int known = 0;

        for (File f : frames) {
            BufferedImage img = ImageIO.read(f);
            RawImageData raw = toRaw(img);
            List<TextLine> lines = serviceUp
                    ? service.read(img, TEXT_COLUMN_LEFT, FEED_TOP, TEXT_COLUMN_RIGHT, FEED_BOTTOM,
                            System.getProperty("ocr.lang", "en"), MIN_CONFIDENCE)
                    : List.of();
            boolean fromService = !lines.isEmpty();
            if (lines.isEmpty()) {
                lines = OcrEngine.recognizeLines(raw, new PointData(TEXT_COLUMN_LEFT, FEED_TOP),
                        new PointData(TEXT_COLUMN_RIGHT, FEED_BOTTOM), latin);
            }
            if (System.getenv("QROWS") != null) {
                for (TextLine l : lines) {
                    out.printf("   %s y=%4d..%4d x=%3d..%3d | %s%n",
                            ChatQuoteBar.isQuoteRow(img, l) ? "[Q]" : "   ",
                            l.top(), l.bottom(), l.left(), l.right(), l.text());
                }
            }
            pass.useRereader(fromService ? (l, t, r, b, language) ->
                    service.read(img, l, t, r, b, language, MIN_CONFIDENCE) : null);
            ChatPass.Screen screen = pass.addScreen(raw, img, lines, fromService);
            for (int i = known; i < known + screen.fresh(); i++) {
                firstSeen.add(f.getName().replace(".png", ""));
            }
            known += screen.fresh();
            out.printf("  %s: %d line(s), %d readable, %d new%n", f.getName(),
                    screen.lines(), screen.readable(), screen.fresh());
        }

        List<ChatMessage> messages = pass.messages();
        out.println();
        int n = 0;
        for (ChatMessage m : messages) {
            n++;
            if (json) {
                ObjectNode o = new ObjectMapper().createObjectNode();
                o.put("n", n);
                o.put("frame", n <= firstSeen.size() ? firstSeen.get(n - 1) : "");
                o.put("author", m.author());
                o.put("tag", m.allianceTag());
                o.put("body", m.body());
                o.put("en", m.translated());
                o.put("quoted", m.quoted());
                o.put("kind", m.kind().name());
                // The flag the panel filters on, so a review page shows the default view rather
                // than a rawer one the app never draws.
                o.put("chatter", dev.frostguard.api.chat.ChatLineCleaner.isNonSpeech(m.body()));
                out.println(o.toString());
            } else {
                out.printf("%3d | %-20s | %s%n", n,
                        m.allianceTag().isEmpty() ? m.author() : "[" + m.allianceTag() + "]"
                                + m.author(), m.body());
                if (!m.quoted().isBlank()) {
                    out.printf("    | %-20s | replying to: %s%n", "", m.quoted());
                }
            }
        }
        if (!json) {
            out.println("total messages: " + n);
        }
    }

    private static OcrSettingsData settings(String language) {
        return OcrSettingsData.assembler()
                .textLayout(TextLayout.TEXT_BLOCK)
                .stripBackground(false)
                .language(language)
                .build();
    }

    /** Packs an image the way the emulator hands one over, so the bench walks the real code path. */
    static RawImageData toRaw(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] px = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                px[i++] = (byte) ((rgb >> 16) & 0xFF);
                px[i++] = (byte) ((rgb >> 8) & 0xFF);
                px[i++] = (byte) (rgb & 0xFF);
                px[i++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(px, w, h, 32);
    }
}
