package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Reads chat text with the PP-OCR models, in this process.
 *
 * <p>The same two networks the Python service runs, driven from Java instead. The service reads
 * well but it is a separate program: a Python install, a gigabyte of libraries, and a web server on
 * a port. None of that can be handed to somebody who just downloaded the application, so the
 * transcript they get is read by a fallback rather than by the reader every accuracy figure was
 * measured against. Running the models here closes that gap for the price of a jar and thirty
 * megabytes of weights.
 *
 * <p>Two networks, in sequence. The first is handed the whole feed and answers with a probability
 * map -- how likely each pixel is to be part of some text. That map is cut into boxes, and each box
 * is cropped out and handed to the second network, which answers with a character per step. The
 * networks are the easy part; everything around them is where reading goes wrong, and every
 * constant below was arrived at by comparing this reader's output against the service's on the same
 * saved frames.
 *
 * <p>Boxes here are upright rectangles rather than the rotated ones PP-OCR produces. Chat is drawn
 * in horizontal lines and never at an angle, so the rotation is a generality this does not need --
 * and skipping it removes the perspective transform, which is the one step that would have needed
 * an image library beyond what Java already has.
 */
public final class OnnxOcrReader implements ChatTextReader, AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession detector;
    private final Map<String, Recogniser> recognisers = new HashMap<>();
    private final Path modelDir;

    /** One recogniser: the network and the alphabet it was trained to emit. */
    private record Recogniser(OrtSession session, String[] alphabet) {
    }

    public OnnxOcrReader(Path modelDir) throws OrtException {
        this.modelDir = modelDir;
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        // Four, matching the service. Benchmarked there: past four the work is too small to divide
        // and more threads measured slower, not faster.
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(THREADS);
        this.detector = env.createSession(modelDir.resolve("det.onnx").toString(), options);
    }

    private static final int THREADS = 4;

    /** True when the models are on disk, so a caller can fall back rather than fail. */
    public static boolean isAvailable(Path modelDir) {
        return Files.isRegularFile(modelDir.resolve("det.onnx"))
                && Files.isRegularFile(modelDir.resolve("rec_latin.onnx"))
                && Files.isRegularFile(modelDir.resolve("rec_latin.dict"));
    }

    /**
     * Reads one region of a frame.
     *
     * @param language {@code "en"} for the Latin alphabet, {@code "ru"} for Cyrillic
     * @return one line per box that read as text, positioned against the whole frame
     */
    @Override
    public String name() {
        return "in-process ONNX";
    }

    @Override
    public boolean isUp() {
        return detector != null;
    }

    /**
     * Reads one region, reporting nothing rather than failing.
     *
     * <p>A reader that cannot read is a reader the caller falls back from -- the same contract the
     * service has when its port is closed. Letting the failure out instead would cost the whole
     * pass rather than one screen.
     */
    @Override
    public List<TextLine> read(BufferedImage frame, int left, int top, int right, int bottom,
                               String language, double minConfidence) {
        try {
            return readOrThrow(frame, left, top, right, bottom, language, minConfidence);
        } catch (OrtException | RuntimeException e) {
            return List.of();
        }
    }

    private List<TextLine> readOrThrow(BufferedImage frame, int left, int top, int right,
                                       int bottom, String language, double minConfidence)
            throws OrtException {
        BufferedImage region = frame.getSubimage(Math.max(0, left), Math.max(0, top),
                Math.min(frame.getWidth(), right) - left,
                Math.min(frame.getHeight(), bottom) - top);

        float[][] probability = probabilityMap(region);
        List<Box> boxes = boxesFrom(probability, region.getWidth(), region.getHeight());
        Recogniser recogniser = recogniserFor(language);

        // The foreign-script filter only makes sense for a model whose own alphabet is Latin.
        // Applied to the Korean or Arabic model it would throw away everything they read.
        String alphabet = ALPHABETS.getOrDefault(
                language == null ? "" : language.toLowerCase(java.util.Locale.ROOT), "latin");
        boolean latinModel = "latin".equals(alphabet) || "european".equals(alphabet);
        List<TextLine> lines = new ArrayList<>();
        for (Box box : boxes) {
            Reading reading = recognise(recogniser, region, box);
            if (reading == null || reading.text().isBlank()
                    || reading.confidence() < minConfidence) {
                continue;
            }
            String text = reading.text();
            // A model reads what it knows. Handed a glyph outside its alphabet, the Latin one
            // answers with the nearest Chinese character it has rather than declining, and that
            // lands in the middle of an English sentence.
            if (latinModel && hasForeignScript(text)) {
                continue;
            }
            // Nothing but symbols is not writing. The feed is full of small marks the reader will
            // name if asked -- the translate button beside every bubble comes back as an arrow, a
            // rank badge as a star -- and left in they land mid-sentence: this reader put one
            // through the middle of a wiki link and broke it in half.
            if (!hasLetterOrDigit(text)) {
                continue;
            }
            lines.add(new TextLine(text, box.left() + left, box.top() + top,
                    box.width(), box.height(), (float) reading.confidence()));
        }
        lines.sort(Comparator.comparingInt(TextLine::top));
        return lines;
    }

    // ---- detection -------------------------------------------------------------------------

    /**
     * Asks the first network how likely each pixel is to be text.
     *
     * <p>The image is resized so both sides are multiples of thirty-two, which is what the network
     * requires, and shifted by the mean and deviation it was trained on. Those three numbers are not
     * decoration: with the wrong ones the network still answers, confidently and wrongly, which is
     * the failure mode this whole pipeline is prone to.
     */
    private float[][] probabilityMap(BufferedImage region) throws OrtException {
        int w = Math.max(32, Math.round(region.getWidth() / 32f) * 32);
        int h = Math.max(32, Math.round(region.getHeight() / 32f) * 32);
        BufferedImage scaled = resize(region, w, h);

        float[] data = new float[3 * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = scaled.getRGB(x, y);
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                int at = y * w + x;
                data[at] = (r - MEAN[0]) / DEVIATION[0];
                data[h * w + at] = (g - MEAN[1]) / DEVIATION[1];
                data[2 * h * w + at] = (b - MEAN[2]) / DEVIATION[2];
            }
        }

        try (OnnxTensor input = OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(data), new long[] {1, 3, h, w});
             OrtSession.Result result = detector.run(
                     Map.of(detector.getInputNames().iterator().next(), input))) {
            float[][][][] out = (float[][][][]) result.get(0).getValue();
            return out[0][0];
        }
    }

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DEVIATION = {0.229f, 0.224f, 0.225f};

    /** An upright box on the region, and how sure the detector was about it. */
    private record Box(int left, int top, int width, int height, double score) {
        int right() {
            return left + width;
        }

        int bottom() {
            return top + height;
        }
    }

    /**
     * Cuts the probability map into boxes.
     *
     * <p>Everything above the threshold is text; connected runs of it are one piece of text. The
     * pieces are found by flooding outward from each unvisited pixel, which is enough because the
     * only question asked of a piece is where its edges are.
     */
    private List<Box> boxesFrom(float[][] probability, int regionWidth, int regionHeight) {
        int h = probability.length;
        int w = probability[0].length;
        boolean[][] seen = new boolean[h][w];
        List<Box> boxes = new ArrayList<>();
        int[] queue = new int[h * w];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (seen[y][x] || probability[y][x] <= TEXT_THRESHOLD) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queue[tail++] = y * w + x;
                seen[y][x] = true;
                int minX = x;
                int maxX = x;
                int minY = y;
                int maxY = y;
                double total = 0;
                int count = 0;

                while (head < tail) {
                    int at = queue[head++];
                    int cy = at / w;
                    int cx = at % w;
                    total += probability[cy][cx];
                    count++;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);
                    for (int[] step : NEIGHBOURS) {
                        int nx = cx + step[0];
                        int ny = cy + step[1];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || seen[ny][nx]
                                || probability[ny][nx] <= TEXT_THRESHOLD) {
                            continue;
                        }
                        seen[ny][nx] = true;
                        queue[tail++] = ny * w + nx;
                    }
                }

                double score = total / Math.max(1, count);
                if (score < BOX_SCORE_MIN || count < SMALLEST_PIECE) {
                    continue;
                }
                boxes.add(grow(minX, minY, maxX, maxY, count,
                        (double) regionWidth / w, (double) regionHeight / h,
                        regionWidth, regionHeight, score));
            }
        }
        return boxes;
    }

    /**
     * Pushes a box outward by a margin of constant thickness, and puts it back on the region's
     * scale.
     *
     * <p>The margin is the box's area over its perimeter, times a ratio -- not a proportion of its
     * size. Scaling a box outward from its centre instead is the obvious thing to write and it is
     * wrong: it grows a line of text as much along its length as across it, so a wide line gains
     * hundreds of pixels of width and swallows whatever sits beside it. Measured against the
     * service, that one mistake turned most of a screen into gibberish.
     */
    private static Box grow(int minX, int minY, int maxX, int maxY, int area,
                            double scaleX, double scaleY, int regionWidth, int regionHeight,
                            double score) {
        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        double perimeter = 2.0 * (boxWidth + boxHeight);
        double margin = perimeter <= 0 ? 0 : area * UNCLIP / perimeter;

        int left = (int) Math.round((minX - margin) * scaleX);
        int top = (int) Math.round((minY - margin) * scaleY);
        int right = (int) Math.round((maxX + margin) * scaleX);
        int bottom = (int) Math.round((maxY + margin) * scaleY);

        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(regionWidth - 1, right);
        bottom = Math.min(regionHeight - 1, bottom);
        return new Box(left, top, Math.max(1, right - left), Math.max(1, bottom - top), score);
    }

    private static final float TEXT_THRESHOLD = 0.3f;
    private static final double BOX_SCORE_MIN = 0.5;
    private static final double UNCLIP = 1.6;

    /** Fewer pixels than this is speckle in the probability map, not a letter. */
    private static final int SMALLEST_PIECE = 12;

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // ---- recognition -----------------------------------------------------------------------

    private record Reading(String text, double confidence) {
    }

    /**
     * Reads the text inside one box.
     *
     * <p>The crop is scaled to a fixed height and laid on a wider canvas rather than stretched to
     * fill one. Both halves of that matter: the network was trained on padded input and loses the
     * spaces between words without it, and squeezing a long line down to the nominal width costs it
     * letters outright -- "seria bueno que ahora" came back as "sera buen que ahra", each missing
     * character a stroke that fell between two of the network's output steps.
     */
    private Reading recognise(Recogniser recogniser, BufferedImage region, Box box)
            throws OrtException {
        int cropWidth = Math.min(box.width(), region.getWidth() - box.left());
        int cropHeight = Math.min(box.height(), region.getHeight() - box.top());
        if (cropWidth < 4 || cropHeight < 4) {
            return null;
        }
        BufferedImage crop = region.getSubimage(box.left(), box.top(), cropWidth, cropHeight);

        int scaledWidth = Math.max(8, Math.round(cropWidth * (float) REC_HEIGHT / cropHeight));
        int canvasWidth = Math.max(REC_WIDTH, (scaledWidth + 7) / 8 * 8);
        BufferedImage scaled = resize(crop, scaledWidth, REC_HEIGHT);

        float[] data = new float[3 * REC_HEIGHT * canvasWidth];
        int plane = REC_HEIGHT * canvasWidth;
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < scaledWidth; x++) {
                int rgb = scaled.getRGB(x, y);
                int at = y * canvasWidth + x;
                data[at] = (((rgb >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
                data[plane + at] = (((rgb >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
                data[2 * plane + at] = ((rgb & 0xFF) / 255f - 0.5f) / 0.5f;
            }
        }

        float[][] steps;
        try (OnnxTensor input = OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(data), new long[] {1, 3, REC_HEIGHT, canvasWidth});
             OrtSession.Result result = recogniser.session().run(
                     Map.of(recogniser.session().getInputNames().iterator().next(), input))) {
            steps = ((float[][][]) result.get(0).getValue())[0];
        }
        return decode(steps, recogniser.alphabet());
    }

    private static final int REC_HEIGHT = 48;
    private static final int REC_WIDTH = 320;

    /**
     * Turns the network's per-step answers into a string.
     *
     * <p>The network emits one answer per slice of the image, so a wide letter spans several slices
     * and repeats. Repeats collapse to one character and the blank class separates letters that
     * genuinely do repeat -- which is why "ll" survives while a stretched "l" does not become "ll".
     */
    private static Reading decode(float[][] steps, String[] alphabet) {
        StringBuilder text = new StringBuilder();
        double total = 0;
        int kept = 0;
        int previous = -1;
        for (float[] step : steps) {
            int best = 0;
            float bestValue = step[0];
            for (int i = 1; i < step.length; i++) {
                if (step[i] > bestValue) {
                    bestValue = step[i];
                    best = i;
                }
            }
            if (best != previous && best != 0 && best < alphabet.length) {
                text.append(alphabet[best]);
                total += bestValue;
                kept++;
            }
            previous = best;
        }
        return new Reading(text.toString(), kept == 0 ? 0 : total / kept);
    }

    /**
     * The recogniser for a language, built on first use.
     *
     * <p>Each costs a few seconds to construct and holds its weights in memory, so they are made
     * only when something actually asks for that alphabet. A language with no model of its own
     * falls back to the Latin one, which is what the service does.
     */
    private Recogniser recogniserFor(String language) throws OrtException {
        String key = ALPHABETS.getOrDefault(
                language == null ? "" : language.toLowerCase(java.util.Locale.ROOT), "latin");
        Recogniser known = recognisers.get(key);
        if (known != null) {
            return known;
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(THREADS);
        OrtSession session = env.createSession(
                modelDir.resolve("rec_" + key + ".onnx").toString(), options);
        Recogniser built = new Recogniser(session, alphabet(modelDir.resolve("rec_" + key + ".dict")));
        recognisers.put(key, built);
        return built;
    }

    /**
     * Which alphabet each language is read in.
     *
     * <p>Most of the alliance writes in one of a handful of scripts, and one model covers a whole
     * script rather than a language: Spanish, Portuguese, Turkish and Polish are all the European
     * model, Russian and Ukrainian are both the Cyrillic one. Anything not named here falls to the
     * Latin model, which is also the one that carries Chinese.
     *
     * <p>Naming them matters more than it looks. A recogniser handed a script it does not know does
     * not decline -- it answers in the nearest shapes it has, so Korean read by a Latin model comes
     * back as nothing at all while reporting itself perfectly loaded.
     */
    private static final Map<String, String> ALPHABETS = Map.ofEntries(
            Map.entry("en", "latin"), Map.entry("zh", "latin"), Map.entry("ch", "latin"),
            Map.entry("es", "european"), Map.entry("pt", "european"),
            Map.entry("fr", "european"), Map.entry("de", "european"),
            Map.entry("it", "european"), Map.entry("pl", "european"),
            Map.entry("tr", "european"), Map.entry("id", "european"),
            Map.entry("vi", "european"), Map.entry("latin", "european"),
            Map.entry("ru", "cyrillic"), Map.entry("uk", "cyrillic"),
            Map.entry("cyrillic", "cyrillic"),
            Map.entry("ko", "korean"), Map.entry("korean", "korean"),
            Map.entry("ja", "japanese"), Map.entry("japan", "japanese"),
            Map.entry("ar", "arabic"), Map.entry("arabic", "arabic"),
            Map.entry("th", "thai"), Map.entry("thai", "thai"),
            Map.entry("el", "greek"), Map.entry("greek", "greek"),
            Map.entry("cht", "chinese_cht"), Map.entry("chinese_cht", "chinese_cht"));

    /**
     * The alphabet a recogniser emits, in the order its output classes are numbered.
     *
     * <p>Class zero is the blank that separates letters, the dictionary fills the classes after it,
     * and one more sits on the end for the space. Getting this order wrong does not fail -- it
     * returns fluent nonsense in the right shape, which is the hardest kind of wrong to notice.
     */
    private static String[] alphabet(Path dictionary) {
        try {
            List<String> characters = Files.readAllLines(dictionary,
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] alphabet = new String[characters.size() + 2];
            alphabet[0] = "";
            for (int i = 0; i < characters.size(); i++) {
                alphabet[i + 1] = characters.get(i);
            }
            alphabet[alphabet.length - 1] = " ";
            return alphabet;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + dictionary, e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

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


    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    @Override
    public void close() {
        for (Recogniser r : recognisers.values()) {
            try {
                r.session().close();
            } catch (OrtException e) {
                // Closing a session that is already gone is not worth reporting.
            }
        }
        try {
            detector.close();
        } catch (OrtException e) {
            // As above.
        }
    }
}
