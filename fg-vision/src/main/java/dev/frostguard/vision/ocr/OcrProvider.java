package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.TesseractSettingsData;

import java.awt.image.BufferedImage;
import java.io.File;

public interface OcrProvider {

    String recognizeText(RawImageData capture, PointData c1, PointData c2, String lang) throws Exception;

    String recognizeText(RawImageData capture, PointData c1, PointData c2, TesseractSettingsData cfg) throws Exception;

    String readFromFile(File file, int x, int y, int w, int h, String lang) throws Exception;

    BufferedImage toBufferedImage(RawImageData capture);
}
