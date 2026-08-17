package dev.frostguard.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildMetadata(String version, boolean pullRequestBuild, String authenticodePublisher) {
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
                properties.getProperty("authenticodePublisher", "").trim());
    }

    private static BuildMetadata unavailable() {
        return new BuildMetadata("unknown", true, "");
    }

    private static String normalizeVersion(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static final class Holder {
        private static final BuildMetadata INSTANCE = read(BuildMetadata.class.getResourceAsStream(RESOURCE));
    }
}
