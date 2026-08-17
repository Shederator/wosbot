package dev.frostguard.engine.service;

import dev.frostguard.api.runtime.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the {@code templatePath} attribute of a task-builder
 * {@code TEMPLATE_SEARCH} step to something the vision layer can load.
 *
 * <p>The attribute holds either a {@code TemplatesEnum} constant name or a
 * reference to an image file. File references reach the resolver in three
 * shapes, and all three must keep working:</p>
 * <ul>
 *   <li>the {@value #FILE_PREFIX} sentinel the template picker writes, which
 *       carries an absolute path;</li>
 *   <li>a bundled asset path such as {@code templates/deals/event_tab.png},
 *       which the packaged application stages next to the launcher;</li>
 *   <li>a relative path a shared task file brought from another machine.</li>
 * </ul>
 *
 * <p>Relative references are matched against the known installation roots
 * rather than the process working directory alone: a launcher that starts the
 * application from an unrelated directory would otherwise break every shared
 * task. The first existing candidate wins, and when none exists the
 * working-directory candidate is returned so the vision layer reports the path
 * the operator actually configured.</p>
 */
public final class TemplatePathResolver {

    /** Sentinel prefix the template picker stores for operator-chosen files. */
    public static final String FILE_PREFIX = "file://";

    /**
     * Overrides the primary lookup root. Set by tests, and available as an
     * escape hatch when an installation stages assets outside the launcher
     * directory.
     */
    public static final String ROOT_PROPERTY = "frostguard.templates.root";

    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern IMAGE_FILE = Pattern.compile(
            ".*\\.(?:png|jpe?g|bmp|gif|webp)$", Pattern.CASE_INSENSITIVE);

    private TemplatePathResolver() {
    }

    /**
     * Whether the stored value names an image file rather than a
     * {@code TemplatesEnum} constant.
     *
     * <p>Enum constants are upper-snake-case identifiers, so any path
     * separator, drive letter, or explicit sentinel marks a file reference.</p>
     */
    public static boolean isFileReference(String templatePath) {
        if (templatePath == null || templatePath.isBlank()) {
            return false;
        }
        String value = templatePath.trim();
        return value.startsWith(FILE_PREFIX)
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || WINDOWS_ABSOLUTE.matcher(value).matches()
                || IMAGE_FILE.matcher(value).matches();
    }

    /**
     * Converts a file reference into an absolute filesystem path.
     *
     * @param templatePath the stored {@code templatePath} attribute
     * @return an absolute, normalized path for the vision layer to read
     * @throws IllegalArgumentException when the reference is blank or unusable
     */
    public static String resolveFileReference(String templatePath) {
        return resolveFileReference(templatePath, null);
    }

    /**
     * Converts a file reference into an absolute path, checking the directory
     * that owns the imported builder file before global installation roots.
     * This lets a shared directory keep its JSON and templates together.
     *
     * @param templatePath the stored {@code templatePath} attribute
     * @param definitionDirectory parent directory of the imported JSON, or null
     * @return an absolute, normalized path for the vision layer to read
     */
    public static String resolveFileReference(String templatePath, Path definitionDirectory) {
        if (templatePath == null || templatePath.isBlank()) {
            throw new IllegalArgumentException("Template path is blank");
        }

        String value = templatePath.trim();
        if (value.startsWith(FILE_PREFIX)) {
            value = value.substring(FILE_PREFIX.length()).trim();
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Template path is blank after the " + FILE_PREFIX + " prefix");
        }

        // Bundled assets are addressed as classpath resources ("/templates/x.png")
        // elsewhere; on disk they are staged without the leading separator.
        if (value.startsWith("/templates/") || value.startsWith("\\templates\\")) {
            value = value.substring(1);
        }

        Path candidate = toPath(value);
        if (candidate.isAbsolute()) {
            return candidate.normalize().toString();
        }

        Path fallback = null;
        for (Path root : lookupRoots(definitionDirectory)) {
            Path resolved = root.resolve(candidate).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved.toString();
            }
            if (fallback == null) {
                fallback = resolved;
            }
        }
        return fallback != null
                ? fallback.toString()
                : candidate.toAbsolutePath().normalize().toString();
    }

    private static Path toPath(String value) {
        try {
            return Paths.get(value);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Template path is not a valid file path: " + value, ex);
        }
    }

    /** Installation roots to probe for a relative template reference, in order. */
    private static List<Path> lookupRoots(Path definitionDirectory) {
        List<Path> roots = new ArrayList<>();
        if (definitionDirectory != null) {
            addRoot(roots, definitionDirectory.toString());
        }
        addRoot(roots, System.getProperty(ROOT_PROPERTY));
        addRoot(roots, System.getProperty("user.dir"));
        addRoot(roots, launcherDirectory());
        try {
            WorkspacePaths workspace = WorkspacePaths.current();
            addRoot(roots, workspace.root().toString());
            addRoot(roots, workspace.customTasks().toString());
        } catch (RuntimeException ex) {
            // A misconfigured workspace must not stop absolute or
            // working-directory references from resolving.
        }
        return roots;
    }

    /** Directory holding the packaged launcher, when running from an install. */
    private static String launcherDirectory() {
        String launcher = System.getProperty("jpackage.app-path", "").trim();
        if (launcher.isEmpty()) {
            return null;
        }
        Path parent = toPath(launcher).toAbsolutePath().getParent();
        return parent != null ? parent.toString() : null;
    }

    private static void addRoot(List<Path> roots, String rawRoot) {
        if (rawRoot == null || rawRoot.isBlank()) {
            return;
        }
        Path root = Paths.get(rawRoot).toAbsolutePath().normalize();
        if (!roots.contains(root)) {
            roots.add(root);
        }
    }
}
