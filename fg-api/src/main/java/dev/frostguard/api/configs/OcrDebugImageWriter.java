package dev.frostguard.api.configs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Shared OCR debug image writer used by OCR and task modules.
 */
public final class OcrDebugImageWriter {

	private OcrDebugImageWriter() {
	}

	public static void saveDebugImage(BufferedImage image, String prefix, long eventTimestamp, int attemptNumber) {
		if (!OcrDebugSettings.isEnabled() || image == null) {
			return;
		}

		int slot = Math.max(1, Math.min(5, attemptNumber));
		String safePrefix = sanitisePrefix(prefix);
		Path outputDir = OcrDebugSettings.resolveOutputDirectory();

		try {
			Files.createDirectories(outputDir);
			Path outputFile = outputDir.resolve(eventTimestamp + "_" + safePrefix + "_" + slot + ".png");
			ImageIO.write(image, "png", outputFile.toFile());
		} catch (IOException ex) {
			System.out.println("OCR debug image export failed: " + ex.getMessage());
		}
	}

	private static String sanitisePrefix(String prefix) {
		String candidate = prefix == null ? "ocr" : prefix.trim();
		if (candidate.isBlank()) {
			candidate = "ocr";
		}
		return candidate.replaceAll("[^a-zA-Z0-9._-]", "_");
	}
}
