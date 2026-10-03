package dev.frostguard.app;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;

public record BuildMetadata(String version, boolean pullRequestBuild, String authenticodePublisher,
        String commit, String buildTime) {
    private static final String RESOURCE = "/dev/frostguard/app/frostguard-build.properties";

    public static BuildMetadata current() {
        return Holder.INSTANCE;
    }

    static BuildMetadata read(InputStream input) {
        if (input == null) {
            return unavailable();
        }
        Properties properties = new Properties();
        try (input) {
            properties.load(input);
        } catch (IOException exception) {
            return unavailable();
        }
        String value = properties.getProperty("pullRequestBuild", "").trim();
        if (!value.equals("true") && !value.equals("false")) {
            return unavailable();
        }
        return new BuildMetadata(normalizeVersion(properties.getProperty("version")), Boolean.parseBoolean(value),
                properties.getProperty("authenticodePublisher", "").trim(),
                normalizeCommit(properties.getProperty("commit")),
                normalizeBuildTime(properties.getProperty("buildTime")));
    }

    private static BuildMetadata unavailable() {
        return new BuildMetadata("unknown", true, "", "unknown", "unknown");
    }

    private static String normalizeCommit(String value) {
        if (value == null) {
            return "unknown";
        }
        String commit = value.trim();
        return commit.matches("(?i)[0-9a-f]{40,64}") ? commit.toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String normalizeBuildTime(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String buildTime = value.trim();
        try {
            return Instant.parse(buildTime).toString();
        } catch (DateTimeParseException exception) {
            return "unknown";
        }
    }

    private static String normalizeVersion(String value) {
        return value == null || value.isBlank() || value.contains("${") ? "unknown" : value.trim();
    }

    private static final class Holder {
        private static final BuildMetadata INSTANCE = read(BuildMetadata.class.getResourceAsStream(RESOURCE));
    }
}
