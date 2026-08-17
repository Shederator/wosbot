package dev.frostguard.app.panel.profile;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BulkUpdateDialogController {

    @FXML
    private Label lblTemplateProfile;

    @FXML
    private VBox vboxProfileList;

    @FXML
    private Button btnSelectAll;

    @FXML
    private Button btnDeselectAll;

    @FXML
    private Button btnCancel;

    @FXML
    private Button btnUpdate;

    private final List<CheckBox> profileCheckBoxes = new ArrayList<>();
    private final List<ProfileAux> selectedProfiles = new ArrayList<>();

    private ProfileAux templateProfile;
    private List<ProfileAux> availableProfiles = List.of();
    private Stage dialogStage;
    private boolean updateConfirmed;

    @FXML
    private void initialize() {
        btnUpdate.setDisable(true);
    }

    public void setupDialog(ProfileAux templateProfile, List<ProfileAux> availableProfiles, Stage dialogStage) {
        this.templateProfile = Objects.requireNonNull(templateProfile, "templateProfile");
        this.availableProfiles = availableProfiles == null ? List.of() : availableProfiles;
        this.dialogStage = dialogStage;

        lblTemplateProfile.setText("Using " + templateProfile.getName() + " as the source profile");
        rebuildProfileChoices();
        refreshUpdateButtonState();
    }

    @FXML
    private void handleSelectAll() {
        setAllProfilesSelected(true);
    }

    @FXML
    private void handleDeselectAll() {
        setAllProfilesSelected(false);
    }

    @FXML
    private void handleCancel() {
        updateConfirmed = false;
        closeDialog();
    }

    @FXML
    private void handleUpdate() {
        selectedProfiles.clear();
        selectedProfiles.addAll(collectSelectedProfiles());
        if (selectedProfiles.isEmpty()) {
            showWarning("No profiles selected", "Choose at least one destination profile before updating.");
            return;
        }

        if (confirmedByUser()) {
            updateConfirmed = true;
            closeDialog();
        }
    }

    public boolean isUpdateConfirmed() {
        return updateConfirmed;
    }

    public List<ProfileAux> getSelectedProfiles() {
        return new ArrayList<>(selectedProfiles);
    }

    private void rebuildProfileChoices() {
        profileCheckBoxes.clear();
        vboxProfileList.getChildren().clear();

        availableProfiles.stream()
            .filter(profile -> !Objects.equals(profile.getId(), templateProfile.getId()))
            .map(this::createProfileChoice)
            .forEach(checkBox -> {
                profileCheckBoxes.add(checkBox);
                vboxProfileList.getChildren().add(checkBox);
            });
    }

    private CheckBox createProfileChoice(ProfileAux profile) {
        CheckBox checkBox = new CheckBox("%s | Emulator %s".formatted(profile.getName(), profile.getEmulatorNumber()));
        checkBox.setUserData(profile);
        checkBox.setMaxWidth(Double.MAX_VALUE);
        checkBox.getStyleClass().add("profile-copy-choice");
        checkBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> refreshUpdateButtonState());
        return checkBox;
    }

    private void setAllProfilesSelected(boolean selected) {
        profileCheckBoxes.forEach(checkBox -> checkBox.setSelected(selected));
        refreshUpdateButtonState();
    }

    private List<ProfileAux> collectSelectedProfiles() {
        return profileCheckBoxes.stream()
            .filter(CheckBox::isSelected)
            .map(checkBox -> (ProfileAux) checkBox.getUserData())
            .toList();
    }

    private void refreshUpdateButtonState() {
        boolean hasSelection = profileCheckBoxes.stream().anyMatch(CheckBox::isSelected);
        btnUpdate.setDisable(!hasSelection);
    }

    private boolean confirmedByUser() {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Apply profile settings");
        confirmAlert.setHeaderText("Update " + selectedProfiles.size() + " destination profile(s)");
        confirmAlert.setContentText("Settings from '" + templateProfile.getName() + "' will be copied to the selected profiles.");
        return confirmAlert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void showWarning(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void closeDialog() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}
