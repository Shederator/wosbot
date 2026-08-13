package dev.frostguard.app.panel.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Settings for the weekly Labyrinth. Drives the enable flag, the formation-test gate, the account's
 * generation, and the per-squad Land-of-Heroes troop ratios that {@code DailyLabyrinthRoutine} reads.
 */
public class LabyrinthLayoutController extends AbstractProfileController {

    @FXML private CheckBox checkBoxEnableLabyrinth;
    @FXML private CheckBox checkBoxFormationTest;
    @FXML private ComboBox<String> comboBoxGeneration;
    @FXML private TextField tfSquad1Inf;
    @FXML private TextField tfSquad1Lan;
    @FXML private TextField tfSquad1Mrk;
    @FXML private TextField tfSquad2Inf;
    @FXML private TextField tfSquad2Lan;
    @FXML private TextField tfSquad2Mrk;
    @FXML private Label labelRatioHint;

    @FXML
    private void initialize() {
        comboBoxGeneration.getItems().setAll("Gen 1", "Gen 2", "Gen 3", "Gen 4", "Gen 5", "Gen 6");

        checkBoxMappings.put(checkBoxEnableLabyrinth, ConfigurationKeyEnum.DAILY_LABYRINTH_BOOL);
        checkBoxMappings.put(checkBoxFormationTest, ConfigurationKeyEnum.LABYRINTH_FORMATION_TEST_BOOL);
        comboBoxMappings.put(comboBoxGeneration, ConfigurationKeyEnum.LABYRINTH_GENERATION_STRING);

        textFieldMappings.put(tfSquad1Inf, ConfigurationKeyEnum.LABYRINTH_SQUAD1_INFANTRY_INT);
        textFieldMappings.put(tfSquad1Lan, ConfigurationKeyEnum.LABYRINTH_SQUAD1_LANCER_INT);
        textFieldMappings.put(tfSquad1Mrk, ConfigurationKeyEnum.LABYRINTH_SQUAD1_MARKSMAN_INT);
        textFieldMappings.put(tfSquad2Inf, ConfigurationKeyEnum.LABYRINTH_SQUAD2_INFANTRY_INT);
        textFieldMappings.put(tfSquad2Lan, ConfigurationKeyEnum.LABYRINTH_SQUAD2_LANCER_INT);
        textFieldMappings.put(tfSquad2Mrk, ConfigurationKeyEnum.LABYRINTH_SQUAD2_MARKSMAN_INT);

        initializeChangeEvents();
        installRatioHint();
    }

    /** Live "should total 100%" hint under the ratio fields. */
    private void installRatioHint() {
        TextField[] all = { tfSquad1Inf, tfSquad1Lan, tfSquad1Mrk, tfSquad2Inf, tfSquad2Lan, tfSquad2Mrk };
        for (TextField tf : all) {
            tf.textProperty().addListener((obs, oldV, newV) -> updateRatioHint());
        }
        updateRatioHint();
    }

    private void updateRatioHint() {
        Integer s1 = sum(tfSquad1Inf, tfSquad1Lan, tfSquad1Mrk);
        Integer s2 = sum(tfSquad2Inf, tfSquad2Lan, tfSquad2Mrk);
        StringBuilder sb = new StringBuilder();
        if (s1 != null && s1 != 100) sb.append("Squad 1 totals ").append(s1).append("% (should be 100). ");
        if (s2 != null && s2 != 100) sb.append("Squad 2 totals ").append(s2).append("% (should be 100).");
        if (labelRatioHint != null) labelRatioHint.setText(sb.toString());
    }

    private Integer sum(TextField... fields) {
        int total = 0;
        for (TextField f : fields) {
            try {
                total += Integer.parseInt(f.getText().trim());
            } catch (Exception e) {
                return null; // a field is blank/non-numeric — skip the hint
            }
        }
        return total;
    }
}
