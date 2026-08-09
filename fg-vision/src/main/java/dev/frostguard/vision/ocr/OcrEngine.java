package dev.frostguard.vision.ocr;

import dev.frostguard.api.configs.OcrEngineType;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;

public final class OcrEngine {
    private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);

    private static OcrEngineType activeType = OcrEngineType.TESSERACT;
    private static OcrProvider tesseractProvider;
    private static OcrProvider paddleProvider;

    private OcrEngine() {}

    public static void setActiveEngine(OcrEngineType type) {
        log.info("Switching OCR Engine to: {}", type);
        activeType = type;
    }

    private static synchronized OcrProvider getProvider() {
        if (activeType == OcrEngineType.PADDLE_ONNX) {
            if (paddleProvider == null) {
                paddleProvider = new PaddleOcrProvider();
            }
            return paddleProvider;
        } else {
            if (tesseractProvider == null) {
                tesseractProvider = new TesseractOcrProvider();
            }
            return tesseractProvider;
        }
    }

    public static String recognizeText(RawImageData capture, PointData c1, PointData c2, String lang) throws net.sourceforge.tess4j.TesseractException {
        try {
            return getProvider().recognizeText(capture, c1, c2, lang);
        } catch (Exception e) {
            throw new net.sourceforge.tess4j.TesseractException(e);
        }
    }

    public static String recognizeText(RawImageData capture, PointData c1, PointData c2, TesseractSettingsData cfg) throws net.sourceforge.tess4j.TesseractException {
        try {
            return getProvider().recognizeText(capture, c1, c2, cfg);
        } catch (Exception e) {
            throw new net.sourceforge.tess4j.TesseractException(e);
        }
    }

    public static String readFromFile(File file, int x, int y, int w, int h, String lang) throws Exception {
        return getProvider().readFromFile(file, x, y, w, h, lang);
    }

    public static BufferedImage toBufferedImage(RawImageData capture) {
        return getProvider().toBufferedImage(capture);
    }
}
