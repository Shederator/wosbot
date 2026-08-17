package dev.frostguard.app.arch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural conformance between JavaFX controllers and the FXML documents they
 * drive.
 *
 * <p>Controllers in this application are attached with
 * {@code FXMLLoader.setController(...)} instead of an {@code fx:controller}
 * attribute, so the loader silently leaves an {@code @FXML} field null when the
 * matching {@code fx:id} is absent from the document. Nothing fails at build
 * time and nothing fails at load time: the control is simply missing from the
 * screen, and every guarded use of the field becomes a no-op. That is exactly
 * how the Task Builder shipped without a node-name input while the controller
 * read from and wrote to {@code nodeNameField}.</p>
 *
 * <p>These checks compare each {@code XxxLayout.fxml} with its
 * {@code XxxLayoutController} by naming convention — the same convention
 * {@code LauncherLayoutController#loadNode} uses to resolve documents at
 * runtime — and enforce both directions of the contract:</p>
 * <ul>
 *   <li>every {@code @FXML} field has a matching {@code fx:id}, so injection
 *       cannot silently yield null;</li>
 *   <li>every {@code on*="#handler"} reference resolves to a controller method,
 *       so document loading cannot fail at runtime.</li>
 * </ul>
 *
 * <p>Source text is parsed rather than reflection or a live {@code FXMLLoader}
 * because the assertions must hold on headless CI agents, where no JavaFX
 * toolkit is available to realize a scene graph.</p>
 */
class FxmlControllerBindingTest {

    /**
     * Controls that predate these checks and are declared in a controller but
     * absent from its document. Each one is read through an explicit null guard,
     * so the screens work as shipped; they are recorded here so the checks can
     * be strict about anything new without bundling unrelated UI changes into a
     * bug fix. Removing an entry means adding the control to the document or
     * dropping the field.
     */
    private static final Set<String> KNOWN_UNBOUND_FIELDS = Set.of(
            "AllianceLayout#hboxAutojoinQueues",
            "AllianceLayout#textfieldAutojoinQueues",
            "ExpertsLayout#troopOptionsVBox",
            "LauncherLayout#logoSurvival",
            "LauncherLayout#logoWhiteout",
            "ShopLayout#labelPeriod",
            "TaskBuilderLayout#zoomLabel");

    private static final Pattern FX_ID = Pattern.compile("fx:id\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern EVENT_HANDLER = Pattern.compile("\\bon[A-Z]\\w*\\s*=\\s*\"#(\\w+)\"");
    private static final Pattern FXML_ANNOTATION = Pattern.compile("@FXML\\b");
    private static final Pattern SUPERCLASS = Pattern.compile("\\bclass\\s+\\w+\\s+extends\\s+(\\w+)");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern GENERICS = Pattern.compile("<[^<>]*(?:<[^<>]*>)?[^<>]*>");
    private static final Pattern MODIFIERS = Pattern.compile("\\b(?:private|public|protected|final|static|transient)\\b|@\\w+");
    private static final Pattern IDENTIFIER = Pattern.compile("\\w+");

    private static Path layoutDirectory;
    private static Map<String, Path> controllerSources;

    @BeforeAll
    static void locateSources() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("modules/desktop"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Could not locate the repository root from the working directory");

        layoutDirectory = root.resolve("modules/desktop/src/main/resources/layout");
        assertTrue(Files.isDirectory(layoutDirectory), "Missing layout directory: " + layoutDirectory);

        controllerSources = new HashMap<>();
        try (Stream<Path> sources = Files.walk(root.resolve("modules/desktop/src/main/java"))) {
            sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        controllerSources.put(fileName.substring(0, fileName.length() - ".java".length()), path);
                    });
        }
        assertTrue(controllerSources.containsKey("TaskBuilderLayoutController"),
                "Controller sources were not indexed");
    }

    /**
     * A field the loader never injects is the failure mode this suite exists
     * for: the screen renders without the control and every use of the field
     * turns into a silent no-op.
     */
    @Test
    void everyInjectedControlExistsInItsDocument() throws IOException {
        List<String> offenders = new ArrayList<>();
        int comparedDocuments = 0;

        for (Path document : documents()) {
            Path controller = controllerFor(document);
            if (controller == null) {
                continue;
            }
            comparedDocuments++;

            String documentName = documentName(document);
            Set<String> declaredIds = matches(FX_ID, Files.readString(document));
            for (String field : injectedFieldNames(controller)) {
                if (declaredIds.contains(field)) {
                    continue;
                }
                String reference = documentName + "#" + field;
                if (KNOWN_UNBOUND_FIELDS.contains(reference)) {
                    continue;
                }
                offenders.add(reference);
            }
        }

        assertTrue(comparedDocuments >= 30,
                "Expected the naming convention to pair most documents, paired only " + comparedDocuments);
        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(offenders)),
                "These @FXML fields have no matching fx:id, so FXMLLoader leaves them null. "
                        + "Add the control to the document or remove the field.");
    }

    /**
     * The node-name input that issue #136 reported as missing. Pinned by name so
     * a future document edit cannot quietly drop it again.
     */
    @Test
    void taskBuilderDocumentDeclaresTheNodeNameInput() throws IOException {
        Set<String> declaredIds = matches(FX_ID, Files.readString(layoutDirectory.resolve("TaskBuilderLayout.fxml")));
        assertTrue(declaredIds.contains("nodeNameField"),
                "TaskBuilderLayout.fxml must declare nodeNameField so node names can be edited");
    }

    /**
     * The opposite mismatch is louder — an unresolved handler makes
     * {@code FXMLLoader.load} throw — but it still escapes compilation, so it is
     * checked here too.
     */
    @Test
    void everyDocumentEventHandlerResolvesToAControllerMethod() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path document : documents()) {
            Path controller = controllerFor(document);
            if (controller == null) {
                continue;
            }

            String controllerText = controllerHierarchyText(controller);
            for (String handler : matches(EVENT_HANDLER, Files.readString(document))) {
                if (!Pattern.compile("\\b" + Pattern.quote(handler) + "\\s*\\(").matcher(controllerText).find()) {
                    offenders.add(documentName(document) + "#" + handler);
                }
            }
        }

        assertEquals(List.of(), new ArrayList<>(new TreeSet<>(offenders)),
                "These document handlers have no controller method, so FXMLLoader.load fails at runtime.");
    }

    /**
     * The allowlist is a temporary record, not a place to park new defects, so it
     * may not name a control that is now bound or a document that no longer
     * exists.
     */
    @Test
    void allowlistOnlyNamesControlsThatAreStillUnbound() throws IOException {
        List<String> stale = new ArrayList<>();

        for (String reference : new TreeSet<>(KNOWN_UNBOUND_FIELDS)) {
            String documentName = reference.substring(0, reference.indexOf('#'));
            String field = reference.substring(reference.indexOf('#') + 1);
            Path document = layoutDirectory.resolve(documentName + ".fxml");
            if (!Files.isRegularFile(document)) {
                stale.add(reference + " (document no longer exists)");
            } else if (matches(FX_ID, Files.readString(document)).contains(field)) {
                stale.add(reference + " (now bound)");
            }
        }

        assertEquals(List.of(), stale,
                "Remove these entries from KNOWN_UNBOUND_FIELDS; the checks now cover them.");
    }

    private static List<Path> documents() throws IOException {
        try (Stream<Path> files = Files.list(layoutDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".fxml")).sorted().toList();
        }
    }

    private static String documentName(Path document) {
        String fileName = document.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".fxml".length());
    }

    private static Path controllerFor(Path document) {
        return controllerSources.get(documentName(document) + "Controller");
    }

    /** Concatenates a controller with its superclasses so inherited members count as declared. */
    private static String controllerHierarchyText(Path controller) throws IOException {
        StringBuilder combined = new StringBuilder();
        Path current = controller;
        Set<Path> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current)) {
            String text = Files.readString(current);
            combined.append(text).append('\n');
            Matcher superclass = SUPERCLASS.matcher(text);
            current = superclass.find() ? controllerSources.get(superclass.group(1)) : null;
        }
        return combined.toString();
    }

    /**
     * Collects the names of {@code @FXML} fields, including inherited ones and
     * the comma-separated groups this codebase uses (for example
     * {@code private CheckBox checkBoxChests, checkBoxTriumph;}). Annotated
     * methods are skipped: they are event handlers, not injected controls.
     */
    private static Set<String> injectedFieldNames(Path controller) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Path current = controller;
        Set<Path> visited = new LinkedHashSet<>();

        while (current != null && visited.add(current)) {
            String text = Files.readString(current);
            names.addAll(injectedFieldNames(stripComments(text)));
            Matcher superclass = SUPERCLASS.matcher(text);
            current = superclass.find() ? controllerSources.get(superclass.group(1)) : null;
        }
        return names;
    }

    private static Set<String> injectedFieldNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher annotation = FXML_ANNOTATION.matcher(source);

        while (annotation.find()) {
            String remainder = source.substring(annotation.end());
            int terminator = remainder.indexOf(';');
            int bodyStart = remainder.indexOf('{');
            if (terminator < 0 || (bodyStart >= 0 && bodyStart < terminator)) {
                continue;
            }
            String declaration = remainder.substring(0, terminator);
            if (declaration.indexOf('(') >= 0) {
                continue;
            }

            declaration = GENERICS.matcher(MODIFIERS.matcher(declaration).replaceAll("")).replaceAll("").trim();
            String[] typeAndNames = declaration.split("\\s+", 2);
            if (typeAndNames.length < 2) {
                continue;
            }
            for (String candidate : typeAndNames[1].split(",")) {
                String name = candidate.split("=")[0].replace("[]", "").trim();
                if (IDENTIFIER.matcher(name).matches()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
