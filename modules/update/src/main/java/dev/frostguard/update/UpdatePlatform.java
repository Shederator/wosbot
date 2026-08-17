package dev.frostguard.update;

import java.util.Locale;

public record UpdatePlatform(OperatingSystem operatingSystem, Architecture architecture) {
    public UpdatePlatform {
        if (operatingSystem == null || architecture == null) {
            throw new IllegalArgumentException("Update platform requires an operating system and architecture");
        }
    }

    public String key() {
        return operatingSystem.id() + "-" + architecture.id();
    }

    public static UpdatePlatform current() {
        return new UpdatePlatform(OperatingSystem.detect(System.getProperty("os.name")),
                Architecture.detect(System.getProperty("os.arch")));
    }

    public enum OperatingSystem {
        WINDOWS("windows"), MACOS("macos"), LINUX("linux");

        private final String id;

        OperatingSystem(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static OperatingSystem from(String value) {
            for (OperatingSystem candidate : values()) {
                if (candidate.id.equalsIgnoreCase(value)) return candidate;
            }
            throw new IllegalArgumentException("Unsupported operating system: " + value);
        }

        static OperatingSystem detect(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            if (normalized.contains("win")) return WINDOWS;
            if (normalized.contains("mac") || normalized.contains("darwin")) return MACOS;
            if (normalized.contains("linux")) return LINUX;
            throw new IllegalStateException("Unsupported operating system: " + value);
        }
    }

    public enum Architecture {
        X64("x64"), ARM64("arm64");

        private final String id;

        Architecture(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Architecture from(String value) {
            for (Architecture candidate : values()) {
                if (candidate.id.equalsIgnoreCase(value)) return candidate;
            }
            throw new IllegalArgumentException("Unsupported architecture: " + value);
        }

        static Architecture detect(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            if (normalized.equals("amd64") || normalized.equals("x86_64") || normalized.equals("x64")) return X64;
            if (normalized.equals("aarch64") || normalized.equals("arm64")) return ARM64;
            throw new IllegalStateException("Unsupported architecture: " + value);
        }
    }
}
