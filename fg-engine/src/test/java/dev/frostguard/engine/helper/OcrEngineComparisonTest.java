package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.OcrEngineType;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.vision.ocr.OcrEngine;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

public class OcrEngineComparisonTest {

    @Test
    public void compareOcrEngines() throws Exception {
        List<String> images = Arrays.asList(
                "/deployment/polar-after-equalize-20260709.png",
                "/research/battle-progress-badges-20260718.png",
                "/research/battle-progress-badges-5-20260718.png",
                "/research/battle-progress-badges-6-20260720.png",
                "/research/help-completed-20260716.png",
                "/research/help-short-20260716.png",
                "/research/resource-confirm-20260717.png",
                "/research/resource-replenish-20260717.png",
                "/research/resource-shortfall-20260717.png",
                "/research/speedup-running-20260716.png"
        );

        File outputFile = new File("target/ocr-benchmark.csv");
        outputFile.getParentFile().mkdirs();

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("Filename,Engine,TimeMs,Output");

            for (String imagePath : images) {
                BufferedImage image = ImageIO.read(getClass().getResourceAsStream(imagePath));
                if (image == null) continue;
                
                RawImageData rawData = rgbaFrame(image);

                for (OcrEngineType engine : Arrays.asList(OcrEngineType.TESSERACT, OcrEngineType.PADDLE_ONNX)) {
                    OcrEngine.setActiveEngine(engine);

                    long start = System.currentTimeMillis();
                    String result = "";
                    try {
                        // Assuming full image OCR for comparison (or a generic crop)
                        result = OcrEngine.recognizeText(
                                rawData,
                                new PointData(0, 0),
                                new PointData(image.getWidth(), image.getHeight()),
                                TesseractSettingsData.forTextBlock()
                        );
                    } catch (Exception e) {
                        result = "ERROR: " + e.getMessage();
                    }
                    long elapsed = System.currentTimeMillis() - start;

                    writer.printf("%s,%s,%d,\"%s\"%n", 
                        new File(imagePath).getName(), 
                        engine.name(), 
                        elapsed, 
                        result.replace("\"", "\"\"").replace("\n", "\\n")
                    );
                }
            }
        }
        System.out.println("Benchmark report generated at: " + outputFile.getAbsolutePath());
    }

    private static RawImageData rgbaFrame(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            rgba[i * 4] = (byte) ((argb >> 16) & 0xFF);     // R
            rgba[i * 4 + 1] = (byte) ((argb >> 8) & 0xFF);  // G
            rgba[i * 4 + 2] = (byte) (argb & 0xFF);         // B
            rgba[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF); // A
        }
        return RawImageData.capture(rgba, w, h, 4);
    }
}
