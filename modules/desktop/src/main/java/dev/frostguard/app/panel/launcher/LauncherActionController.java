package dev.frostguard.app.panel.launcher;

import dev.frostguard.api.domain.BotStateData;
import dev.frostguard.api.domain.QueueStateData;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.panel.profile.ProfileManagerLayoutController;
import dev.frostguard.engine.listener.BotStateListener;
import dev.frostguard.engine.listener.QueueStateListener;
import dev.frostguard.engine.service.ScheduleService;
import javafx.application.Platform;
import javafx.collections.ObservableList;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public class LauncherActionController implements BotStateListener, QueueStateListener {

    private final LauncherLayoutController layoutController;
    private final ScheduleService scheduleService;
    private ProfileManagerLayoutController profileManagerLayoutController;

    public LauncherActionController(LauncherLayoutController launcherLayoutController) {
        layoutController = Objects.requireNonNull(launcherLayoutController, "launcherLayoutController");
        scheduleService = ScheduleService.obtain();
        scheduleService.addEngineObserver(this);
        scheduleService.addQueueObserver(this);
    }

    public void setProfileManagerController(ProfileManagerLayoutController profileManagerLayoutController) {
        this.profileManagerLayoutController = profileManagerLayoutController;
    }

    public void startBot() {
        scheduleService.launchEngine();
    }

    public void stopBot() {
        // Changed by pernerch | Date: 2026-07-04 | Why: route GUI stop through dedicated GUI stop-behavior policy.
        scheduleService.haltEngineFromGui();
    }

    public void pauseAllQueues() {
        scheduleService.suspendEngine();
    }

    public void resumeAllQueues() {
        scheduleService.resumeEngine();
    }

    public void pauseQueue(ProfileAux profile) {
        withProfile(profile, selectedProfile -> scheduleService.suspendAccountQueue(selectedProfile.getId()));
    }

    public void resumeQueue(ProfileAux profile) {
        withProfile(profile, selectedProfile -> scheduleService.resumeAccountQueue(selectedProfile.getId()));
    }

    public void captureScreenshots() {
        // Hook retained for toolbar parity; capture work belongs in the emulator panel.
    }

    public void loadProfilesIntoComboBox() {
        withProfileManager(manager -> {
            ProfileRoster roster = ProfileRoster.from(manager);
            if (roster.isEmpty()) {
                manager.loadProfiles();
                Platform.runLater(this::updateProfileComboBox);
                return;
            }
            applyRoster(roster, SelectionPolicy.SELECT_FIRST_WHEN_EMPTY);
        });
    }

    public void updateProfileComboBox() {
        withProfileManager(manager -> {
            ProfileRoster roster = ProfileRoster.from(manager);
            if (!roster.isEmpty()) {
                applyRoster(roster, SelectionPolicy.PRESERVE_ONLY);
            }
        });
    }

    public void selectProfile(ProfileAux selectedProfile) {
        selectProfile(selectedProfile, true);
    }

    public void selectProfile(ProfileAux selectedProfile, boolean userTriggered) {
        withProfile(selectedProfile, profile -> {
            if (profileManagerLayoutController != null) {
                profileManagerLayoutController.setLoadedProfileId(profile.getId());
                profileManagerLayoutController.notifyProfileLoadListeners(profile);
            }
            layoutController.onProfileLoad(profile);
        });
    }

    public void refreshProfileComboBox() {
        Platform.runLater(this::updateProfileComboBox);
    }

    @Override
    public void onEngineStateTransition(BotStateData botState) {
        Platform.runLater(() -> layoutController.onEngineStateTransition(botState));
    }

    @Override
    public void onQueueStateChanged(QueueStateData queueState) {
        Platform.runLater(() -> layoutController.onQueueStateChanged(queueState));
    }

    private void applyRoster(ProfileRoster roster, SelectionPolicy policy) {
        ProfileAux currentSelection = layoutController.getSelectedProfile();
        layoutController.updateComboBoxItems(roster.profiles());

        Optional<ProfileAux> retained = roster.findById(currentSelection);
        if (retained.isPresent()) {
            layoutController.selectProfileInComboBox(retained.get());
            return;
        }

        boolean shouldSelectFallback = policy == SelectionPolicy.SELECT_FIRST_WHEN_EMPTY || currentSelection != null;
        if (shouldSelectFallback) {
            ProfileAux firstProfile = roster.firstProfile();
            layoutController.selectProfileInComboBox(firstProfile);
            selectProfile(firstProfile, false);
        }
    }

    private void withProfileManager(Consumer<ProfileManagerLayoutController> action) {
        if (profileManagerLayoutController != null) {
            action.accept(profileManagerLayoutController);
        }
    }

    private void withProfile(ProfileAux profile, Consumer<ProfileAux> action) {
        if (profile != null) {
            action.accept(profile);
        }
    }

    private enum SelectionPolicy {
        PRESERVE_ONLY,
        SELECT_FIRST_WHEN_EMPTY
    }

    private record ProfileRoster(ObservableList<ProfileAux> profiles) {
        private static ProfileRoster from(ProfileManagerLayoutController manager) {
            return new ProfileRoster(manager.getProfiles());
        }

        private boolean isEmpty() {
            return profiles == null || profiles.isEmpty();
        }

        private ProfileAux firstProfile() {
            return profiles.getFirst();
        }

        private Optional<ProfileAux> findById(ProfileAux selectedProfile) {
            if (selectedProfile == null || selectedProfile.getId() == null) {
                return Optional.empty();
            }

            return profiles.stream()
                .filter(profile -> Objects.equals(profile.getId(), selectedProfile.getId()))
                .findFirst();
        }
    }
}
