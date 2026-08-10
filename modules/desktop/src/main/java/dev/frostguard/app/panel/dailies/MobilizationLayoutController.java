package dev.frostguard.app.panel.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.shared.AbstractProfileController;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import java.util.Map;

public class MobilizationLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxAllianceMobilization;

    @FXML
    private ComboBox<String> rewardsPercentageCombo;

    @FXML
    private CheckBox buildSpeedupsCheck;
    @FXML
    private CheckBox buyPackageCheck;
    @FXML
    private CheckBox chiefGearCharmCheck;
    @FXML
    private CheckBox chiefGearScoreCheck;
    @FXML
    private CheckBox defeatBeastsCheck;
    @FXML
    private CheckBox fireCrystalCheck;
    @FXML
    private CheckBox gatherResourcesCheck;
    @FXML
    private CheckBox heroGearStoneCheck;
    @FXML
    private CheckBox mythicShardCheck;
    @FXML
    private CheckBox rallyCheck;
    @FXML
    private CheckBox trainTroopsCheck;
    @FXML
    private CheckBox trainingSpeedupsCheck;
    @FXML
    private CheckBox useGemsCheck;
    @FXML
    private CheckBox useSpeedupsCheck;

    @FXML
    private TextField minimumPoints200Field;
    @FXML
    private TextField minimumPoints120Field;
    @FXML
    private CheckBox autoAcceptCheck;
    @FXML
    private CheckBox useGemsBottomCheck;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxAllianceMobilization, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BOOL);
        checkBoxMappings.putAll(mobilizationTaskMappings());
        textFieldMappings.put(minimumPoints200Field, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_200_INT);
        textFieldMappings.put(minimumPoints120Field, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_120_INT);
        comboBoxMappings.put(rewardsPercentageCombo, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_REWARDS_PERCENTAGE_STRING);
        bindTaskControlsToMasterSwitch();
        initializeChangeEvents();
    }

    private Map<CheckBox, ConfigurationKeyEnum> mobilizationTaskMappings() {
        return Map.ofEntries(
            Map.entry(buildSpeedupsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BUILD_SPEEDUPS_BOOL),
            Map.entry(buyPackageCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_BUY_PACKAGE_BOOL),
            Map.entry(chiefGearCharmCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_CHIEF_GEAR_CHARM_BOOL),
            Map.entry(chiefGearScoreCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_CHIEF_GEAR_SCORE_BOOL),
            Map.entry(defeatBeastsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_DEFEAT_BEASTS_BOOL),
            Map.entry(fireCrystalCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_FIRE_CRYSTAL_BOOL),
            Map.entry(gatherResourcesCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_GATHER_RESOURCES_BOOL),
            Map.entry(heroGearStoneCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_HERO_GEAR_STONE_BOOL),
            Map.entry(mythicShardCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_MYTHIC_SHARD_BOOL),
            Map.entry(rallyCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_RALLY_BOOL),
            Map.entry(trainTroopsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_TRAIN_TROOPS_BOOL),
            Map.entry(trainingSpeedupsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_TRAINING_SPEEDUPS_BOOL),
            Map.entry(useGemsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_USE_GEMS_BOOL),
            Map.entry(useSpeedupsCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_USE_SPEEDUPS_BOOL),
            Map.entry(autoAcceptCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_AUTO_ACCEPT_BOOL),
            Map.entry(useGemsBottomCheck, ConfigurationKeyEnum.ALLIANCE_MOBILIZATION_USE_GEMS_FOR_ACCEPT_BOOL)
        );
    }

    private void bindTaskControlsToMasterSwitch() {
        checkBoxMappings.keySet().stream()
            .filter(checkBox -> checkBox != checkBoxAllianceMobilization)
            .forEach(checkBox -> checkBox.disableProperty().bind(checkBoxAllianceMobilization.selectedProperty().not()));

        minimumPoints200Field.disableProperty().bind(checkBoxAllianceMobilization.selectedProperty().not());
        minimumPoints120Field.disableProperty().bind(checkBoxAllianceMobilization.selectedProperty().not());
        rewardsPercentageCombo.disableProperty().bind(checkBoxAllianceMobilization.selectedProperty().not());
    }
}
