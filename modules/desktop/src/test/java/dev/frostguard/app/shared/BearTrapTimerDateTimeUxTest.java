package dev.frostguard.app.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.controlsfx.control.CheckComboBox;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.BearTrapParticipationTriggerEnum;
import dev.frostguard.app.panel.combat.BearTrapLayoutController;
import dev.frostguard.app.panel.profile.ConfigAux;
import dev.frostguard.app.panel.profile.ProfileAux;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

class BearTrapTimerDateTimeUxTest {

    private static final AtomicBoolean TOOLKIT_STARTED = new AtomicBoolean();

    @BeforeAll
    static void startJavaFx() throws Exception {
        if (TOOLKIT_STARTED.compareAndSet(false, true)) {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        }
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void parsesExistingValuesAndFormatsTheSamePersistenceShape() {
        LocalDateTime expected = LocalDateTime.of(2026, 7, 26, 0, 30);

        assertEquals(expected, UtcDateTimeValue.parsePersisted("26-07-2026 00:30").orElseThrow());
        assertEquals("26-07-2026 00:30", UtcDateTimeValue.formatPersisted(expected));
        assertEquals("Next activation: 26-07-2026 00:30 UTC", UtcDateTimeValue.formatUtcPreview(expected));
        assertTrue(UtcDateTimeValue.parsePersisted("").isEmpty());
        assertTrue(UtcDateTimeValue.parsePersisted("2026-07-26T00:30").isEmpty());
    }

    @Test
    void convertsUtcPreviewAcrossThePreviousLocalDate() {
        String preview = UtcDateTimeValue.formatLocalPreview(
                LocalDateTime.of(2026, 7, 27, 0, 30),
                ZoneId.of("America/New_York"));

        assertEquals("Local time: 26-07-2026 20:30 EDT", preview);
    }

    @Test
    void calculatesTheNextActivationFromTheFortyEightHourAnchor() {
        LocalDateTime anchor = LocalDateTime.of(2026, 7, 25, 0, 30);
        Clock afterTwoCycles = Clock.fixed(Instant.parse("2026-07-28T01:00:00Z"), ZoneId.of("UTC"));

        assertEquals(LocalDateTime.of(2026, 7, 29, 0, 30),
                UtcDateTimeValue.nextActivation(anchor, afterTwoCycles));
        assertEquals(anchor, UtcDateTimeValue.nextActivation(anchor,
                Clock.fixed(Instant.parse("2026-07-24T23:00:00Z"), ZoneId.of("UTC"))));
    }

    @Test
    void rejectsIncompleteAndInvalidSelections() {
        assertEquals(UtcDateTimeValue.Validation.INCOMPLETE,
                UtcDateTimeValue.resolve(null, "12", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INCOMPLETE,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "24", "30").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolve(LocalDate.of(2026, 7, 26), "12", "60").validation());
        assertEquals(UtcDateTimeValue.Validation.INVALID,
                UtcDateTimeValue.resolveDateText("31-02-2026", "12", "30").validation());
    }

    @Test
    void editorCommitsOnceOnlyAfterApplyAndShowsImmediatePreview() throws Exception {
        runOnFxThread(() -> {
            UtcDateTimeEditor editor = new UtcDateTimeEditor(
                    ZoneId.of("America/New_York"),
                    Clock.fixed(Instant.parse("2026-07-27T01:00:00Z"), ZoneId.of("UTC")));
            List<LocalDateTime> commits = new ArrayList<>();
            AtomicBoolean cleared = new AtomicBoolean();
            editor.setOnCommit(commits::add);
            editor.setOnClear(() -> cleared.set(true));
            editor.timerEnabledProperty().set(true);
            assertTrue(editor.datePicker().isEditable());
            assertEquals("dd-MM-yyyy, e.g. 28-07-2026", editor.datePicker().getPromptText());

            editor.setDateTime(LocalDateTime.of(2026, 7, 26, 19, 15));
            assertTrue(commits.isEmpty(), "loading must not persist the existing value");

            editor.setDraft(LocalDate.of(2026, 7, 27), "00", "30");
            assertEquals("Next activation: 29-07-2026 00:30 UTC",
                    editor.getNextActivationPreviewText());
            assertEquals("Local time: 28-07-2026 20:30 EDT", editor.getLocalPreviewText());
            assertTrue(commits.isEmpty(), "individual control changes must remain a draft");

            editor.commitSelection();
            assertEquals(List.of(LocalDateTime.of(2026, 7, 27, 0, 30)), commits);
            assertEquals("00", editor.hourSpinner().getEditor().getText());
            assertEquals("30", editor.minuteSpinner().getEditor().getText());
            assertEquals(82, editor.hourSpinner().getMinWidth());
            assertEquals(82, editor.minuteSpinner().getMinWidth());

            editor.setDraft(LocalDate.of(2026, 7, 28), "99", "30");
            assertFalse(editor.getValidationMessage().isEmpty());
            assertTrue(editor.datePicker().getEditor().getStyleClass().contains("setting-field-error"));
            assertFalse(editor.datePicker().getEditor().getAccessibleHelp().isBlank());
            editor.commitSelection();
            assertEquals(1, commits.size(), "invalid input must not be persisted");

            editor.setDateTextDraft("31-02-2026", "12", "30");
            assertFalse(editor.getValidationMessage().isEmpty());
            editor.commitSelection();
            assertEquals(1, commits.size(), "invalid date text must not reuse the previous date");

            editor.clearSelection();
            assertTrue(cleared.get());
            assertFalse(editor.hasCommittedDateTime());
            assertNull(editor.getDateTime());
            return null;
        });
    }

    @Test
    void sharedTimeBindingPreservesInvalidDraftAndSuppressesPersistence() throws Exception {
        runOnFxThread(() -> {
            TextField field = new TextField();
            Label error = new Label();
            List<LocalTime> commits = new ArrayList<>();
            new ValidatedTextFieldBinding<>(
                    field,
                    error,
                    SettingValidators.localTime("Activation time"),
                    value -> String.format("%02d:%02d", value.getHour(), value.getMinute()),
                    commits::add,
                    text -> text != null && text.matches("\\d{4}")
                            ? text.substring(0, 2) + ":" + text.substring(2)
                            : text);

            field.setText("99:00");
            field.fireEvent(new ActionEvent());

            assertEquals("99:00", field.getText());
            assertTrue(error.isVisible());
            assertTrue(commits.isEmpty());

            field.setText("0930");
            field.fireEvent(new ActionEvent());

            assertEquals("09:30", field.getText());
            assertEquals(List.of(LocalTime.of(9, 30)), commits);
            assertFalse(error.isVisible());
            return null;
        });
    }

    @Test
    void profileIntegerFieldKeepsInvalidUserInput() throws Exception {
        runOnFxThread(() -> {
            TestProfileController controller = new TestProfileController();
            TextField field = new TextField();
            List<Change> changes = new ArrayList<>();
            controller.register(field, ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT);
            controller.attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            controller.startListening();

            field.setText("invalid");
            field.fireEvent(new ActionEvent());

            assertEquals("invalid", field.getText());
            assertTrue(field.getStyleClass().contains("setting-field-error"));
            assertTrue(changes.isEmpty());

            field.setText("2");
            field.fireEvent(new ActionEvent());

            assertFalse(field.getStyleClass().contains("setting-field-error"));
            assertEquals(List.of(new Change(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2)), changes);
            return null;
        });
    }

    @Test
    void controllerLoadsBothTimersWithoutSavingAndResetsDraftsOnProfileSwitch() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));

            ProfileAux first = profile(1L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "26-07-2026 19:30"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "27-07-2026 05:45"));
            view.controller().onProfileLoad(first);

            assertEquals(LocalDateTime.of(2026, 7, 26, 19, 30), view.timer1().getDateTime());
            assertEquals(LocalDateTime.of(2026, 7, 27, 5, 45), view.timer2().getDateTime());
            assertEquals(List.of(1, 2), view.trapNumber().getItems());
            assertEquals("Both 48-hour event timers are configured.", view.timerRecommendation().getText());
            assertFalse(view.timer1().isDisabled());
            assertFalse(view.timer2().isDisabled());
            assertEquals(BearTrapParticipationTriggerEnum.TIMER_ONLY,
                    view.participationTrigger().getValue());
            assertEquals(150.0,
                    view.participationTriggerInfo().getTooltip().getShowDelay().toMillis());
            assertTrue(view.participationTriggerInfo().getTooltip().getText()
                    .startsWith("Bear icon detection always protects the event"));
            assertFalse(view.participationTrigger().isDisabled());
            assertEquals("Uses Timer 1's UTC schedule or Run now.",
                    view.participationHelper().getText());
            assertFalse(view.participationWarning().isVisible());
            assertFalse(view.selectedTimerWarning().isVisible());
            assertTrue(changes.isEmpty(), "profile loading must not save timer values");

            view.participationTrigger().setValue(BearTrapParticipationTriggerEnum.TIMER_ICON_FALLBACK);
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_ICON_PARTICIPATION_FALLBACK_BOOL, true)));
            assertEquals("Uses Timer 1's UTC schedule; a Bear Trap icon may also start participation.",
                    view.participationHelper().getText());
            assertTrue(view.participationWarning().isVisible());

            view.timer1().setDraft(LocalDate.of(2026, 7, 28), "20", "05");
            view.timer1().commitSelection();
            view.timer2().setDraft(LocalDate.of(2026, 7, 29), "06", "10");
            view.timer2().commitSelection();

            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "28-07-2026 20:05")));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "29-07-2026 06:10")));

            changes.clear();
            ProfileAux second = profile(2L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "30-07-2026 21:15"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "31-07-2026 07:20"));
            view.controller().onProfileLoad(second);

            assertEquals(LocalDateTime.of(2026, 7, 30, 21, 15), view.timer1().getDateTime());
            assertEquals(LocalDateTime.of(2026, 7, 31, 7, 20), view.timer2().getDateTime());
            assertFalse(view.timer1().isDisabled(), "event timers must remain editable without protection");
            assertFalse(view.timer2().isDisabled(), "event timers must remain editable without protection");
            assertTrue(view.participationTrigger().isDisabled());
            assertTrue(changes.isEmpty(), "profile switching must not persist stale drafts");
            return null;
        });
    }

    @Test
    void explainsThatParticipationUsesTheSeparatelySelectedTimer() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(4L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, "1"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "30-07-2026 02:00"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "29-07-2026 19:00"));

            view.controller().onProfileLoad(profile);

            assertEquals("Bear Trap 1 (Timer 1)",
                    view.trapNumber().getConverter().toString(view.trapNumber().getValue()));
            assertEquals("Uses Timer 1's UTC schedule or Run now.", view.participationHelper().getText());
            assertTrue(view.selectedTimerWarning().isVisible());
            assertEquals("Timer 1 protection is disabled. Participation still uses its UTC schedule.",
                    view.selectedTimerWarning().getText());
            assertFalse(view.timer1().isDisabled(),
                    "the selected participation schedule must remain editable without protection");
            assertTrue(changes.isEmpty(), "loading the mismatched settings must not change them silently");

            view.trapNumber().setValue(2);

            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2)));
            assertEquals("Bear Trap 2 (Timer 2)",
                    view.trapNumber().getConverter().toString(view.trapNumber().getValue()));
            assertEquals("Uses Timer 2's UTC schedule or Run now.", view.participationHelper().getText());
            assertFalse(view.selectedTimerWarning().isVisible());
            return null;
        });
    }

    @Test
    void enabledTimerWithMissingValueShowsValidationWithoutPersistingFallback() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(3L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, ""));

            view.controller().onProfileLoad(profile);

            assertNull(view.timer1().getDateTime());
            assertFalse(view.timer1().getValidationMessage().isEmpty());
            assertTrue(view.timer2().getValidationMessage().isEmpty());
            assertTrue(view.trapNumber().getItems().isEmpty());
            assertTrue(view.trapNumber().isDisabled());
            assertEquals("Set a complete UTC date and time above and click Apply first.",
                    view.trapSelectionHelper().getText());
            assertTrue(view.timerRecommendation().getText().startsWith("Strongly recommended:"));
            assertTrue(changes.isEmpty());
            return null;
        });
    }

    @Test
    void exposesATrapForParticipationOnlyAfterItsTimerIsApplied() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(5L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, "1"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "false"));

            view.controller().onProfileLoad(profile);

            assertTrue(view.trapNumber().getItems().isEmpty());
            assertNull(view.trapNumber().getValue());
            assertTrue(view.trapNumber().isDisabled());
            assertTrue(changes.isEmpty(), "an unavailable saved trap must not be replaced during load");

            view.timer2().setDraft(LocalDate.of(2026, 8, 1), "19", "00");
            assertTrue(view.trapNumber().getItems().isEmpty(), "a draft timer must not become selectable");
            view.timer2().commitSelection();

            assertEquals(List.of(2), view.trapNumber().getItems());
            assertNull(view.trapNumber().getValue(), "applying a timer must not silently select it");
            assertFalse(view.trapNumber().isDisabled());
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "01-08-2026 19:00")));
            assertEquals("Block rally-starting tasks (Recommended)",
                    String.valueOf(view.protectionTimer2().getValue()));
            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, true)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL, true)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL, false)));
            assertFalse(changes.stream().anyMatch(change ->
                    change.key() == ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT));

            view.trapNumber().setValue(2);

            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT, 2)));
            assertEquals("Uses Timer 2's UTC schedule or Run now.", view.participationHelper().getText());

            changes.clear();
            view.timer2().clearSelection();

            assertTrue(view.trapNumber().getItems().isEmpty());
            assertNull(view.trapNumber().getValue());
            assertEquals("Off", String.valueOf(view.protectionTimer2().getValue()));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "")));
            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, false)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL, false)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL, false)));
            return null;
        });
    }

    @Test
    void presentsProtectionAsOneUnambiguousModeAndPersistsCompatibleKeys() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(6L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "02-08-2026 19:00"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING, "03-08-2026 19:00"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, "false"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL, "true"),
                    config(ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL, "true"));

            view.controller().onProfileLoad(profile);

            assertEquals("Off", String.valueOf(view.protectionTimer1().getValue()));
            assertEquals("Pause all scheduled tasks", String.valueOf(view.protectionTimer2().getValue()));
            assertTrue(changes.isEmpty(), "loading legacy protection booleans must not rewrite them");

            view.timer1().setDraft(LocalDate.of(2026, 8, 4), "19", "30");
            view.timer1().commitSelection();

            assertEquals("Off", String.valueOf(view.protectionTimer1().getValue()),
                    "editing an existing timer must preserve an intentional Off mode");
            assertFalse(changes.stream().anyMatch(change ->
                    change.key() == ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL));
            changes.clear();

            selectItemNamed(view.protectionTimer1(), "Block rally-starting tasks (Recommended)");

            assertTrue(changes.contains(new Change(ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL, true)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL, true)));
            assertTrue(changes.contains(new Change(
                    ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL, false)));
            assertEquals("Prevents Polar Terror, Hero Mission, and Mercenary from starting rallies.",
                    view.protectionHelperTimer1().getText());
            return null;
        });
    }

    @Test
    void restoresBearJoinFormationsByValueInsteadOfListIndex() throws Exception {
        runOnFxThread(() -> {
            LoadedBearView view = loadBearView();
            List<Change> changes = new ArrayList<>();
            view.controller().attachProfileListener((key, value) -> changes.add(new Change(key, value)));
            ProfileAux profile = profile(7L,
                    config(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "2,3,4"));

            view.controller().onProfileLoad(profile);

            assertEquals(List.of(2, 3, 4), view.joinFormations().getCheckModel().getCheckedItems());
            assertTrue(changes.isEmpty(), "restoring saved formations must not rewrite the profile");

            view.joinFormations().getCheckModel().clearCheck(Integer.valueOf(2));
            view.joinFormations().getCheckModel().check(Integer.valueOf(7));

            assertEquals(new Change(ConfigurationKeyEnum.BEAR_TRAP_JOIN_FLAG_INT, "3,4,7"),
                    changes.get(changes.size() - 1));
            return null;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectItemNamed(ComboBox<?> comboBox, String label) {
        Object item = comboBox.getItems().stream()
                .filter(candidate -> label.equals(String.valueOf(candidate)))
                .findFirst()
                .orElseThrow();
        ((ComboBox) comboBox).setValue(item);
    }

    @SuppressWarnings("unchecked")
    private static LoadedBearView loadBearView() throws Exception {
        FXMLLoader loader = new FXMLLoader(BearTrapLayoutController.class.getResource("/layout/BearTrapLayout.fxml"));
        BearTrapLayoutController controller = new BearTrapLayoutController();
        loader.setController(controller);
        Parent root = loader.load();
        Map<String, Object> namespace = loader.getNamespace();
        return new LoadedBearView(
                root,
                controller,
                (UtcDateTimeEditor) namespace.get("timer1DateTimeEditor"),
                (UtcDateTimeEditor) namespace.get("timer2DateTimeEditor"),
                (ComboBox<?>) namespace.get("comboBoxProtectionTimer1"),
                (ComboBox<?>) namespace.get("comboBoxProtectionTimer2"),
                (Label) namespace.get("labelProtectionHelperTimer1"),
                (Label) namespace.get("labelProtectionHelperTimer2"),
                (ComboBox<Integer>) namespace.get("comboBoxTrapNumber"),
                (ComboBox<BearTrapParticipationTriggerEnum>) namespace.get("comboBoxParticipationTrigger"),
                (Label) namespace.get("labelTimerRecommendation"),
                (Label) namespace.get("labelTrapSelectionHelper"),
                (Label) namespace.get("labelParticipationTriggerInfo"),
                (Label) namespace.get("labelParticipationHelper"),
                (Label) namespace.get("labelParticipationWarning"),
                (Label) namespace.get("labelSelectedTimerWarning"),
                (CheckComboBox<Integer>) namespace.get("checkComboBoxJoinFlag"));
    }

    private static ProfileAux profile(long id, ConfigAux... configs) {
        ProfileAux profile = new ProfileAux(id, "Profile " + id, "1", true, 50L, "", 0L);
        profile.setConfigs(List.of(configs));
        return profile;
    }

    private static ConfigAux config(ConfigurationKeyEnum key, String value) {
        return new ConfigAux(key.name(), value);
    }

    private static <T> T runOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "JavaFX action timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private record Change(ConfigurationKeyEnum key, Object value) {
    }

    private static final class TestProfileController extends AbstractProfileController {
        private void register(TextField field, ConfigurationKeyEnum key) {
            registerTextField(field, key);
        }

        private void startListening() {
            initializeChangeEvents();
        }
    }

    private record LoadedBearView(
            Parent root,
            BearTrapLayoutController controller,
            UtcDateTimeEditor timer1,
            UtcDateTimeEditor timer2,
            ComboBox<?> protectionTimer1,
            ComboBox<?> protectionTimer2,
            Label protectionHelperTimer1,
            Label protectionHelperTimer2,
            ComboBox<Integer> trapNumber,
            ComboBox<BearTrapParticipationTriggerEnum> participationTrigger,
            Label timerRecommendation,
            Label trapSelectionHelper,
            Label participationTriggerInfo,
            Label participationHelper,
            Label participationWarning,
            Label selectedTimerWarning,
            CheckComboBox<Integer> joinFormations) {
    }
}
