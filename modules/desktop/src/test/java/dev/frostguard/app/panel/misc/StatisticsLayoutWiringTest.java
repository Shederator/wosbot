package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks that StatisticsLayout.fxml and its controller still agree.
 *
 * <p>Covers the wiring gap left by testing {@code computeViewState} alone: that method is a clean
 * decision seam, but it cannot notice an {@code fx:id} being renamed, a node being deleted, or an
 * {@code onAction} handler losing its method -- any of which breaks the tab at runtime while every
 * existing test stays green, because the controller's own fields silently stay null.
 *
 * <p>Deliberately structural rather than a live scene-graph smoke test. Instantiating JavaFX
 * controls requires an initialised toolkit, which on a headless Linux runner means adding Monocle or
 * an Xvfb step -- a new CI dependency, and a flaky one, for this single check. Reading the FXML as
 * XML and reflecting over the controller catches the failure that actually happens (a name drifting
 * apart) on every platform with no new dependency. It does NOT verify property binding, layout, or
 * that a click reaches a handler; a live smoke test remains worth doing separately.
 *
 * <p>Evidence level: automated tests.
 */
class StatisticsLayoutWiringTest {

    private static final String FXML = "/layout/StatisticsLayout.fxml";
    private static final Class<?> CONTROLLER = StatisticsLayoutController.class;

    private static Document fxml() throws Exception {
        try (InputStream in = StatisticsLayoutWiringTest.class.getResourceAsStream(FXML)) {
            Objects.requireNonNull(in, "missing " + FXML);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // FXML carries processing instructions and namespaces; no DTD resolution wanted here.
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static void walk(Node node, List<Element> out) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            out.add((Element) node);
        }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            walk(kids.item(i), out);
        }
    }

    private static List<Element> allElements() throws Exception {
        List<Element> out = new ArrayList<>();
        walk(fxml().getDocumentElement(), out);
        return out;
    }

    private static Set<String> attributeValues(String attribute) throws Exception {
        Set<String> values = new LinkedHashSet<>();
        for (Element e : allElements()) {
            NamedNodeMap attrs = e.getAttributes();
            Node a = attrs == null ? null : attrs.getNamedItem(attribute);
            if (a != null && !a.getNodeValue().isBlank()) {
                values.add(a.getNodeValue().trim());
            }
        }
        return values;
    }

    @Test
    void theFxmlParsesAtAll() throws Exception {
        assertFalse(allElements().isEmpty(), "the layout should contain elements");
    }

    @Test
    void everyFxIdHasAMatchingFxmlFieldOnTheController() throws Exception {
        Set<String> ids = attributeValues("fx:id");
        assertFalse(ids.isEmpty(), "the layout is expected to declare fx:id nodes");

        Set<String> fields = Arrays.stream(CONTROLLER.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(javafx.fxml.FXML.class))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> missing = ids.stream().filter(id -> !fields.contains(id)).toList();
        if (!missing.isEmpty()) {
            fail("FXML declares fx:id " + missing + " with no matching @FXML field on "
                    + CONTROLLER.getSimpleName() + ". At runtime those fields stay null and the tab "
                    + "silently half-renders. Controller @FXML fields: " + fields);
        }
    }

    @Test
    void everyOnActionHandlerExistsOnTheController() throws Exception {
        Set<String> handlers = new LinkedHashSet<>();
        Pattern ref = Pattern.compile("^#(\\w+)$");
        for (String value : attributeValues("onAction")) {
            Matcher m = ref.matcher(value.trim());
            if (m.matches()) {
                handlers.add(m.group(1));
            }
        }
        assertFalse(handlers.isEmpty(), "the layout is expected to bind at least one action");

        Set<String> methods = Arrays.stream(CONTROLLER.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> missing = handlers.stream().filter(h -> !methods.contains(h)).toList();
        if (!missing.isEmpty()) {
            fail("FXML binds onAction to " + missing + " but " + CONTROLLER.getSimpleName()
                    + " has no such method -- the control would throw on click.");
        }
    }

    @Test
    void theRefreshAndResetControlsAreStillWired() throws Exception {
        // Named explicitly because the review calls out refresh as a path worth covering: if either
        // disappears from the layout, the failure is a dead button rather than a compile error.
        Set<String> ids = attributeValues("fx:id");
        assertTrue(ids.contains("btnRefresh"), "refresh button missing from the layout; ids=" + ids);
        assertTrue(ids.contains("btnReset"), "reset button missing from the layout; ids=" + ids);
    }

    @Test
    void theTimeframeSelectorIsStillPresent() throws Exception {
        // The whole point of this PR is timeframe selection, so the control that drives it has to be
        // in the layout. Matched loosely by name so a ComboBox/ChoiceBox swap does not fail this.
        Set<String> ids = attributeValues("fx:id");
        boolean hasSelector = ids.stream().anyMatch(id -> {
            String lower = id.toLowerCase();
            return lower.contains("window") || lower.contains("timeframe")
                    || lower.contains("range") || lower.contains("segment");
        });
        assertTrue(hasSelector, "no timeframe-selector fx:id found in the layout; ids=" + ids);
    }
}
