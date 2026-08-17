package dev.frostguard.app.panel.social;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

/**
 * Rally has its own dedicated panel because Polar Terror and rally-joining are
 * both combat routines; Chat Capture is not, so it gets its own sidebar entry
 * instead of living inside that one.
 */
public class ChatCaptureLayoutController extends AbstractProfileController {

    @FXML
    private CheckBox checkBoxEnableChatCapture;
    @FXML
    private CheckBox checkBoxChatIncludeWorld;
    @FXML
    private CheckBox checkBoxChatIncludeAlliance;
    @FXML
    private CheckBox checkBoxChatIncludePersonal;
    @FXML
    private CheckBox checkBoxChatFilterNoise;
    @FXML
    private ComboBox<Integer> comboBoxChatFrequency;
    @FXML
    private ComboBox<String> comboBoxChatMode;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxEnableChatCapture, ConfigurationKeyEnum.CHAT_CAPTURE_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeWorld, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_WORLD_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeAlliance, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL);
        checkBoxMappings.put(checkBoxChatIncludePersonal, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_PERSONAL_BOOL);
        checkBoxMappings.put(checkBoxChatFilterNoise, ConfigurationKeyEnum.CHAT_CAPTURE_FILTER_NOISE_BOOL);

        comboBoxChatFrequency.getItems().addAll(15, 30, 45, 60, 90, 120);
        comboBoxChatFrequency.setCellFactory(lv -> minutesCell());
        comboBoxChatFrequency.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatFrequency, ConfigurationKeyEnum.CHAT_CAPTURE_FREQUENCY_MINUTES_INT);

        // Item values are the raw config strings (TRANSCRIPT/SUMMARY) - the
        // binding in AbstractProfileController round-trips the ComboBox value
        // directly against the stored string, so the cell factory below only
        // changes what is displayed, not what gets saved.
        comboBoxChatMode.getItems().addAll("TRANSCRIPT", "SUMMARY");
        comboBoxChatMode.setCellFactory(lv -> modeCell());
        comboBoxChatMode.setButtonCell(modeCell());
        comboBoxMappings.put(comboBoxChatMode, ConfigurationKeyEnum.CHAT_CAPTURE_MODE_STRING);

        initializeChangeEvents();
    }

    private ListCell<Integer> minutesCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item + " min");
            }
        };
    }

    private ListCell<String> modeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText("SUMMARY".equals(item) ? "Daily Summary" : "Live Transcript");
                }
            }
        };
    }
}
