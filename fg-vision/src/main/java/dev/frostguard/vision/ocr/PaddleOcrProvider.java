package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;

public class PaddleOcrProvider implements OcrProvider {
    private static final Logger log = LoggerFactory.getLogger(PaddleOcrProvider.class);

    private final io.github.mymonstercat.ocr.InferenceEngine engine;

    public PaddleOcrProvider() {
        log.info("Initializing PaddleOCR ONNX Runtime provider...");
        // Use the bundled ONNX PPOCR V4 (or V3 if V4 isn't in this version, it falls back or we use ONNX_PPOCR_V3)
        // rapidocr 0.0.7 should have ONNX_PPOCR_V3 or V4
        this.engine = io.github.mymonstercat.ocr.InferenceEngine.getInstance(io.github.mymonstercat.Model.ONNX_PPOCR_V3);
    }

    @Override
    public String recognizeText(RawImageData capture, PointData c1, PointData c2, String lang) throws Exception {
        return recognizeText(capture, c1, c2, TesseractSettingsData.builder().build());
    }

    @Override
    public String recognizeText(RawImageData capture, PointData c1, PointData c2, TesseractSettingsData cfg) throws Exception {
        BufferedImage full = toBufferedImage(capture);
        int x = (int) Math.min(c1.getX(), c2.getX());
        int y = (int) Math.min(c1.getY(), c2.getY());
        int w = (int) Math.abs(c1.getX() - c2.getX());
        int h = (int) Math.abs(c1.getY() - c2.getY());
        
        BufferedImage sub = full.getSubimage(x, y, w, h);
        
        io.github.mymonstercat.ocr.config.ParamConfig paramConfig = io.github.mymonstercat.ocr.config.ParamConfig.getDefaultConfig();
        paramConfig.setDoAngle(false); // fast text read
        
        File tempFile = File.createTempFile("paddleocr_", ".png");
        try {
            javax.imageio.ImageIO.write(sub, "png", tempFile);
            com.benjaminwan.ocrlibrary.OcrResult ocrResult = engine.runOcr(tempFile.getAbsolutePath(), paramConfig);
            if (ocrResult == null || ocrResult.getStrRes() == null) {
                return "";
            }
            return ocrResult.getStrRes().trim();
        } finally {
            tempFile.delete();
        }
    }

    @Override
    public String readFromFile(File file, int x, int y, int w, int h, String lang) throws Exception {
        BufferedImage full = javax.imageio.ImageIO.read(file);
        BufferedImage sub = full.getSubimage(x, y, w, h);
        
        io.github.mymonstercat.ocr.config.ParamConfig paramConfig = io.github.mymonstercat.ocr.config.ParamConfig.getDefaultConfig();
        paramConfig.setDoAngle(false);
        
        File tempFile = File.createTempFile("paddleocr_read_", ".png");
        try {
            javax.imageio.ImageIO.write(sub, "png", tempFile);
            com.benjaminwan.ocrlibrary.OcrResult ocrResult = engine.runOcr(tempFile.getAbsolutePath(), paramConfig);
            if (ocrResult == null || ocrResult.getStrRes() == null) {
                return "";
            }
            return ocrResult.getStrRes().trim();
        } finally {
            tempFile.delete();
        }
    }

    @Override
    public BufferedImage toBufferedImage(RawImageData capture) {
        // We can reuse the Tesseract logic to just convert the image for now, as it's standard java.awt
        return new TesseractOcrProvider().toBufferedImage(capture);
    }
}
