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
    @FXML private TextField tfDailyStartTime;
    @FXML private TextField tfSquad1Inf;
    @FXML private TextField tfSquad1Lan;
    @FXML private TextField tfSquad1Mrk;
    @FXML private TextField tfSquad2Inf;
    @FXML private TextField tfSquad2Lan;
    @FXML private TextField tfSquad2Mrk;
    @FXML private Label labelRatioHint;

    // matt/2026-08-13: "we're up to like three now" -- Cave of Monsters + Charm Mine get the same
    // per-squad ratio controls as Land of Heroes.
    @FXML private TextField tfCaveSquad1Inf;
    @FXML private TextField tfCaveSquad1Lan;
    @FXML private TextField tfCaveSquad1Mrk;
    @FXML private TextField tfCaveSquad2Inf;
    @FXML private TextField tfCaveSquad2Lan;
    @FXML private TextField tfCaveSquad2Mrk;
    @FXML private Label labelCaveRatioHint;

    @FXML private TextField tfCharmSquad1Inf;
    @FXML private TextField tfCharmSquad1Lan;
    @FXML private TextField tfCharmSquad1Mrk;
    @FXML private TextField tfCharmSquad2Inf;
    @FXML private TextField tfCharmSquad2Lan;
    @FXML private TextField tfCharmSquad2Mrk;
    @FXML private Label labelCharmRatioHint;

    // matt/2026-08-15: "add the research center in the gear forge... where we can start entering
    // true default troop ratios" -- Research Center and Gear Forge are single-composition Challenge
    // fights (no Squad1/Squad2 split), so one Inf/Lan/Mrk row each instead of the two-squad layout
    // above. LabyrinthRaidRoutine.challengeZone() reads these as the first attempt's preset.
    @FXML private TextField tfResearchInf;
    @FXML private TextField tfResearchLan;
    @FXML private TextField tfResearchMrk;
    @FXML private Label labelResearchRatioHint;

    @FXML private TextField tfGearForgeInf;
    @FXML private TextField tfGearForgeLan;
    @FXML private TextField tfGearForgeMrk;
    @FXML private Label labelGearForgeRatioHint;

    @FXML
    private void initialize() {
        comboBoxGeneration.getItems().setAll("Gen 1", "Gen 2", "Gen 3", "Gen 4", "Gen 5", "Gen 6");

        checkBoxMappings.put(checkBoxEnableLabyrinth, ConfigurationKeyEnum.DAILY_LABYRINTH_BOOL);
        checkBoxMappings.put(checkBoxFormationTest, ConfigurationKeyEnum.LABYRINTH_FORMATION_TEST_BOOL);
        comboBoxMappings.put(comboBoxGeneration, ConfigurationKeyEnum.LABYRINTH_GENERATION_STRING);
        textFieldMappings.put(tfDailyStartTime, ConfigurationKeyEnum.LABYRINTH_DAILY_START_TIME_STRING);

        textFieldMappings.put(tfSquad1Inf, ConfigurationKeyEnum.LABYRINTH_SQUAD1_INFANTRY_INT);
        textFieldMappings.put(tfSquad1Lan, ConfigurationKeyEnum.LABYRINTH_SQUAD1_LANCER_INT);
        textFieldMappings.put(tfSquad1Mrk, ConfigurationKeyEnum.LABYRINTH_SQUAD1_MARKSMAN_INT);
        textFieldMappings.put(tfSquad2Inf, ConfigurationKeyEnum.LABYRINTH_SQUAD2_INFANTRY_INT);
        textFieldMappings.put(tfSquad2Lan, ConfigurationKeyEnum.LABYRINTH_SQUAD2_LANCER_INT);
        textFieldMappings.put(tfSquad2Mrk, ConfigurationKeyEnum.LABYRINTH_SQUAD2_MARKSMAN_INT);

        textFieldMappings.put(tfCaveSquad1Inf, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_INFANTRY_INT);
        textFieldMappings.put(tfCaveSquad1Lan, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_LANCER_INT);
        textFieldMappings.put(tfCaveSquad1Mrk, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD1_MARKSMAN_INT);
        textFieldMappings.put(tfCaveSquad2Inf, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_INFANTRY_INT);
        textFieldMappings.put(tfCaveSquad2Lan, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_LANCER_INT);
        textFieldMappings.put(tfCaveSquad2Mrk, ConfigurationKeyEnum.LABYRINTH_CAVE_SQUAD2_MARKSMAN_INT);

        textFieldMappings.put(tfCharmSquad1Inf, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_INFANTRY_INT);
        textFieldMappings.put(tfCharmSquad1Lan, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_LANCER_INT);
        textFieldMappings.put(tfCharmSquad1Mrk, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD1_MARKSMAN_INT);
        textFieldMappings.put(tfCharmSquad2Inf, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_INFANTRY_INT);
        textFieldMappings.put(tfCharmSquad2Lan, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_LANCER_INT);
        textFieldMappings.put(tfCharmSquad2Mrk, ConfigurationKeyEnum.LABYRINTH_CHARM_SQUAD2_MARKSMAN_INT);

        textFieldMappings.put(tfResearchInf, ConfigurationKeyEnum.LABYRINTH_RESEARCH_INFANTRY_INT);
        textFieldMappings.put(tfResearchLan, ConfigurationKeyEnum.LABYRINTH_RESEARCH_LANCER_INT);
        textFieldMappings.put(tfResearchMrk, ConfigurationKeyEnum.LABYRINTH_RESEARCH_MARKSMAN_INT);

        textFieldMappings.put(tfGearForgeInf, ConfigurationKeyEnum.LABYRINTH_GEARFORGE_INFANTRY_INT);
        textFieldMappings.put(tfGearForgeLan, ConfigurationKeyEnum.LABYRINTH_GEARFORGE_LANCER_INT);
        textFieldMappings.put(tfGearForgeMrk, ConfigurationKeyEnum.LABYRINTH_GEARFORGE_MARKSMAN_INT);

        initializeChangeEvents();
        installRatioHint(labelRatioHint, tfSquad1Inf, tfSquad1Lan, tfSquad1Mrk, tfSquad2Inf, tfSquad2Lan, tfSquad2Mrk);
        installRatioHint(labelCaveRatioHint, tfCaveSquad1Inf, tfCaveSquad1Lan, tfCaveSquad1Mrk,
                tfCaveSquad2Inf, tfCaveSquad2Lan, tfCaveSquad2Mrk);
        installRatioHint(labelCharmRatioHint, tfCharmSquad1Inf, tfCharmSquad1Lan, tfCharmSquad1Mrk,
                tfCharmSquad2Inf, tfCharmSquad2Lan, tfCharmSquad2Mrk);
        installSingleRowRatioHint(labelResearchRatioHint, tfResearchInf, tfResearchLan, tfResearchMrk);
        installSingleRowRatioHint(labelGearForgeRatioHint, tfGearForgeInf, tfGearForgeLan, tfGearForgeMrk);
    }

    /** Same "should total 100%" hint as {@link #installRatioHint}, but for the single-composition
     *  Research Center / Gear Forge rows (no Squad 1 / Squad 2 split). */
    private void installSingleRowRatioHint(Label hintLabel, TextField inf, TextField lan, TextField mrk) {
        Runnable update = () -> {
            Integer total = sum(inf, lan, mrk);
            hintLabel.setText(total != null && total != 100 ? "Totals " + total + "% (should be 100)." : "");
        };
        for (TextField tf : new TextField[] { inf, lan, mrk }) {
            tf.textProperty().addListener((obs, oldV, newV) -> update.run());
        }
        update.run();
    }

    /**
     * Live "should total 100%" hint under a zone's ratio fields. Generic over which zone (Land of
     * Heroes / Cave of Monsters / Charm Mine) so the same wiring covers all three.
     */
    private void installRatioHint(Label hintLabel, TextField sq1Inf, TextField sq1Lan, TextField sq1Mrk,
                                   TextField sq2Inf, TextField sq2Lan, TextField sq2Mrk) {
        TextField[] all = { sq1Inf, sq1Lan, sq1Mrk, sq2Inf, sq2Lan, sq2Mrk };
        Runnable update = () -> {
            Integer s1 = sum(sq1Inf, sq1Lan, sq1Mrk);
            Integer s2 = sum(sq2Inf, sq2Lan, sq2Mrk);
            StringBuilder sb = new StringBuilder();
            if (s1 != null && s1 != 100) sb.append("Squad 1 totals ").append(s1).append("% (should be 100). ");
            if (s2 != null && s2 != 100) sb.append("Squad 2 totals ").append(s2).append("% (should be 100).");
            if (hintLabel != null) hintLabel.setText(sb.toString());
        };
        for (TextField tf : all) {
            tf.textProperty().addListener((obs, oldV, newV) -> update.run());
        }
        update.run();
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
