package dev.frostguard.app.panel.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.app.panel.profile.ConfigAux;
import dev.frostguard.app.panel.profile.ProfileAux;
import dev.frostguard.app.shared.JavaFxToolkit;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arena target filters are gated on the profile's Alliance and Server, which are
 * profile fields rather than task settings. They used to be rendered here as two
 * read-only "Not set" labels beside two permanently greyed dropdowns, and the
 * only way to act on them was to leave the screen for Profile &gt; Character
 * Information — a round trip the hint text described but the tab could not
 * perform.
 *
 * <p>These checks hold the fields editable in place and the dropdowns following
 * what is typed, so a state number entered on this tab arms the server policy
 * without a profile reload.</p>
 */
class ArenaTargetFilterUxTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        JavaFxToolkit.start();
    }

    @Test
    void profileAllianceAndServerAreEditableOnTheArenaTab() throws Exception {
        LoadedArenaView view = loadArenaView();

        view.controller.onProfileLoad(arenaProfile("ABC", "4527"));

        assertEquals("ABC", view.alliance.getText());
        assertEquals("4527", view.server.getText());
        assertTrue(view.alliance.isEditable(), "alliance must be typable, not a read-only label");
        assertTrue(view.server.isEditable(), "server must be typable, not a read-only label");
    }

    @Test
    void blankProfileValuesLeaveTheMatchingPolicyDisabled() throws Exception {
        LoadedArenaView view = loadArenaView();

        view.controller.onProfileLoad(arenaProfile("", ""));

        assertTrue(view.alliancePolicy.isDisabled(), "alliance filter must stay disabled without an alliance");
        assertTrue(view.serverPolicy.isDisabled(), "server policy must stay disabled without a server");
        assertEquals("Any alliance", view.alliancePolicy.getValue());
        assertEquals("Any server", view.serverPolicy.getValue());
    }

    @Test
    void typingAServerArmsTheServerPolicyWithoutReloadingTheProfile() throws Exception {
        LoadedArenaView view = loadArenaView();
        view.controller.onProfileLoad(arenaProfile("", ""));
        assertTrue(view.serverPolicy.isDisabled());

        view.server.setText("4527");

        assertFalse(view.serverPolicy.isDisabled(), "server policy must arm as soon as a state number is typed");
        assertTrue(view.alliancePolicy.isDisabled(), "the alliance filter is gated separately and stays disabled");
    }

    @Test
    void clearingTheServerDisarmsTheServerPolicyAgain() throws Exception {
        LoadedArenaView view = loadArenaView();
        view.controller.onProfileLoad(arenaProfile("ABC", "4527"));
        assertFalse(view.serverPolicy.isDisabled());

        view.server.setText("");

        assertTrue(view.serverPolicy.isDisabled());
        assertFalse(view.alliancePolicy.isDisabled(), "clearing the server must not disturb the alliance filter");
    }

    @Test
    void theHintStopsSendingUsersToTheProfileScreenOnceBothValuesAreSet() throws Exception {
        LoadedArenaView view = loadArenaView();

        view.controller.onProfileLoad(arenaProfile("", ""));
        assertTrue(view.hint.getText().contains("below"), "an empty tab must point at the fields on it");

        view.controller.onProfileLoad(arenaProfile("ABC", "4527"));
        assertFalse(view.hint.getText().contains("below"),
                "a configured tab must not keep asking for values that are already set");
    }

    @Test
    void bothPoliciesStayDisabledWhileArenaIsOff() throws Exception {
        LoadedArenaView view = loadArenaView();
        view.controller.onProfileLoad(arenaProfile("ABC", "4527"));
        assertFalse(view.serverPolicy.isDisabled());

        view.arenaEnabled.setSelected(false);

        assertTrue(view.alliancePolicy.isDisabled());
        assertTrue(view.serverPolicy.isDisabled());
        assertTrue(view.alliance.isDisabled(), "the profile fields follow the arena toggle too");
        assertTrue(view.server.isDisabled());
    }

    private static ProfileAux arenaProfile(String alliance, String server) {
        ProfileAux profile = new ProfileAux(1L, "Default", "0", true, 50L, "", 0L,
                "", "", alliance, server);
        profile.setConfigs(List.of(new ConfigAux(ConfigurationKeyEnum.ARENA_TASK_BOOL.name(), "true")));
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static LoadedArenaView loadArenaView() throws Exception {
        FXMLLoader loader = new FXMLLoader(
                CityEventsExtraLayoutController.class.getResource("/layout/CityEventsExtraLayout.fxml"));
        CityEventsExtraLayoutController controller = new CityEventsExtraLayoutController();
        loader.setController(controller);
        loader.load();
        Map<String, Object> namespace = loader.getNamespace();
        return new LoadedArenaView(
                controller,
                (TextField) namespace.get("textFieldArenaProfileAlliance"),
                (TextField) namespace.get("textFieldArenaProfileServer"),
                (ComboBox<String>) namespace.get("comboBoxArenaAlliancePolicy"),
                (ComboBox<String>) namespace.get("comboBoxArenaServerPolicy"),
                (CheckBox) namespace.get("checkBoxArena"),
                (Label) namespace.get("labelArenaTargetingHint"));
    }

    private record LoadedArenaView(CityEventsExtraLayoutController controller,
                                   TextField alliance,
                                   TextField server,
                                   ComboBox<String> alliancePolicy,
                                   ComboBox<String> serverPolicy,
                                   CheckBox arenaEnabled,
                                   Label hint) {
    }
}
