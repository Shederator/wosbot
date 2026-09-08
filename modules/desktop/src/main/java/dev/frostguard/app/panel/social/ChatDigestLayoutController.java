package dev.frostguard.app.panel.social;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.frostguard.api.chat.ChatDigest;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.engine.chat.ChatTranscriptStore;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * What the chat adds up to, for a window of time.
 *
 * <p>The companion to the transcript rather than a replacement for it. The transcript answers "what
 * was said"; nobody scrolls nine hundred messages to work out whether the alliance is awake at the
 * hour they are, or which two people are actually coordinating, or what got asked while they were
 * at work and never picked up. Those are the questions this page answers, and it answers them by
 * counting -- see {@link ChatDigest} for why it stops short of writing prose about them.
 */
public class ChatDigestLayoutController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    /** How many rows a ranked list shows before it stops being a ranking and becomes a list. */
    private static final int TOP_ROWS = 8;

    @FXML
    private VBox digestBody;
    @FXML
    private Label statusLabel;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private ToggleButton tabWorld;
    @FXML
    private ToggleButton tabAlliance;
    @FXML
    private ComboBox<Duration> windowBox;

    private String channelFilter = "alliance";

    /** The reader whose transcript is counted. The panel no longer offers a choice of reader. */
    private static final String READER = "JAVA";

    private final ChatTranscriptStore store =
            new ChatTranscriptStore(baseDir(), ZoneId.systemDefault());

    /** The last thing worked out, kept so Copy has something to put on the clipboard. */
    private ChatDigest.Result current;

    @FXML
    private void initialize() {
        // The busiest-hour figure is a statement about a clock, so it has to be recomputed
        // rather than merely relabelled when the clock changes.
        ChatClock.zoneProperty().addListener((obs, was, now) -> rebuild());

        ToggleGroup channels = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {tabWorld, tabAlliance}) {
            t.setToggleGroup(channels);
        }
        channels.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });

        windowBox.getItems().addAll(Duration.ofHours(24), Duration.ofDays(7), Duration.ofDays(30));
        windowBox.setCellFactory(lv -> windowCell());
        windowBox.setButtonCell(windowCell());
        windowBox.setValue(Duration.ofHours(24));
        windowBox.valueProperty().addListener((obs, was, now) -> rebuild());

        rebuild();
    }

    private ListCell<Duration> windowCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Duration item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label(item));
            }
        };
    }

    private static String label(Duration window) {
        return window.toHours() <= 24 ? "Last 24 hours" : "Last " + window.toDays() + " days";
    }

    /**
     * Reads the transcript and counts it, off the UI thread.
     *
     * <p>Thirty days of stored chat is a lot of files and a lot of lines, and the counting itself
     * walks every message several times. Inline it would freeze the interface on the machine that
     * is also running the emulator.
     */
    @FXML
    private void rebuild() {
        Toggle selected = tabWorld.getToggleGroup().getSelectedToggle();
        Object data = selected == null ? null : ((ToggleButton) selected).getUserData();
        channelFilter = data == null ? "alliance" : data.toString();

        Duration window = windowBox.getValue() == null ? Duration.ofHours(24) : windowBox.getValue();
        statusLabel.setText("Working it out...");

        Task<ChatDigest.Result> work = new Task<>() {
            @Override
            protected ChatDigest.Result call() throws IOException {
                // Twice the window, not just the window. "First heard from" is a claim about what
                // came before, so the chat before the window has to be in hand too -- read to the
                // window's edge and the roster section can never resolve, because there is nothing
                // behind it to have been absent from.
                int reach = (int) Math.min(MOST_MESSAGES_WORTH_READING,
                        window.toHours() * 2 * MESSAGES_PER_HOUR_ALLOWANCE);
                List<ChatMessage> all = store.recent(reach).stream()
                        .filter(m -> channelFilter.equals(m.channel()))
                        .toList();
                return ChatDigest.of(all, window, Instant.now(), ChatClock.zone());
            }
        };
        work.setOnSucceeded(e -> Platform.runLater(() -> show(work.getValue(), window)));
        work.setOnFailed(e -> Platform.runLater(() ->
                statusLabel.setText("Could not read the transcript: "
                        + work.getException().getMessage())));
        Thread t = new Thread(work, "chat-digest");
        t.setDaemon(true);
        t.start();
    }

    /** A busy alliance runs a few hundred messages a day; this leaves generous room above that. */
    private static final long MESSAGES_PER_HOUR_ALLOWANCE = 60;

    /** A ceiling, so picking thirty days cannot ask for every message ever stored. */
    private static final long MOST_MESSAGES_WORTH_READING = 40_000;

    private void show(ChatDigest.Result r, Duration window) {
        current = r;
        digestBody.getChildren().clear();

        if (r.messages() == 0) {
            statusLabel.setText("Nothing captured in this window.");
            digestBody.getChildren().add(muted(
                    "No " + channelFilter + " messages in the " + label(window).toLowerCase()
                            + ". Capture runs on a schedule -- the Live Transcript tab has a "
                            + "Capture Now button if you do not want to wait for it."));
            return;
        }

        statusLabel.setText(r.messages() + " messages from " + r.people() + " people, "
                + label(window).toLowerCase());

        digestBody.getChildren().add(headline(r));
        digestBody.getChildren().add(hourChart(r));
        digestBody.getChildren().add(rankedSection("Who is talking", r.voices(), "messages"));
        digestBody.getChildren().add(rankedSection("What it was about", r.topics(), "mentions"));
        digestBody.getChildren().add(rankedSection("Who is coordinating", r.pairs(), "times"));
        digestBody.getChildren().add(calloutSection(r));
        digestBody.getChildren().add(questionSection(r));
        digestBody.getChildren().add(rosterSection(r));

        scrollPane.setVvalue(0);
    }

    private VBox headline(ChatDigest.Result r) {
        HBox row = new HBox(28);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(
                figure(String.valueOf(r.messages()), "messages"),
                figure(String.valueOf(r.people()), "people talking"),
                figure(r.busiestHour() < 0 ? "--" : String.format("%02d:00", r.busiestHour()),
                        "busiest hour"),
                figure(String.valueOf(r.callouts().size()), "map callouts"),
                figure(String.valueOf(r.unanswered().size()), "unanswered"));
        return section("At a glance", row);
    }

    private VBox figure(String value, String caption) {
        Label big = new Label(value);
        big.getStyleClass().add("digest-figure");
        Label small = new Label(caption);
        small.getStyleClass().add("digest-caption");
        VBox box = new VBox(1, big, small);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Traffic by hour of the day.
     *
     * <p>Bars rather than a number, because the useful reading is the shape: when the alliance is
     * awake, and whether that overlaps the hours the person reading this is. A single "busiest
     * hour" hides a feed with two peaks in different timezones, which is what most alliances are.
     */
    private VBox hourChart(ChatDigest.Result r) {
        int busiest = 0;
        for (int n : r.perHour()) {
            busiest = Math.max(busiest, n);
        }
        HBox bars = new HBox(2);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setPrefHeight(CHART_HEIGHT);
        for (int hour = 0; hour < 24; hour++) {
            int count = r.perHour()[hour];
            Region bar = new Region();
            bar.getStyleClass().add(count > 0 && count == busiest ? "digest-bar-peak" : "digest-bar");
            bar.setPrefHeight(busiest == 0 ? 1 : Math.max(1.0, CHART_HEIGHT * count / busiest));
            bar.setPrefWidth(BAR_WIDTH);
            javafx.scene.control.Tooltip.install(bar,
                    new javafx.scene.control.Tooltip(String.format("%02d:00 — %d message(s)",
                            hour, count)));
            VBox column = new VBox(3, bar, hourTick(hour));
            column.setAlignment(Pos.BOTTOM_CENTER);
            bars.getChildren().add(column);
            HBox.setHgrow(column, Priority.ALWAYS);
        }
        return section("When the alliance is awake  (your clock)", bars);
    }

    private Label hourTick(int hour) {
        // Every third hour. All twenty-four overlap into a smear at this width.
        Label tick = new Label(hour % 3 == 0 ? String.format("%02d", hour) : "");
        tick.getStyleClass().add("digest-tick");
        return tick;
    }

    private static final double CHART_HEIGHT = 74;
    private static final double BAR_WIDTH = 22;

    private VBox rankedSection(String title, List<ChatDigest.Tally> rows, String unit) {
        VBox list = new VBox(4);
        if (rows.isEmpty()) {
            list.getChildren().add(muted("Nothing in this window."));
            return section(title, list);
        }
        int most = rows.get(0).count();
        for (ChatDigest.Tally t : rows.stream().limit(TOP_ROWS).toList()) {
            Label name = new Label(t.name());
            name.getStyleClass().add("digest-row-name");
            name.setMinWidth(NAME_COLUMN);
            name.setPrefWidth(NAME_COLUMN);

            Region bar = new Region();
            bar.getStyleClass().add("digest-bar");
            bar.setPrefHeight(BAR_THICKNESS);
            bar.setPrefWidth(Math.max(2.0, BAR_TRACK * t.count() / most));

            Label count = new Label(t.count() + " " + unit);
            count.getStyleClass().add("digest-caption");

            HBox row = new HBox(8, name, bar, count);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        if (rows.size() > TOP_ROWS) {
            list.getChildren().add(muted("and " + (rows.size() - TOP_ROWS) + " more"));
        }
        return section(title, list);
    }

    private static final double NAME_COLUMN = 190;
    private static final double BAR_TRACK = 240;
    private static final double BAR_THICKNESS = 11;

    private VBox calloutSection(ChatDigest.Result r) {
        VBox list = new VBox(3);
        if (r.callouts().isEmpty()) {
            list.getChildren().add(muted("No map references in this window."));
        } else {
            for (ChatDigest.Callout c : r.callouts().stream()
                    .skip(Math.max(0, r.callouts().size() - TOP_ROWS)).toList()) {
                list.getChildren().add(muted(TIME.format(c.at().atZone(ChatClock.zone()))
                        + "   " + c.who() + "   " + c.coordinates()));
            }
        }
        return section("Map callouts", list);
    }

    /**
     * Questions nobody came back to.
     *
     * <p>The one section worth reading top to bottom. A question that went past while somebody was
     * at work is the thing a transcript buries and a digest can hand straight back.
     */
    private VBox questionSection(ChatDigest.Result r) {
        VBox list = new VBox(5);
        if (r.unanswered().isEmpty()) {
            list.getChildren().add(muted("Everything asked got a reply."));
        } else {
            for (ChatDigest.Unanswered u : r.unanswered().stream().limit(TOP_ROWS).toList()) {
                Label who = new Label(DAY_TIME.format(u.at().atZone(ChatClock.zone()))
                        + "   " + u.who());
                who.getStyleClass().add("digest-caption");
                Label what = new Label(u.text());
                what.getStyleClass().add("digest-question");
                what.setWrapText(true);
                list.getChildren().add(new VBox(1, who, what));
            }
            if (r.unanswered().size() > TOP_ROWS) {
                list.getChildren().add(muted("and " + (r.unanswered().size() - TOP_ROWS) + " more"));
            }
        }
        return section("Asked, and nobody answered", list);
    }

    private VBox rosterSection(ChatDigest.Result r) {
        VBox list = new VBox(4);
        if (!r.comparable()) {
            // Said plainly rather than shown as two empty lists. With nothing stored before the
            // window, everyone is new and nobody has gone quiet, and printing that as a finding
            // would be stating the absence of evidence as evidence.
            list.getChildren().add(muted(
                    "Not enough history yet. This compares the window against the chat before it, "
                            + "and there is not a day of transcript behind this one to compare "
                            + "against. It fills in once capture has been running longer."));
        } else {
            list.getChildren().add(namesLine("First heard from", r.arrived()));
            list.getChildren().add(namesLine("Went quiet", r.wentQuiet()));
        }
        return section("Who came and went", list);
    }

    private VBox namesLine(String title, List<String> names) {
        Label head = new Label(title);
        head.getStyleClass().add("digest-caption");
        Label body = new Label(names.isEmpty() ? "nobody" : String.join(", ", names));
        body.getStyleClass().add("digest-row-name");
        body.setWrapText(true);
        return new VBox(1, head, body);
    }

    private VBox section(String title, javafx.scene.Node body) {
        Label head = new Label(title);
        head.getStyleClass().add("digest-section");
        VBox box = new VBox(8, head, body);
        box.getStyleClass().add("digest-card");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("digest-caption");
        label.setWrapText(true);
        return label;
    }

    /** Puts the digest on the clipboard as text, for pasting into chat or a note. */
    @FXML
    private void copyDigest() {
        if (current == null) {
            statusLabel.setText("Nothing to copy yet.");
            return;
        }
        Duration window = windowBox.getValue();
        StringBuilder out = new StringBuilder();
        out.append(label(window)).append(" — ").append(channelFilter).append(" chat")
                .append(System.lineSeparator())
                .append(current.messages()).append(" messages from ").append(current.people())
                .append(" people");
        if (current.busiestHour() >= 0) {
            out.append(String.format(", busiest around %02d:00", current.busiestHour()));
        }
        out.append(System.lineSeparator());
        appendTally(out, "Talking most", current.voices(), "messages");
        appendTally(out, "Topics", current.topics(), "mentions");
        appendTally(out, "Coordinating", current.pairs(), "times");
        if (!current.unanswered().isEmpty()) {
            out.append(System.lineSeparator()).append("Unanswered:").append(System.lineSeparator());
            for (ChatDigest.Unanswered u : current.unanswered().stream().limit(TOP_ROWS).toList()) {
                out.append("  ").append(u.who()).append(": ").append(u.text())
                        .append(System.lineSeparator());
            }
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(out.toString().stripTrailing());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Digest copied.");
    }

    private void appendTally(StringBuilder out, String title, List<ChatDigest.Tally> rows,
                             String unit) {
        if (rows.isEmpty()) {
            return;
        }
        out.append(System.lineSeparator()).append(title).append(':')
                .append(System.lineSeparator());
        for (ChatDigest.Tally t : rows.stream().limit(TOP_ROWS).toList()) {
            out.append("  ").append(t.name()).append(" — ").append(t.count()).append(' ')
                    .append(unit).append(System.lineSeparator());
        }
    }

    private static Path baseDir() {
        Path base = Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
        return "JAVA".equals(READER) ? base.resolveSibling("chat-java") : base;
    }
}
