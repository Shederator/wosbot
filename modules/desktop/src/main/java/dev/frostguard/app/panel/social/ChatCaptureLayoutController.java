package dev.frostguard.app.panel.social;

import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.api.configs.ConfigurationKeyEnum;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import java.util.Objects;
import javafx.scene.control.Alert;
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
    private CheckBox checkBoxChatFilterNoise;
    @FXML
    private ComboBox<Integer> comboBoxChatFrequency;
    @FXML
    private ComboBox<Integer> comboBoxChatFrameCache;
    @FXML
    private ComboBox<Integer> comboBoxChatViewMessages;
    @FXML
    private ComboBox<String> comboBoxChatTimezone;

    /** Keeps every screen for ever. Stored as a figure no real budget would reach. */
    private static final int UNLIMITED_FRAMES = -1;

    @FXML
    private void initialize() {
        checkBoxMappings.put(checkBoxEnableChatCapture, ConfigurationKeyEnum.CHAT_CAPTURE_ENABLED_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeWorld, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_WORLD_BOOL);
        checkBoxMappings.put(checkBoxChatIncludeAlliance, ConfigurationKeyEnum.CHAT_CAPTURE_INCLUDE_ALLIANCE_BOOL);
        checkBoxMappings.put(checkBoxChatFilterNoise, ConfigurationKeyEnum.CHAT_CAPTURE_FILTER_NOISE_BOOL);

        comboBoxChatFrequency.getItems().addAll(15, 30, 45, 60, 90, 120);
        comboBoxChatFrequency.setCellFactory(lv -> minutesCell());
        comboBoxChatFrequency.setButtonCell(minutesCell());
        comboBoxMappings.put(comboBoxChatFrequency, ConfigurationKeyEnum.CHAT_CAPTURE_FREQUENCY_MINUTES_INT);

        // Off first, and off by default. This writes pictures of the profile's chat to disk, which
        // is a thing somebody should choose rather than find out about.
        comboBoxChatFrameCache.getItems().addAll(0, 100, 250, 500, 1000, UNLIMITED_FRAMES);
        comboBoxChatFrameCache.setCellFactory(lv -> megabytesCell());
        comboBoxChatFrameCache.setButtonCell(megabytesCell());
        comboBoxMappings.put(comboBoxChatFrameCache, ConfigurationKeyEnum.CHAT_FRAME_CACHE_MB_INT);
        // Said once, when it is picked, and not again. Every other figure here has a ceiling; this
        // one does not, and the thing being written is pictures of a person's chat on a schedule
        // that never stops. Roughly 400 KB a screen, a few hundred screens a pass, every pass --
        // it reaches tens of gigabytes in a month and nothing prunes it.
        comboBoxChatFrameCache.valueProperty().addListener((obs, was, now) -> {
            if (now == null || now != UNLIMITED_FRAMES || Objects.equals(was, now)) {
                return;
            }
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("Keep every screen");
            warn.setHeaderText("Nothing will ever be deleted.");
            warn.setContentText("Screens are about 400 KB each and a pass reads a few hundred of "
                    + "them, so this grows by roughly a gigabyte a day and keeps going. They are "
                    + "pictures of your chat, and the only thing that will ever remove them is you."
                    + "\\n\\nSet a figure instead unless you have a reason to keep the lot.");
            warn.showAndWait();
        });

        // Every zone the JDK knows, so somebody in a place this list would not have thought of is
        // not stuck. The machine's own comes first and is the default, because that is what almost
        // everybody wants and nobody should have to go looking for it.
        comboBoxChatTimezone.getItems().add(ChatClock.SYSTEM);
        comboBoxChatTimezone.getItems().addAll(java.time.ZoneId.getAvailableZoneIds().stream()
                .filter(id -> id.contains("/") || "UTC".equals(id))
                .sorted()
                .toList());
        comboBoxChatTimezone.setCellFactory(lv -> timezoneCell());
        comboBoxChatTimezone.setButtonCell(timezoneCell());
        comboBoxChatTimezone.setVisibleRowCount(14);
        comboBoxMappings.put(comboBoxChatTimezone,
                ConfigurationKeyEnum.CHAT_DISPLAY_TIMEZONE_STRING);
        // Applied as it is picked rather than only on the next profile load, so switching it and
        // stepping over to the Chat tab shows the new times immediately.
        comboBoxChatTimezone.valueProperty().addListener((obs, was, now) -> ChatClock.useSetting(now));

        // A limit on what the panel draws, not on what was captured. Four hundred is what the tab
        // used before this was offered, so an existing profile opens looking the same.
        comboBoxChatViewMessages.getItems().addAll(100, 200, 400, 800, 2000);
        comboBoxChatViewMessages.setCellFactory(lv -> messageCountCell());
        comboBoxChatViewMessages.setButtonCell(messageCountCell());
        comboBoxMappings.put(comboBoxChatViewMessages,
                ConfigurationKeyEnum.CHAT_VIEW_MESSAGES_INT);

        initializeChangeEvents();
    }

    /**
     * Names a zone the way somebody looking for it would.
     *
     * <p>"Europe/Madrid" with its current offset beside it, because the offset is what actually
     * answers the question being asked -- how far ahead of me is this -- and the raw identifier
     * alone makes the reader do arithmetic against a table they do not have.
     */
    private ListCell<String> timezoneCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else if (ChatClock.SYSTEM.equals(item)) {
                    setText("Same as this computer (" + java.time.ZoneId.systemDefault() + ")");
                } else {
                    setText(item + "   " + offsetOf(item));
                }
            }
        };
    }

    private static String offsetOf(String zoneId) {
        try {
            String offset = java.time.ZonedDateTime.now(java.time.ZoneId.of(zoneId))
                    .getOffset().getId();
            return "Z".equals(offset) ? "UTC+00:00" : "UTC" + offset;
        } catch (RuntimeException notAZone) {
            return "";
        }
    }

    /** Reads a count as messages, and the largest as the warning it is. */
    private ListCell<Integer> messageCountCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item + " messages" + (item >= 2000 ? " (slower to open)" : ""));
                }
            }
        };
    }

    /** Reads the budget as a size, and zero as what it means rather than as "0 MB". */
    private ListCell<Integer> megabytesCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item == 0 ? "Don't keep any"
                            : item == UNLIMITED_FRAMES ? "INSANE - never delete"
                            : item + " MB (about " + item * 5 / 2 + " screens)");
                }
            }
        };
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
}
