package dev.frostguard.update;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ProjectUpdateKey {
    private static final String RESOURCE = "/dev/frostguard/update/project-update-key.properties";

    private ProjectUpdateKey() {
    }

    public static ManifestVerificationKey current() {
        return Holder.INSTANCE;
    }

    static ManifestVerificationKey read(InputStream input) {
        if (input == null) {
            return new ManifestVerificationKey("", "");
        }
        Properties properties = new Properties();
        try (input) {
            properties.load(input);
        } catch (IOException exception) {
            return new ManifestVerificationKey("", "");
        }
        return new ManifestVerificationKey(
                properties.getProperty("keyId", ""),
                properties.getProperty("publicKey", ""));
    }

    private static final class Holder {
        private static final ManifestVerificationKey INSTANCE = read(
                ProjectUpdateKey.class.getResourceAsStream(RESOURCE));
    }
}
