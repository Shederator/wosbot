package dev.frostguard.app.panel.combat;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.PolarTerrorMode;
import dev.frostguard.app.panel.profile.ProfileAux;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class PolarTerrorLayoutController extends AbstractProfileController {

    private static final int MAX_POLAR_TERROR_LEVEL = 8;

    @FXML
    private CheckBox checkBoxEnablePolarTerror;

    @FXML
    private ComboBox<Integer> comboBoxPolarTerrorLevel;

    @FXML
    private ComboBox<Integer> comboBoxPolarTerrorMarches;

    @FXML
    private TextField textFieldPolarStaminaReserve;

    @FXML
    private CheckBox checkBoxPolarHighestLevel;

    @FXML
    private CheckBox checkBoxPolarUseStaminaItems;

    @FXML
    private TextField textFieldPolarStaminaItemReserve;

    @FXML
    private Label labelPolarTerrorMarch1Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch1Flag;

    @FXML
    private Label labelPolarTerrorMarch2Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch2Flag;

    @FXML
    private Label labelPolarTerrorMarch3Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch3Flag;

    @FXML
    private Label labelPolarTerrorMarch4Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch4Flag;

    @FXML
    private Label labelPolarTerrorMarch5Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch5Flag;

    @FXML
    private Label labelPolarTerrorMarch6Flag;
    @FXML
    private ComboBox<String> comboBoxPolarTerrorMarch6Flag;

    @FXML
    private ComboBox<String> comboBoxPolarTerrorMode;

    @FXML
    private CheckBox checkBoxEnableBerserkCryptid;

    @FXML
    private Label labelBerserkCryptidMarch1Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch1Flag;

    @FXML
    private Label labelBerserkCryptidMarch2Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch2Flag;

    @FXML
    private Label labelBerserkCryptidMarch3Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch3Flag;

    @FXML
    private Label labelBerserkCryptidMarch4Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch4Flag;

    @FXML
    private Label labelBerserkCryptidMarch5Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch5Flag;

    @FXML
    private Label labelBerserkCryptidMarch6Flag;
    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMarch6Flag;

    @FXML
    private ComboBox<String> comboBoxBerserkCryptidMode;

    @FXML
    private ComboBox<Integer> comboBoxBerserkCryptidMarches;

    @FXML
    private ComboBox<String> comboBoxRallyTarget;

    /**
     * Ordered map: display name -> filename key (without extension).
     * Add new targets here as new PNGs are added to /templates/rally/targets/.
     */
    private static final Map<String, String> RALLY_TARGETS = new LinkedHashMap<>();
    static {
        RALLY_TARGETS.put("Everything", "everything");
        RALLY_TARGETS.put("Berserk Cryptid", "berserkCryptid");
        RALLY_TARGETS.put("Cave Lion", "caveLion");
        RALLY_TARGETS.put("Snow Ape", "snowApe");
    }

    @FXML
    private void initialize() {
        // CheckBox mappings
        checkBoxMappings.put(checkBoxEnablePolarTerror, ConfigurationKeyEnum.POLAR_TERROR_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxEnableBerserkCryptid, ConfigurationKeyEnum.RALLY_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxPolarHighestLevel, ConfigurationKeyEnum.POLAR_TERROR_HIGHEST_LEVEL_BOOL);
        checkBoxMappings.put(checkBoxPolarUseStaminaItems, ConfigurationKeyEnum.POLAR_TERROR_USE_STAMINA_ITEMS_BOOL);

        // Polar Terror combos
        for (int i = 1; i <= MAX_POLAR_TERROR_LEVEL; i++) {
            comboBoxPolarTerrorLevel.getItems().addAll(i);
        }
        comboBoxPolarTerrorMode.getItems().addAll(
                PolarTerrorMode.SPECIAL_REWARDS.getDisplayName(),
                PolarTerrorMode.UNLIMITED.getDisplayName());

        // Manual Rally Join combos
        @SuppressWarnings("unchecked")
        ComboBox<String>[] marchFlagCombos = new ComboBox[] {
                comboBoxBerserkCryptidMarch1Flag, comboBoxBerserkCryptidMarch2Flag,
                comboBoxBerserkCryptidMarch3Flag, comboBoxBerserkCryptidMarch4Flag,
                comboBoxBerserkCryptidMarch5Flag, comboBoxBerserkCryptidMarch6Flag,
                comboBoxPolarTerrorMarch1Flag, comboBoxPolarTerrorMarch2Flag,
                comboBoxPolarTerrorMarch3Flag, comboBoxPolarTerrorMarch4Flag,
                comboBoxPolarTerrorMarch5Flag, comboBoxPolarTerrorMarch6Flag
        };
        for (ComboBox<String> combo : marchFlagCombos) {
            combo.getItems().add("No Flag");
            for (int i = 1; i <= 8; i++) {
                combo.getItems().add(String.valueOf(i));
            }
        }
        comboBoxBerserkCryptidMode.getItems().addAll("Limited (10)", "Limited (50)", "Unlimited");
        for (int i = 1; i <= 6; i++) {
            comboBoxBerserkCryptidMarches.getItems().add(i);
            comboBoxPolarTerrorMarches.getItems().add(i);
        }

        // Target selector: items are filename keys; cells show display name + thumbnail
        comboBoxRallyTarget.getItems().addAll(RALLY_TARGETS.values());
        comboBoxRallyTarget.setCellFactory(lv -> buildTargetCell());
        comboBoxRallyTarget.setButtonCell(buildTargetCell());

        // ComboBox mappings
        comboBoxMappings.put(comboBoxPolarTerrorLevel, ConfigurationKeyEnum.POLAR_TERROR_LEVEL_INT);
        comboBoxMappings.put(comboBoxPolarTerrorMode, ConfigurationKeyEnum.POLAR_TERROR_MODE_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarches, ConfigurationKeyEnum.POLAR_TERROR_MARCHES_INT);

        // Shared stamina reserve kept back for Intel/Rally (same key as Beast Hunting)
        registerTextField(textFieldPolarStaminaReserve, ConfigurationKeyEnum.STAMINA_RESERVE_INT);
        registerTextField(textFieldPolarStaminaItemReserve, ConfigurationKeyEnum.POLAR_TERROR_STAMINA_ITEM_RESERVE_INT);

        comboBoxMappings.put(comboBoxPolarTerrorMarch1Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_1_FLAG_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarch2Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_2_FLAG_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarch3Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_3_FLAG_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarch4Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_4_FLAG_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarch5Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_5_FLAG_STRING);
        comboBoxMappings.put(comboBoxPolarTerrorMarch6Flag, ConfigurationKeyEnum.POLAR_TERROR_MARCH_6_FLAG_STRING);

        comboBoxMappings.put(comboBoxBerserkCryptidMarch1Flag, ConfigurationKeyEnum.RALLY_MARCH_1_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarch2Flag, ConfigurationKeyEnum.RALLY_MARCH_2_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarch3Flag, ConfigurationKeyEnum.RALLY_MARCH_3_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarch4Flag, ConfigurationKeyEnum.RALLY_MARCH_4_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarch5Flag, ConfigurationKeyEnum.RALLY_MARCH_5_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarch6Flag, ConfigurationKeyEnum.RALLY_MARCH_6_FLAG_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMode, ConfigurationKeyEnum.RALLY_MODE_STRING);
        comboBoxMappings.put(comboBoxBerserkCryptidMarches, ConfigurationKeyEnum.RALLY_MARCHES_INT);
        comboBoxMappings.put(comboBoxRallyTarget, ConfigurationKeyEnum.RALLY_TARGET_STRING);

        initializeChangeEvents();

        // Add listener for dynamic march flags visibility (Manual Rally)
        comboBoxBerserkCryptidMarches.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateMarchFlagsVisibility(newVal);
        });

        // Add listener for dynamic march flags visibility (Polar Terror)
        comboBoxPolarTerrorMarches.valueProperty().addListener((obs, oldVal, newVal) -> {
            updatePolarTerrorMarchFlagsVisibility(newVal);
        });

        // Initialize visibility
        updateMarchFlagsVisibility(1);
        updatePolarTerrorMarchFlagsVisibility(1);
        textFieldPolarStaminaItemReserve.disableProperty().bind(checkBoxPolarUseStaminaItems.selectedProperty().not());
        comboBoxPolarTerrorLevel.disableProperty().bind(checkBoxPolarHighestLevel.selectedProperty());
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);

        String storedMode = profile.getConfiguration(ConfigurationKeyEnum.POLAR_TERROR_MODE_STRING);
        if (!PolarTerrorMode.isLegacyValue(storedMode)) {
            return;
        }

        isLoadingProfile = true;
        try {
            comboBoxPolarTerrorMode.setValue(PolarTerrorMode.SPECIAL_REWARDS.getDisplayName());
        } finally {
            isLoadingProfile = false;
        }
    }

    private void updateMarchFlagsVisibility(Integer marches) {
        int count = marches != null ? marches : 1;

        Label[] marchLabels = {
                labelBerserkCryptidMarch1Flag, labelBerserkCryptidMarch2Flag, labelBerserkCryptidMarch3Flag,
                labelBerserkCryptidMarch4Flag, labelBerserkCryptidMarch5Flag, labelBerserkCryptidMarch6Flag
        };

        @SuppressWarnings("unchecked")
        ComboBox<String>[] marchCombos = new ComboBox[] {
                comboBoxBerserkCryptidMarch1Flag, comboBoxBerserkCryptidMarch2Flag, comboBoxBerserkCryptidMarch3Flag,
                comboBoxBerserkCryptidMarch4Flag, comboBoxBerserkCryptidMarch5Flag, comboBoxBerserkCryptidMarch6Flag
        };

        for (int i = 0; i < 6; i++) {
            boolean visible = i < count;
            marchLabels[i].setVisible(visible);
            marchLabels[i].setManaged(visible);
            marchCombos[i].setVisible(visible);
            marchCombos[i].setManaged(visible);
        }
    }

    private void updatePolarTerrorMarchFlagsVisibility(Integer marches) {
        int count = marches != null ? marches : 1;

        Label[] marchLabels = {
                labelPolarTerrorMarch1Flag, labelPolarTerrorMarch2Flag, labelPolarTerrorMarch3Flag,
                labelPolarTerrorMarch4Flag, labelPolarTerrorMarch5Flag, labelPolarTerrorMarch6Flag
        };

        @SuppressWarnings("unchecked")
        ComboBox<String>[] marchCombos = new ComboBox[] {
                comboBoxPolarTerrorMarch1Flag, comboBoxPolarTerrorMarch2Flag, comboBoxPolarTerrorMarch3Flag,
                comboBoxPolarTerrorMarch4Flag, comboBoxPolarTerrorMarch5Flag, comboBoxPolarTerrorMarch6Flag
        };

        for (int i = 0; i < 6; i++) {
            boolean visible = i < count;
            marchLabels[i].setVisible(visible);
            marchLabels[i].setManaged(visible);
            marchCombos[i].setVisible(visible);
            marchCombos[i].setManaged(visible);
        }
    }

    /**
     * Creates a ListCell that shows a 30×30 thumbnail of the target PNG
     * alongside its human-readable display name.
     */
    private ListCell<String> buildTargetCell() {
        return new ListCell<>() {
            private final ImageView imageView = new ImageView();

            {
                imageView.setFitWidth(30);
                imageView.setFitHeight(30);
                imageView.setPreserveRatio(true);
            }

            @Override
            protected void updateItem(String key, boolean empty) {
                super.updateItem(key, empty);
                if (empty || key == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    // Resolve display name
                    String displayName = RALLY_TARGETS.entrySet().stream()
                            .filter(e -> e.getValue().equals(key))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(key);
                    setText(displayName);

                    // Load thumbnail from classpath
                    String resourcePath = "/templates/rally/targets/" + key + ".png";
                    InputStream is = getClass().getResourceAsStream(resourcePath);
                    if (is != null) {
                        imageView.setImage(new Image(is, 30, 30, true, true));
                        setGraphic(imageView);
                    } else {
                        imageView.setImage(null);
                        setGraphic(null);
                    }
                }
            }
        };
    }
}
