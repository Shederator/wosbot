package dev.frostguard.vision.ocr;

import java.awt.image.BufferedImage;
import java.util.List;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.convert.ImagePreprocessor;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/** One thread-confined native engine for a bounded sequence of fixed-preset reads. */
public final class TesseractOcrSession implements AutoCloseable {
    interface Engine extends AutoCloseable {
        default void prepare() { }
        String read(BufferedImage image) throws TesseractException;
        default String read(BufferedImage image, OcrSettingsData.TextLayout layout) throws TesseractException { return read(image); }
        @Override void close();
    }

    private final Thread owner = Thread.currentThread();
    private final OcrSettingsData settings;
    private final Engine engine;
    private boolean closed;

    public TesseractOcrSession(OcrSettingsData settings) {
        this(settings, new RetainedEngine(settings));
    }

    TesseractOcrSession(OcrSettingsData settings, Engine engine) {
        this.settings = java.util.Objects.requireNonNull(settings);
        this.engine = java.util.Objects.requireNonNull(engine);
    }

    public String recognize(RawImageData frame, AreaData region) throws OcrException {
        return recognize(frame, region, settings.isolateForeground(), settings.targetColor(), settings.textLayout());
    }

    /** Loads the retained models on their owning thread before time-sensitive observations begin. */
    public void prepare() {
        requireOwner();
        if (closed) throw new IllegalStateException("OCR session closed");
        engine.prepare();
    }

    /** Per-region preprocessing only; segmentation, language and the retained engine stay unchanged. */
    public String recognizeForeground(RawImageData frame, AreaData region, java.awt.Color foreground) throws OcrException {
        return recognizeForeground(frame, region, foreground, settings.textLayout());
    }

    /** Changes region segmentation without loading another model or changing the glyph whitelist. */
    public String recognizeForeground(RawImageData frame, AreaData region, java.awt.Color foreground,
            OcrSettingsData.TextLayout layout) throws OcrException {
        return recognize(frame, region, true, java.util.Objects.requireNonNull(foreground), layout);
    }

    private String recognize(RawImageData frame, AreaData region, boolean isolate, java.awt.Color foreground,
            OcrSettingsData.TextLayout layout) throws OcrException {
        requireOwner();
        if (closed) throw new IllegalStateException("OCR session closed");
        int x = region.topLeft().getX(), y = region.topLeft().getY();
        int width = region.bottomRight().getX() - x, height = region.bottomRight().getY() - y;
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > frame.getWidth()
                || y + height > frame.getHeight()) throw new IllegalArgumentException("Invalid OCR region");
        BufferedImage prepared = ImagePreprocessor.prepareForOcr(frame, x, y, width, height,
                isolate, foreground);
        try {
            return engine.read(prepared, layout).replace("\r", "").trim();
        } catch (TesseractException error) {
            throw new OcrException("Session OCR failed", error);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("OCR session is thread-confined");
    }

    @Override
    public void close() {
        requireOwner();
        if (!closed) {
            closed = true;
            engine.close();
        }
    }

    private static final class RetainedEngine extends Tesseract implements Engine {
        private boolean initialized, released;
        private final OcrSettingsData.TextLayout defaultLayout;

        RetainedEngine(OcrSettingsData settings) {
            defaultLayout = settings.textLayout();
            setDatapath(TesseractOcrProvider.locateTessdata());
            setLanguage("eng");
            setConfigs(List.of("quiet"));
            setOcrEngineMode(1);
            setPageSegMode(TesseractOcrProvider.mapTextLayout(settings.textLayout() == null
                    ? OcrSettingsData.TextLayout.AUTO : settings.textLayout()));
            if (settings.hasAllowedChars()) setVariable("tessedit_char_whitelist", settings.getAllowedChars());
        }

        @Override
        protected void init() {
            if (released) throw new IllegalStateException("Native OCR engine released");
            if (!initialized) {
                super.init();
                initialized = true;
            }
        }

        @Override
        protected void dispose() {
            // Tess4J disposes after every doOCR; clear per-image state while keeping models loaded.
            if (getAPI() != null && getHandle() != null) getAPI().TessBaseAPIClear(getHandle());
        }

        @Override
        public void prepare() { init(); }

        @Override
        public String read(BufferedImage image) throws TesseractException { return read(image, defaultLayout); }

        @Override
        public String read(BufferedImage image, OcrSettingsData.TextLayout layout) throws TesseractException {
            init();
            getAPI().TessBaseAPISetPageSegMode(getHandle(), TesseractOcrProvider.mapTextLayout(
                    layout == null ? OcrSettingsData.TextLayout.AUTO : layout));
            return doOCR(image);
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                super.dispose();
            }
        }
    }
}
