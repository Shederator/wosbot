package dev.frostguard.app.panel.pets;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.tasks.pets.PetSkillsRoutine.PetSkill;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ties each pet-skill checkbox label to the tile its configuration key makes
 * {@code PetSkillsRoutine} tap.
 *
 * <p>The chain is label (FXML) to config key ({@link PetsLayoutController}) to
 * {@link PetSkill} region (tasks module). The expected tile number is derived
 * from the regions themselves, counted left to right with the top row first,
 * so moving a region or rebinding a key fails here instead of mislabelling the
 * panel. Sources are parsed as text because headless CI has no JavaFX toolkit.</p>
 */
class PetSkillTileLabelTest {

    private static final String FXML_NAMESPACE = "http://javafx.com/fxml/1";
    private static final Pattern PET_SWITCH =
            Pattern.compile("new\\s+PetSwitch\\(\\s*(\\w+)\\s*,\\s*ConfigurationKeyEnum\\.(\\w+)\\s*\\)");
    private static final Pattern TILE_LABEL = Pattern.compile("^Tile (\\d+) - (\\w+)\\b");

    private static Map<String, String> checkBoxTextById;
    private static Map<ConfigurationKeyEnum, String> checkBoxIdByKey;

    @BeforeAll
    static void readPanelSources() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("modules/desktop"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Could not locate the repository root from the working directory");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList checkBoxes = factory.newDocumentBuilder()
                .parse(root.resolve("modules/desktop/src/main/resources/layout/PetsLayout.fxml").toFile())
                .getElementsByTagName("CheckBox");
        checkBoxTextById = new HashMap<>();
        for (int i = 0; i < checkBoxes.getLength(); i++) {
            Element checkBox = (Element) checkBoxes.item(i);
            checkBoxTextById.put(checkBox.getAttributeNS(FXML_NAMESPACE, "id"), checkBox.getAttribute("text"));
        }

        String controller = Files.readString(
                root.resolve("modules/desktop/src/main/java/dev/frostguard/app/panel/pets/PetsLayoutController.java"));
        checkBoxIdByKey = new EnumMap<>(ConfigurationKeyEnum.class);
        Matcher binding = PET_SWITCH.matcher(controller);
        while (binding.find()) {
            checkBoxIdByKey.put(ConfigurationKeyEnum.valueOf(binding.group(2)), binding.group(1));
        }
    }

    @Test
    void eachSkillCheckboxNamesTheTileItsConfigKeyTaps() {
        List<PetSkill> tilesInPanelOrder = Arrays.stream(PetSkill.values())
                .sorted(Comparator.<PetSkill>comparingInt(skill -> skill.getTopLeft().row())
                        .thenComparingInt(skill -> skill.getTopLeft().col()))
                .toList();

        for (int index = 0; index < tilesInPanelOrder.size(); index++) {
            PetSkill skill = tilesInPanelOrder.get(index);
            int expectedTile = index + 1;

            String checkBoxId = checkBoxIdByKey.get(skill.configKey());
            assertNotNull(checkBoxId, "No checkbox is bound to " + skill.configKey());
            String text = checkBoxTextById.get(checkBoxId);
            assertNotNull(text, "PetsLayout.fxml has no CheckBox with fx:id " + checkBoxId);

            Matcher label = TILE_LABEL.matcher(text);
            assertTrue(label.find(), checkBoxId + " label does not start with 'Tile N - Name': " + text);
            assertEquals(expectedTile, Integer.parseInt(label.group(1)),
                    skill.configKey() + " taps tile " + expectedTile + " but is labelled: " + text);
            assertEquals(skill.name(), label.group(2).toUpperCase(),
                    skill.configKey() + " label names the wrong skill: " + text);
        }
    }

    @Test
    void tileNumbersAppearOnlyOnSkillCheckboxes() {
        long tileLabels = checkBoxTextById.values().stream()
                .filter(text -> TILE_LABEL.matcher(text).find())
                .count();

        assertEquals(PetSkill.values().length, tileLabels,
                "Only the pet-skill checkboxes may carry a tile number: " + checkBoxTextById);
    }
}
