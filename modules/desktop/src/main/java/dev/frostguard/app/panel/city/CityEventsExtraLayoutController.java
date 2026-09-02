package dev.frostguard.app.panel.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.AbstractProfileController;
import dev.frostguard.app.shared.SettingValidators;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.tasks.combat.ArenaRoutine;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;

import java.time.LocalTime;
import java.util.List;
import java.util.function.UnaryOperator;

public class CityEventsExtraLayoutController extends AbstractProfileController {

    private static final List<Integer> EXTRA_ATTEMPT_OPTIONS = List.of(0, 1, 2, 3, 4, 5);
    private static final List<String> SERVER_POLICY_OPTIONS = List.of(
        "Any server",
        "Prefer profile server",
        "Avoid profile server",
        "Never attack profile server"
    );
    private static final List<String> ALLIANCE_POLICY_OPTIONS = List.of(
        "Any alliance",
        "Avoid profile alliance",
        "Never attack profile alliance"
    );
    // The same input rules the profile editor enforces, so a value typed here
    // cannot differ in shape from one typed there.
    private static final UnaryOperator<TextFormatter.Change> DIGITS_ONLY = change ->
        change.getControlNewText().matches("\\d*") ? change : null;
    private static final UnaryOperator<TextFormatter.Change> ALLIANCE_CODE = change ->
        change.getControlNewText().length() <= 3 && change.getControlNewText().matches("[A-Za-z0-9]*") ? change : null;

    @FXML
    private CheckBox checkBoxDailyVipRewards;
    @FXML
    private CheckBox checkBoxBuyMonthlyVip;
    @FXML
    private CheckBox checkBoxStorehouseChest;
    @FXML
    private CheckBox checkBoxDailyLabyrinth;
    @FXML
    private CheckBox checkBoxHeroRecruitment;
    @FXML
    private CheckBox checkBoxTrekSupplies;
    @FXML
    private CheckBox checkBoxTrekAutomation;
    @FXML
    private CheckBox checkBoxArena;
    @FXML
    private CheckBox checkBoxArenaRefreshWithGems;
    @FXML
    private CheckBox checkBoxArenaAttackQuickDeploy;
    @FXML
    private TextField textFieldArenaActivationHour;
    @FXML
    private TextField textFieldArenaProfileAlliance;
    @FXML
    private TextField textFieldArenaProfileServer;
    @FXML
    private Label labelArenaTargetingHint;
    @FXML
    private Label labelArenaExtraAttemptsHelp;
    @FXML
    private Label labelArenaListRefreshHelp;
    @FXML
    private Label labelArenaAlliancePolicyHelp;
    @FXML
    private Label labelArenaServerPolicyHelp;
    @FXML
    private ComboBox<Integer> comboBoxArenaExtraAttempts;
    @FXML
    private ComboBox<String> comboBoxArenaAlliancePolicy;
    @FXML
    private ComboBox<String> comboBoxArenaServerPolicy;
    @FXML
    private Label labelDateTimeError;

    private ProfileAux currentProfile;

    @FXML
    private void initialize() {
        cityRoutineToggles().forEach(toggle -> checkBoxMappings.put(toggle.control(), toggle.configKey()));
        comboBoxArenaExtraAttempts.getItems().setAll(EXTRA_ATTEMPT_OPTIONS);
        comboBoxArenaAlliancePolicy.getItems().setAll(ALLIANCE_POLICY_OPTIONS);
        comboBoxArenaServerPolicy.getItems().setAll(SERVER_POLICY_OPTIONS);
        comboBoxMappings.put(comboBoxArenaExtraAttempts, ConfigurationKeyEnum.ARENA_TASK_EXTRA_ATTEMPTS_INT);
        comboBoxMappings.put(comboBoxArenaAlliancePolicy, ConfigurationKeyEnum.ARENA_TASK_ALLIANCE_POLICY_STRING);
        comboBoxMappings.put(comboBoxArenaServerPolicy, ConfigurationKeyEnum.ARENA_TASK_SERVER_POLICY_STRING);

        new ArenaSection().install();
        new ArenaTimeField().install();
        initializeChangeEvents();
    }

    private List<ToggleBinding> cityRoutineToggles() {
        return List.of(
            new ToggleBinding(checkBoxDailyVipRewards, ConfigurationKeyEnum.BOOL_VIP_POINTS),
            new ToggleBinding(checkBoxBuyMonthlyVip, ConfigurationKeyEnum.VIP_MONTHLY_BUY_BOOL),
            new ToggleBinding(checkBoxStorehouseChest, ConfigurationKeyEnum.STOREHOUSE_CHEST_BOOL),
            new ToggleBinding(checkBoxDailyLabyrinth, ConfigurationKeyEnum.DAILY_LABYRINTH_BOOL),
            new ToggleBinding(checkBoxHeroRecruitment, ConfigurationKeyEnum.BOOL_HERO_RECRUITMENT),
            new ToggleBinding(checkBoxTrekSupplies, ConfigurationKeyEnum.TUNDRA_TREK_SUPPLIES_BOOL),
            new ToggleBinding(checkBoxTrekAutomation, ConfigurationKeyEnum.TUNDRA_TREK_AUTOMATION_BOOL),
            new ToggleBinding(checkBoxArena, ConfigurationKeyEnum.ARENA_TASK_BOOL),
            new ToggleBinding(checkBoxArenaAttackQuickDeploy, ConfigurationKeyEnum.ARENA_TASK_ATTACK_QUICK_DEPLOY_BOOL),
            new ToggleBinding(checkBoxArenaRefreshWithGems, ConfigurationKeyEnum.ARENA_TASK_REFRESH_WITH_GEMS_BOOL)
        );
    }

    private void disableWhenArenaOff(Node node) {
        node.disableProperty().bind(checkBoxArena.selectedProperty().not());
    }

    @Override
    public void onProfileLoad(ProfileAux profile) {
        super.onProfileLoad(profile);
        isLoadingProfile = true;
        try {
            this.currentProfile = profile;
            String alliance = orBlank(profile.getCharacterAllianceCode());
            String server = orBlank(profile.getCharacterServer());
            textFieldArenaProfileAlliance.setText(alliance);
            textFieldArenaProfileServer.setText(server);
            if (alliance.isBlank()) {
                comboBoxArenaAlliancePolicy.setValue("Any alliance");
            }
            if (server.isBlank()) {
                comboBoxArenaServerPolicy.setValue("Any server");
            }
            refreshArenaTargetingHint();
            refreshPolicyHelp();
        } finally {
            isLoadingProfile = false;
        }
    }

    /**
     * What the selected filter actually does to a scanned opponent.
     *
     * <p>Every option here reads as a preference, but two of them are hard
     * skips and the rest only reorder a list the routine still attacks all of.
     * "Avoid" in particular still attacks your own alliance when nothing else
     * is eligible, which is the opposite of what the word suggests to someone
     * turning it on to protect teammates. The wording below says which opponents
     * are removed outright, and names the unreadable case, because a strict
     * filter drops rows the OCR could not resolve and that is what empties a
     * list and leaves attempts unused.</p>
     */
    private String describeAlliancePolicy(String policy, String alliance) {
        String tag = alliance.isBlank() ? "your alliance" : alliance;
        if ("Never attack profile alliance".equals(policy)) {
            return "Skips every " + tag + " member outright, and any opponent whose tag cannot be read.";
        }
        if ("Avoid profile alliance".equals(policy)) {
            return "Ranks " + tag + " members last. Still attacks them when no one else is eligible.";
        }
        return "No alliance check. " + tag + " members are attacked like anyone else.";
    }

    private String describeServerPolicy(String policy, String server) {
        String state = server.isBlank() ? "your state" : "state " + server;
        if ("Never attack profile server".equals(policy)) {
            return "Skips everyone in " + state + " outright, plus any row whose state cannot be read"
                + " -- including the compact row layout, which shows no state at all.";
        }
        if ("Avoid profile server".equals(policy)) {
            return "Ranks " + state + " last. Still attacks it when no one else is eligible.";
        }
        if ("Prefer profile server".equals(policy)) {
            return "Ranks " + state + " first, so points stay inside your own state.";
        }
        return "No state check. Opponents in " + state + " are attacked like anyone else.";
    }

    /**
     * What the attempt and refresh settings cost, in the units the game charges.
     *
     * <p>Both are budgets rather than switches, and neither stated its size
     * anywhere: extra attempts are bought with gems on a rising scale, and a run
     * gets a fixed number of free list refreshes before any gem is spent. Read
     * from the routine that spends them rather than restated here, so the screen
     * cannot quote a price the run does not charge.</p>
     */
    private String describeExtraAttempts(Integer attempts) {
        int[] prices = ArenaRoutine.extraAttemptGemPrices();
        int wanted = attempts == null ? 0 : Math.min(attempts, prices.length);
        if (wanted <= 0) {
            return "Off. Only the free daily attempts are used and no gems are spent on attempts.";
        }
        StringBuilder breakdown = new StringBuilder();
        int total = 0;
        for (int i = 0; i < wanted; i++) {
            total += prices[i];
            breakdown.append(i == 0 ? "" : " + ").append(prices[i]);
        }
        String sum = wanted == 1
            ? total + " gems"
            : breakdown + " = " + total + " gems";
        return "Buys " + wanted + (wanted == 1 ? " attempt" : " attempts")
            + " once the free ones are gone, at a rising price: " + sum
            + ". Bought only when an eligible opponent is actually on the list.";
    }

    private String describeListRefresh(boolean paidAllowed) {
        String free = "Every run gets " + ArenaRoutine.maxFreeListRefreshes()
            + " free list refreshes, used first, whenever no opponent on the list is eligible.";
        if (paidAllowed) {
            return free + " After those, up to " + ArenaRoutine.maxPaidListRefreshes()
                + " more are bought with gems at the game's price.";
        }
        return free + " After those the run stops with its remaining attempts unused."
            + " Tick to allow up to " + ArenaRoutine.maxPaidListRefreshes()
            + " more, paid for in gems.";
    }

    private void refreshPolicyHelp() {
        labelArenaAlliancePolicyHelp.setText(describeAlliancePolicy(
            comboBoxArenaAlliancePolicy.getValue(),
            orBlank(textFieldArenaProfileAlliance.getText())));
        labelArenaServerPolicyHelp.setText(describeServerPolicy(
            comboBoxArenaServerPolicy.getValue(),
            orBlank(textFieldArenaProfileServer.getText())));
        labelArenaExtraAttemptsHelp.setText(describeExtraAttempts(comboBoxArenaExtraAttempts.getValue()));
        labelArenaListRefreshHelp.setText(describeListRefresh(checkBoxArenaRefreshWithGems.isSelected()));
    }

    private static String orBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private void refreshArenaTargetingHint() {
        labelArenaTargetingHint.setText(buildArenaTargetingHint(
            orBlank(textFieldArenaProfileAlliance.getText()),
            orBlank(textFieldArenaProfileServer.getText())));
    }

    private void installPolicyHelp() {
        // Driven by both the dropdown and the field above it, so the description
        // names the actual tag and state rather than a placeholder.
        comboBoxArenaAlliancePolicy.valueProperty().addListener((o, before, after) -> refreshPolicyHelp());
        comboBoxArenaServerPolicy.valueProperty().addListener((o, before, after) -> refreshPolicyHelp());
        textFieldArenaProfileAlliance.textProperty().addListener((o, before, after) -> refreshPolicyHelp());
        textFieldArenaProfileServer.textProperty().addListener((o, before, after) -> refreshPolicyHelp());
        comboBoxArenaExtraAttempts.valueProperty().addListener((o, before, after) -> refreshPolicyHelp());
        checkBoxArenaRefreshWithGems.selectedProperty().addListener((o, before, after) -> refreshPolicyHelp());
        refreshPolicyHelp();
    }

    private String buildArenaTargetingHint(String alliance, String server) {
        if (!alliance.isBlank() && !server.isBlank()) {
            return "Target filters use this profile's Alliance and Server, saved to the profile as you type them here.";
        }
        if (alliance.isBlank() && server.isBlank()) {
            return "Enter this profile's Alliance and Server below to enable the target filters.";
        }
        if (alliance.isBlank()) {
            return "Enter this profile's Alliance below to protect alliance members.";
        }
        return "Enter this profile's Server below to enable server preferences.";
    }

    /**
     * Writes Alliance and Server straight back to the profile from this tab.
     *
     * <p>Both live on the profile rather than in task config, so they were
     * reachable only through Profile &gt; Character Information -- while the two
     * dropdowns they gate sit here, greyed out, telling you to go there. Editing
     * them in place removes that round trip. Committed on Enter or focus loss,
     * the same points every other field on this tab commits at.</p>
     */
    private void commitProfileCharacterInfo() {
        if (currentProfile == null || isLoadingProfile) {
            return;
        }
        String storedAlliance = orBlank(currentProfile.getCharacterAllianceCode());
        String storedServer = orBlank(currentProfile.getCharacterServer());
        // Uppercased to match the profile editor, which stores tags in caps.
        String alliance = orBlank(textFieldArenaProfileAlliance.getText()).toUpperCase();
        String server = orBlank(textFieldArenaProfileServer.getText());

        if (alliance.equals(storedAlliance) && server.equals(storedServer)) {
            return;
        }

        currentProfile.setCharacterAllianceCode(alliance.isBlank() ? null : alliance);
        currentProfile.setCharacterServer(server.isBlank() ? null : server);

        isLoadingProfile = true;
        try {
            if (persistCurrentProfile()) {
                textFieldArenaProfileAlliance.setText(alliance);
                textFieldArenaProfileServer.setText(server);
            } else {
                // Put the stored values back rather than leave the fields showing
                // something the next arena run would not actually use.
                currentProfile.setCharacterAllianceCode(storedAlliance.isBlank() ? null : storedAlliance);
                currentProfile.setCharacterServer(storedServer.isBlank() ? null : storedServer);
                textFieldArenaProfileAlliance.setText(storedAlliance);
                textFieldArenaProfileServer.setText(storedServer);
            }
        } finally {
            isLoadingProfile = false;
        }
        refreshArenaTargetingHint();
    }

    private boolean persistCurrentProfile() {
        if (currentProfile.getId() == null) {
            return false;
        }
        AccountDescriptor descriptor = new AccountDescriptor(
            currentProfile.getId(),
            currentProfile.getName(),
            currentProfile.getEmulatorNumber(),
            currentProfile.isEnabled(),
            currentProfile.getPriority(),
            currentProfile.getReconnectionTime(),
            currentProfile.getCharacterId(),
            currentProfile.getCharacterName(),
            currentProfile.getCharacterAllianceCode(),
            currentProfile.getCharacterServer()
        );
        currentProfile.getConfigs().forEach(config ->
            descriptor.getConfigs().add(new ConfigData(currentProfile.getId(), config.getName(), config.getValue())));
        descriptor.setTags(currentProfile.getTags());
        // persistAccount broadcasts the change itself, so the profile editor and
        // any other open panel pick the new values up without a reload.
        return ProfileService.obtain().persistAccount(descriptor);
    }

    private record ToggleBinding(CheckBox control, ConfigurationKeyEnum configKey) {
    }

    private final class ArenaSection {
        private void install() {
            List.of(checkBoxArenaAttackQuickDeploy, checkBoxArenaRefreshWithGems,
                    textFieldArenaActivationHour, comboBoxArenaExtraAttempts,
                    textFieldArenaProfileAlliance, textFieldArenaProfileServer)
                .forEach(CityEventsExtraLayoutController.this::disableWhenArenaOff);

            textFieldArenaProfileAlliance.setTextFormatter(new TextFormatter<>(ALLIANCE_CODE));
            textFieldArenaProfileServer.setTextFormatter(new TextFormatter<>(DIGITS_ONLY));
            installProfileFieldCommit(textFieldArenaProfileAlliance);
            installProfileFieldCommit(textFieldArenaProfileServer);

            // The dropdowns follow what is typed above, so they ungrey the moment
            // a value is entered rather than waiting for the profile to reload.
            comboBoxArenaAlliancePolicy.disableProperty().bind(
                checkBoxArena.selectedProperty().not()
                    .or(Bindings.createBooleanBinding(
                        () -> orBlank(textFieldArenaProfileAlliance.getText()).isBlank(),
                        textFieldArenaProfileAlliance.textProperty())));
            comboBoxArenaServerPolicy.disableProperty().bind(
                checkBoxArena.selectedProperty().not()
                    .or(Bindings.createBooleanBinding(
                        () -> orBlank(textFieldArenaProfileServer.getText()).isBlank(),
                        textFieldArenaProfileServer.textProperty())));

            installPolicyHelp();

            textFieldArenaProfileAlliance.setTooltip(new Tooltip("This profile's alliance tag, saved to the profile. Enables the alliance filter."));
            textFieldArenaProfileServer.setTooltip(new Tooltip("This profile's server (state) number, saved to the profile. Enables the server policy."));
            comboBoxArenaAlliancePolicy.setTooltip(new Tooltip("Enter this profile's Alliance above to enable alliance preferences."));
            comboBoxArenaServerPolicy.setTooltip(new Tooltip("Enter this profile's Server above to enable server preferences."));
        }

        private void installProfileFieldCommit(TextField field) {
            field.setOnAction(event -> commitProfileCharacterInfo());
            field.focusedProperty().addListener((observable, hadFocus, hasFocus) -> {
                if (hadFocus && !hasFocus) {
                    commitProfileCharacterInfo();
                }
            });
        }
    }

    private final class ArenaTimeField {
        private void install() {
            textFieldArenaActivationHour.setPromptText("HH:mm");
            textFieldArenaActivationHour.setTooltip(new Tooltip("Arena runs before 23:56 UTC; example: 19:30"));
            registerTimeTextField(
                textFieldArenaActivationHour,
                labelDateTimeError,
                ConfigurationKeyEnum.ARENA_TASK_ACTIVATION_TIME_STRING,
                SettingValidators.localTimeNoLaterThan("Arena activation time", LocalTime.of(23, 55)));
        }
    }
}
