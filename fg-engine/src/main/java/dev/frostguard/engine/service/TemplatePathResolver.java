package dev.frostguard.engine.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * Resolves custom template references stored in task-builder JSON.
 */
public final class TemplatePathResolver {
    public static final String FILE_PREFIX = "file://";

    private static final Pattern WINDOWS_ABSOLUTE =
            Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private TemplatePathResolver() {
    }

    public static boolean isFileReference(String templatePath) {
        if (templatePath == null || templatePath.isBlank()) {
            return false;
        }
        String value = templatePath.trim();
        return value.startsWith(FILE_PREFIX)
                || value.startsWith(".")
                || value.startsWith("/")
                || value.startsWith("\\")
                || value.contains("/")
                || value.contains("\\")
                || WINDOWS_ABSOLUTE.matcher(value).matches();
    }

    public static String resolveFileReference(String templatePath) {
        if (templatePath == null || templatePath.isBlank()) {
            throw new IllegalArgumentException("Template path is blank");
        }
        String value = templatePath.trim();
        if (value.startsWith(FILE_PREFIX)) {
            value = value.substring(FILE_PREFIX.length());
        } else if (value.startsWith("/templates/")) {
            value = value.substring(1);
        }

        Path path = Paths.get(value);
        if (path.isAbsolute() || WINDOWS_ABSOLUTE.matcher(value).matches()) {
            return path.normalize().toString();
        }
        return Paths.get(System.getProperty("user.dir")).resolve(path).normalize().toString();
    }
}
