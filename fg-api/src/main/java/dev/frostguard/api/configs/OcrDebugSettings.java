package dev.frostguard.api.configs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Runtime view of OCR debug image settings.
 */
public final class OcrDebugSettings {

	private static final String DEFAULT_RELATIVE_DIRECTORY = Paths.get("Botpfad", "OCRImages").toString();

	private static volatile boolean enabled = false;
	private static volatile String outputDirectory = DEFAULT_RELATIVE_DIRECTORY;

	private OcrDebugSettings() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static String getOutputDirectory() {
		return outputDirectory;
	}

	public static void setOutputDirectory(String value) {
		outputDirectory = normaliseDirectoryValue(value);
	}

	public static void syncFrom(Map<String, String> config) {
		if (config == null) {
			return;
		}
		setEnabled(Boolean.parseBoolean(config.getOrDefault(
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_ENABLED_BOOL.name(),
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_ENABLED_BOOL.getDefaultValue())));
		setOutputDirectory(config.getOrDefault(
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING.name(),
				ConfigurationKeyEnum.OCR_DEBUG_IMAGES_PATH_STRING.getDefaultValue()));
	}

	public static Path resolveOutputDirectory() {
		String configured = normaliseDirectoryValue(outputDirectory);
		Path candidate = Paths.get(configured);
		if (!candidate.isAbsolute()) {
			candidate = Paths.get(System.getProperty("user.dir")).resolve(candidate);
		}
		return candidate.normalize();
	}

	private static String normaliseDirectoryValue(String value) {
		String candidate = value == null ? "" : value.trim();
		return candidate.isBlank() ? DEFAULT_RELATIVE_DIRECTORY : candidate;
	}
}
