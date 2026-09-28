package dev.frostguard.engine.service;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Locates the executable desktop JAR used by source and portable launches. */
public final class DesktopJarLocator {

    private DesktopJarLocator() {}

    public static Optional<Path> detect() {
        Set<Path> anchors = new LinkedHashSet<>();
        anchors.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
        try {
            Path codeSource = Path.of(DesktopJarLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            anchors.add(Files.isDirectory(codeSource) ? codeSource : codeSource.getParent());
        } catch (URISyntaxException | NullPointerException ignored) {
            // The working-directory anchor still covers normal source and portable launches.
        }

        return anchors.stream()
                .filter(java.util.Objects::nonNull)
                .map(DesktopJarLocator::findFrom)
                .flatMap(Optional::stream)
                .max(DesktopJarLocator::compareCandidates);
    }

    static Optional<Path> findFrom(Path start) {
        if (start == null) return Optional.empty();

        Set<Path> directories = new LinkedHashSet<>();
        Path current = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(current)) current = current.getParent();
        while (current != null) {
            directories.add(current);
            directories.add(current.resolve("modules").resolve("desktop").resolve("target"));
            current = current.getParent();
        }

        return directories.stream()
                .filter(Files::isDirectory)
                .flatMap(DesktopJarLocator::listCandidates)
                .max(DesktopJarLocator::compareCandidates)
                .map(Path::toAbsolutePath)
                .map(Path::normalize);
    }

    private static Stream<Path> listCandidates(Path directory) {
        try {
            return Files.list(directory)
                    .filter(Files::isRegularFile)
                    .filter(DesktopJarLocator::isDesktopJar);
        } catch (IOException ignored) {
            return Stream.empty();
        }
    }

    private static boolean isDesktopJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("frostguard-desktop-")
                && name.endsWith(".jar")
                && !name.endsWith("-sources.jar")
                && !name.endsWith("-javadoc.jar")
                && !name.endsWith("-shaded.jar");
    }

    private static int compareCandidates(Path left, Path right) {
        int modifiedComparison = modifiedTime(left).compareTo(modifiedTime(right));
        if (modifiedComparison != 0) return modifiedComparison;
        return left.getFileName().toString().compareTo(right.getFileName().toString());
    }

    private static FileTime modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ignored) {
            return FileTime.fromMillis(0);
        }
    }
}
