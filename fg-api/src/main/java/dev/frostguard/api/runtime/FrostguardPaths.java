package dev.frostguard.api.runtime;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/** Resolves replaceable application files separately from persistent user data. */
public final class FrostguardPaths {
    public static final String DATA_PROPERTY = "frostguard.data";
    public static final String HOME_PROPERTY = "frostguard.home";
    public static final String NATIVE_PROPERTY = "frostguard.native";
    public static final String DATA_ENVIRONMENT = "FROSTGUARD_DATA";
    public static final String NATIVE_ENVIRONMENT = "FROSTGUARD_NATIVE";

    private final Path applicationHome;
    private final Path dataHome;
    private final Path nativeHome;
    private final Path codeSource;

    private FrostguardPaths(Path applicationHome, Path dataHome, Path nativeHome, Path codeSource) {
        this.applicationHome = applicationHome;
        this.dataHome = dataHome;
        this.nativeHome = nativeHome;
        this.codeSource = codeSource;
    }

    public static FrostguardPaths resolve(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        Path codeSource = codeSource(anchor);
        Path explicitHome = propertyPath(HOME_PROPERTY);
        Path repository = findAncestor(codeSource, FrostguardPaths::isRepository);
        Path distribution = findAncestor(codeSource, path -> Files.isRegularFile(path.resolve("build-info.json")));

        Path applicationHome;
        if (explicitHome != null) {
            applicationHome = explicitHome;
        } else if (distribution != null) {
            applicationHome = distribution;
        } else if (repository != null) {
            applicationHome = repository.resolve(".frostguard");
        } else {
            throw unresolved(codeSource, "application home");
        }

        Path explicitData = propertyPath(DATA_PROPERTY);
        if (explicitData == null) {
            explicitData = environmentPath(DATA_ENVIRONMENT);
        }
        Path dataHome;
        if (explicitData != null) {
            dataHome = explicitData;
        } else if (repository != null) {
            dataHome = repository.resolve(".frostguard/data");
        } else if (distribution != null) {
            dataHome = distribution.resolve("data");
        } else {
            throw unresolved(codeSource, "data home");
        }
        Path explicitNative = propertyPath(NATIVE_PROPERTY);
        if (explicitNative == null) {
            explicitNative = environmentPath(NATIVE_ENVIRONMENT);
        }
        Path nativeHome = explicitNative != null
                ? explicitNative
                : applicationHome.resolve("app/lib/native");
        return new FrostguardPaths(normalize(applicationHome), normalize(dataHome), normalize(nativeHome), codeSource);
    }

    public Path applicationHome() {
        return applicationHome;
    }

    public Path dataHome() {
        return dataHome;
    }

    public Path nativeHome() {
        return nativeHome;
    }

    public Path codeSource() {
        return codeSource;
    }

    public Path applicationJar() {
        return Files.isRegularFile(codeSource) && codeSource.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                ? codeSource
                : applicationHome.resolve("app");
    }

    public Path customTasks() {
        return dataHome.resolve("custom-tasks");
    }

    public Path logs() {
        return dataHome.resolve("logs");
    }

    public Path temp() {
        return dataHome.resolve("temp");
    }

    public Path resources() {
        return applicationHome.resolve("resources");
    }

    public void createDataDirectories() {
        try {
            Files.createDirectories(dataHome);
            Files.createDirectories(dataHome.resolve("config"));
            Files.createDirectories(customTasks());
            Files.createDirectories(logs());
            Files.createDirectories(temp());
        } catch (Exception cause) {
            throw new IllegalStateException("Cannot initialize Frostguard data directory " + dataHome, cause);
        }
    }

    private static Path codeSource(Class<?> anchor) {
        try {
            URI location = anchor.getProtectionDomain().getCodeSource().getLocation().toURI();
            return normalize(Paths.get(location));
        } catch (Exception cause) {
            throw new IllegalStateException("Cannot determine Frostguard code source", cause);
        }
    }

    private static Path findAncestor(Path start, java.util.function.Predicate<Path> predicate) {
        Path current = Files.isDirectory(start) ? start : start.getParent();
        while (current != null) {
            if (predicate.test(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isRepository(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("fg-app"))
                && Files.isDirectory(path.resolve("fg-api"));
    }

    private static Path propertyPath(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : normalize(Paths.get(value));
    }

    private static Path environmentPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : normalize(Paths.get(value));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static IllegalStateException unresolved(Path source, String target) {
        return new IllegalStateException("Cannot resolve Frostguard " + target + " from " + source
                + "; set -D" + DATA_PROPERTY + "=/absolute/path (and optionally -D" + HOME_PROPERTY + "=/absolute/path)");
    }
}
