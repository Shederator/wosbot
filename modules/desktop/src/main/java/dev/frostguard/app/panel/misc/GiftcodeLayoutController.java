package dev.frostguard.app.panel.misc;

import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.frostguard.app.panel.misc.GiftCodeAutomationService.GiftCodeProfile;
import dev.frostguard.app.panel.misc.GiftCodeAutomationService.GiftCodeState;
import dev.frostguard.app.panel.misc.GiftCodeClient.GiftCodeEntry;
import dev.frostguard.app.panel.misc.GiftCodeStore.GiftCodeRecipient;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class GiftcodeLayoutController {

    private final GiftCodeAutomationService automation = GiftCodeAutomationService.getInstance();
    private final Consumer<GiftCodeState> stateListener = state -> Platform.runLater(() -> render(state));

    @FXML private Button buttonFetch;
    @FXML private Button buttonCopyAll;
    @FXML private Button buttonClaimAll;
    @FXML private Label labelStatus;
    @FXML private Label labelClaimStatus;
    @FXML private Label labelAccountHint;
    @FXML private VBox giftcodeListContainer;
    @FXML private VBox recipientListContainer;
    @FXML private ComboBox<GiftCodeProfile> comboRecipientProfile;
    @FXML private TextField textfieldRecipientAlias;
    @FXML private TextField textfieldRecipientId;
    @FXML private TextField textfieldRecipientRegion;

    @FXML
    private void initialize() {
        textfieldRecipientId.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.matches("\\d*")) {
                textfieldRecipientId.setText(oldValue);
            }
        });
        textfieldRecipientRegion.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.matches("\\d*")) {
                textfieldRecipientRegion.setText(oldValue);
            }
        });
        automation.start();
        automation.addListener(stateListener);
        GiftCodeState initial = automation.snapshot();
        render(initial);
        if (initial.activeCodes().isEmpty() && !initial.busy()) {
            automation.fetchNow();
        }
    }

    @FXML
    private void handleFetch() {
        automation.fetchNow();
    }

    @FXML
    private void handleCopyAll() {
        GiftCodeState state = automation.snapshot();
        if (state.activeCodes().isEmpty()) {
            return;
        }
        String allCodes = state.activeCodes().stream()
                .map(GiftCodeEntry::code)
                .collect(Collectors.joining("\n"));
        copyToClipboard(allCodes);
        showCopiedFeedback(buttonCopyAll, "Copy All Active", "Copied All!");
    }

    @FXML
    private void handleClaimAll() {
        GiftCodeState state = automation.snapshot();
        if (state.manualClaimRunning()) {
            automation.stopManualClaim();
        } else {
            automation.claimAllUnchecked();
        }
    }

    @FXML
    private void handleAddRecipient() {
        GiftCodeProfile owner = comboRecipientProfile.getValue();
        if (owner != null && automation.addExtraRecipient(owner.profileId(),
                textfieldRecipientId.getText(), textfieldRecipientAlias.getText(),
                textfieldRecipientRegion.getText())) {
            textfieldRecipientId.clear();
            textfieldRecipientAlias.clear();
            textfieldRecipientRegion.clear();
        }
    }

    private void render(GiftCodeState state) {
        labelStatus.setText(state.status());
        buttonFetch.setDisable(state.busy());
        buttonCopyAll.setDisable(state.busy() || state.activeCodes().isEmpty());
        buttonCopyAll.setVisible(!state.activeCodes().isEmpty());
        buttonCopyAll.setManaged(!state.activeCodes().isEmpty());
        buttonClaimAll.setText(state.manualClaimRunning()
                ? state.manualStopRequested() ? "Stopping Claim..." : "Stop Claiming"
                : "Claim All for All Recipients");
        buttonClaimAll.setDisable((state.busy() && !state.manualClaimRunning())
                || state.manualStopRequested() || state.recipients().stream()
                        .noneMatch(recipient -> recipient.playerId().matches("\\d+")
                                && recipient.region().matches("\\d+")));
        labelClaimStatus.setText(state.manualClaimStatus());
        labelClaimStatus.setVisible(!state.manualClaimStatus().isBlank());
        labelClaimStatus.setManaged(!state.manualClaimStatus().isBlank());

        boolean hasCompleteProfile = state.profiles().stream().anyMatch(profile ->
                profile.playerId().matches("\\d+") && profile.region().matches("\\d+"));
        labelAccountHint.setVisible(!hasCompleteProfile);
        labelAccountHint.setManaged(!hasCompleteProfile);
        updateProfileSelector(state);
        populateGiftCodeCards(state);
        populateRecipients(state);
    }

    private void updateProfileSelector(GiftCodeState state) {
        Long selectedId = comboRecipientProfile.getValue() == null
                ? null : comboRecipientProfile.getValue().profileId();
        comboRecipientProfile.getItems().setAll(state.profiles());
        state.profiles().stream()
                .filter(profile -> profile.profileId().equals(selectedId))
                .findFirst()
                .ifPresentOrElse(comboRecipientProfile::setValue, () -> {
                    if (!state.profiles().isEmpty()) {
                        comboRecipientProfile.getSelectionModel().selectFirst();
                    }
                });
        comboRecipientProfile.setDisable(state.busy() || state.profiles().isEmpty());
    }

    private void populateGiftCodeCards(GiftCodeState state) {
        giftcodeListContainer.getChildren().clear();
        if (state.activeCodes().isEmpty()) {
            Label empty = new Label(state.busy() ? "Loading active gift codes..." : "No active gift codes loaded.");
            empty.getStyleClass().add("giftcode-placeholder");
            giftcodeListContainer.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < state.activeCodes().size(); i++) {
            giftcodeListContainer.getChildren().add(createGiftCodeRow(state.activeCodes().get(i), i + 1));
        }
    }

    private HBox createGiftCodeRow(GiftCodeEntry entry, int index) {
        Label indexLabel = new Label("#" + index);
        indexLabel.getStyleClass().add("giftcode-index-label");
        Label codeLabel = new Label(entry.code());
        codeLabel.getStyleClass().add("giftcode-code-label");
        Label dateLabel = new Label(entry.displayDate());
        dateLabel.getStyleClass().add("giftcode-date-label");
        VBox codeInfo = new VBox(2, codeLabel, dateLabel);
        codeInfo.setAlignment(Pos.CENTER_LEFT);

        Button copy = new Button("Copy");
        copy.getStyleClass().add("giftcode-copy-btn");
        copy.setOnAction(event -> {
            copyToClipboard(entry.code());
            showCopiedFeedback(copy, "Copy", "Copied!");
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, indexLabel, codeInfo, spacer, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("giftcode-row");
        return row;
    }

    private void populateRecipients(GiftCodeState state) {
        recipientListContainer.getChildren().clear();
        if (state.profiles().isEmpty()) {
            Label empty = new Label("No profiles configured yet.");
            empty.getStyleClass().add("giftcode-placeholder");
            recipientListContainer.getChildren().add(empty);
            return;
        }
        for (GiftCodeProfile profile : state.profiles()) {
            recipientListContainer.getChildren().add(createProfileRow(profile, state.busy()));
            if (profile.recipients().isEmpty()) {
                Label empty = new Label("No Player ID configured for this profile.");
                empty.getStyleClass().addAll("giftcode-placeholder", "giftcode-recipient-child");
                recipientListContainer.getChildren().add(empty);
            } else {
                profile.recipients().forEach(recipient ->
                        recipientListContainer.getChildren().add(createRecipientRow(recipient)));
            }
        }
    }

    private HBox createProfileRow(GiftCodeProfile profile, boolean busy) {
        Label name = new Label(profile.profileName());
        name.getStyleClass().add("giftcode-profile-label");
        String identity = profile.playerId().isBlank() ? "Player ID missing" : "Player ID " + profile.playerId();
        identity += profile.region().isBlank() ? " | Region missing" : " | Region " + profile.region();
        Label id = new Label(identity);
        id.getStyleClass().add("giftcode-recipient-source");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        String autoLabel = profile.autoEnabled()
                ? profile.autoRunning() ? "Auto Claim Running - Stop" : "Stop Auto Claim"
                : profile.autoRunning() ? "Stopping Auto Claim..." : "Auto claim hourly";
        CheckBox auto = new CheckBox(autoLabel);
        auto.setSelected(profile.autoEnabled());
        auto.setDisable(profile.playerId().isBlank() || profile.region().isBlank()
                || (busy && !profile.autoEnabled()));
        if (profile.playerId().isBlank() || profile.region().isBlank()) {
            auto.setTooltip(new Tooltip(
                    "Enter this profile's Player ID and Server/Region in Character Information first."));
        }
        auto.setOnAction(event -> automation.setAutoEnabled(profile.profileId(), auto.isSelected()));
        HBox row = new HBox(10, name, id, spacer, auto);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("giftcode-profile-row");
        return row;
    }

    private HBox createRecipientRow(GiftCodeRecipient recipient) {
        Label identity = new Label(recipient.label());
        identity.getStyleClass().add("giftcode-recipient-label");
        Label source = new Label(recipient.managedProfile() ? "Profile account" : "Additional");
        source.getStyleClass().add("giftcode-recipient-source");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row;
        if (recipient.managedProfile()) {
            row = new HBox(10, identity, spacer, source);
        } else {
            Button remove = new Button("Remove");
            remove.getStyleClass().add("giftcode-copy-btn");
            remove.setOnAction(event -> automation.removeExtraRecipient(
                    recipient.ownerProfileId(), recipient.playerId()));
            row = new HBox(10, identity, spacer, source, remove);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("giftcode-recipient-row", "giftcode-recipient-child");
        return row;
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void showCopiedFeedback(Button button, String originalText, String feedbackText) {
        button.setText(feedbackText);
        button.getStyleClass().add("giftcode-copy-btn-copied");
        PauseTransition pause = new PauseTransition(javafx.util.Duration.millis(1_200));
        pause.setOnFinished(event -> {
            button.setText(originalText);
            button.getStyleClass().remove("giftcode-copy-btn-copied");
        });
        pause.play();
    }
}
