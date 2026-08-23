package dev.frostguard.app.panel.social;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.chat.ChatDiscordRenderer;
import dev.frostguard.api.chat.ChatMessage;
import dev.frostguard.engine.chat.ChatTranscriptStore;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.ScheduleService;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.app.panel.profile.IProfileLoadListener;
import dev.frostguard.app.panel.profile.ProfileAux;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * The live transcript: what the alliance and the world actually said, laid out the way a chat
 * client lays it out rather than as rows in a table.
 *
 * <p>This is drawn with ordinary JavaFX nodes rather than a browser view. Embedding one would have
 * allowed the Discord component libraries to be reused directly, but {@code javafx-web} carries
 * WebKit native libraries -- tens of megabytes per platform -- and this feature ships to people who
 * did not ask for it. Size is the constraint the whole design bends around, so the layout is
 * rebuilt with controls that are already on the classpath. {@link ChatDiscordRenderer} still
 * produces the browser version, which the export button writes out.
 */
public class ChatTranscriptLayoutController implements IProfileLoadListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** How many messages to draw, from the profile. A limit on the panel, not on the transcript. */
    private int viewLimit = DEFAULT_VIEW_LIMIT;

    /** What the tab drew before the figure was offered as a setting. */
    private static final int DEFAULT_VIEW_LIMIT = 400;

    /** Scales the message list from its top-left corner. See the zoom wiring in initialize(). */
    private final javafx.scene.transform.Scale zoom = new javafx.scene.transform.Scale(1, 1, 0, 0);

    /** Drawn at its natural size. What a double-click on the zoom returns to. */
    private static final double NORMAL_ZOOM = 100;

    /** Close enough to the bottom to count as reading the newest message. */
    private static final double NEWEST_ENOUGH = 0.98;

    /** One stable colour per author, so a player is recognisable down the list. */
    private static final String[] AUTHOR_COLOURS = {
            "#5865f2", "#57f287", "#fee75c", "#eb459e", "#ed4245",
            "#3ba55d", "#faa81a", "#00b0f4", "#9b84ec", "#f47fff"
    };

    @FXML
    private VBox messageList;
    @FXML
    private Label statusLabel;
    @FXML
    private ScrollPane scrollPane;
    @FXML
    private ToggleButton tabWorld;
    @FXML
    private ToggleButton tabAlliance;
    @FXML
    private CheckBox checkHideChatter;
    @FXML
    private Slider zoomSlider;
    @FXML
    private Label zoomLabel;
    @FXML
    private Button captureNowButton;
    @FXML
    private BorderPane root;
    @FXML
    private HBox findBar;
    @FXML
    private TextField findField;
    @FXML
    private Label findCount;

    /** Which conversation is on screen. Alliance is what the panel opens on. */
    private String channelFilter = "alliance";

    /**
     * The transcript on screen is the in-process reader's.
     *
     * <p>There used to be a Python/Java switch here. The service is not something a person who
     * downloads this has, so for everyone but us the other side of that switch was an empty panel,
     * and the reader it named was not the one doing the reading. The service still writes its own
     * directory when it is deliberately turned on for comparison work; the panel just no longer
     * offers a choice that only means something on a developer's machine.
     */
    private static final String READER = "JAVA";

    private final ChatTranscriptStore store = new ChatTranscriptStore(baseDir(READER),
            ZoneId.systemDefault());

    @FXML
    private void initialize() {
        // One channel at a time by default. World, Alliance and Personal are separate
        // conversations, and interleaving them is what made the transcript hard to follow.
        // Zoom scales the message list alone, so the controls above it stay the size the rest of
        // the application draws them at. A percentage is what people expect a zoom to be.
        // A Scale pinned to the top-left corner rather than setScaleX/Y, which pivot on the middle
        // of the list and need a translate to undo -- and that translate is computed from the
        // height, so it changes every time a message arrives and fights whatever the scroll
        // position was. Growing from the origin is also how a page zooms: the top-left stays put.
        messageList.getTransforms().add(zoom);
        zoomSlider.valueProperty().addListener((obs, was, now) -> {
            double scale = now.doubleValue() / 100.0;
            zoom.setX(scale);
            zoom.setY(scale);
            zoomLabel.setText(Math.round(now.doubleValue()) + "%");
            // The list is a different height now, so where the bottom is has moved.
            if (scrollPane.getVvalue() >= NEWEST_ENOUGH) {
                pinToNewest();
            }
        });
        // Double-click either the slider or the percentage to put it back to 100. Dragging a
        // slider back to exactly its default is fiddly, and this is the one value on it anybody
        // actually wants to return to.
        zoomSlider.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                zoomSlider.setValue(NORMAL_ZOOM);
            }
        });
        zoomLabel.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                zoomSlider.setValue(NORMAL_ZOOM);
            }
        });
        zoomLabel.setTooltip(new javafx.scene.control.Tooltip("Double-click to reset to 100%"));

        // Copy out of the panel. JavaFX will not select across a column of separate text nodes
        // the way a browser does, and the alternative -- one editable field per message -- gives
        // up the names, colours and quote blocks that make this readable in the first place. So
        // selection is per message: click one to pick it, Ctrl+C or the right-click menu to take
        // it, and Ctrl+A then Ctrl+C for the conversation.
        messageList.setOnKeyPressed(e -> {
            if (COPY.match(e)) {
                copySelection();
                e.consume();
            } else if (SELECT_ALL.match(e)) {
                selected.clear();
                selected.addAll(shown);
                repaintSelection();
                e.consume();
            }
        });
        messageList.setFocusTraversable(true);

        // A filter on the root rather than a handler on the list, so Ctrl+F works wherever the
        // focus happens to be in this tab -- including inside the find field itself, which is
        // where somebody who has already opened it will press it again.
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (FIND.match(e)) {
                openFind();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && findBar.isVisible()) {
                closeFind();
                e.consume();
            }
        });
        findBar.managedProperty().bind(findBar.visibleProperty());
        findField.textProperty().addListener((obs, was, now) -> runSearch(now));
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    findPrevious();
                } else {
                    findNext();
                }
                e.consume();
            }
        });

        // Times already on screen are in the old zone until something redraws them.
        ChatClock.zoneProperty().addListener((obs, was, now) -> refresh());

        ToggleGroup channels = new ToggleGroup();
        for (ToggleButton t : new ToggleButton[] {tabWorld, tabAlliance}) {
            t.setToggleGroup(channels);
        }
        // A toggle group lets the selected button be clicked off, which would leave no channel
        // chosen and an empty screen; re-select it instead.
        channels.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now == null && was != null) {
                was.setSelected(true);
            }
        });
        // Capture runs on its own schedule, so a panel left open goes stale without saying so --
        // it shows a quiet afternoon that ended half an hour ago. Reloading on the same cadence as
        // the capture keeps the two in step.
        // Zero is off and comes first, so leaving it alone costs nothing.
        autoReloadBox.getItems().addAll(0, 1, 2, 5, 15, 30);
        autoReloadBox.setCellFactory(lv -> intervalCell());
        autoReloadBox.setButtonCell(intervalCell());
        autoReloadBox.setValue(REFRESH_MINUTES);
        autoReloadBox.valueProperty().addListener((obs, was, now) -> restartFollow());

        // Only while the panel is actually on screen. A timer that keeps firing behind a tab
        // nobody is looking at reads day files off disk every minute for no reader at all, on a
        // machine that is also running an emulator.
        messageList.sceneProperty().addListener((obs, was, now) -> restartFollow());
        messageList.visibleProperty().addListener((obs, was, now) -> restartFollow());
        restartFollow();

        refresh();
    }

    /** Reloads on a cadence the reader chooses, so an open panel does not quietly go stale. */
    private Timeline follow;

    /** What the panel reloaded at before the interval was offered as a choice. */
    private static final int REFRESH_MINUTES = 30;

    /**
     * Starts, stops or re-times the reload timer to match the dropdown and what is on screen.
     *
     * <p>Called from every input that can change either, and it rebuilds rather than adjusts: a
     * Timeline's period is fixed once it is running, and a stopped one costs nothing to replace.
     */
    private void restartFollow() {
        if (follow != null) {
            follow.stop();
            follow = null;
        }
        Integer minutes = autoReloadBox.getValue();
        boolean onScreen = messageList.getScene() != null && messageList.isVisible();
        if (minutes == null || minutes <= 0 || !onScreen) {
            return;
        }
        follow = new Timeline(new KeyFrame(Duration.minutes(minutes), e -> refresh()));
        follow.setCycleCount(Timeline.INDEFINITE);
        follow.play();
    }

    /** Reads the interval as time, and zero as off rather than as "0 min". */
    private ListCell<Integer> intervalCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item == 0 ? "Off" : item == 1 ? "Every minute" : "Every " + item + " min");
                }
            }
        };
    }

    @FXML
    private ComboBox<Integer> autoReloadBox;

    @FXML
    private void selectChannel() {
        Toggle selected = tabWorld.getToggleGroup().getSelectedToggle();
        Object data = selected == null ? null : ((ToggleButton) selected).getUserData();
        channelFilter = data == null ? "" : data.toString();
        refresh();
    }

    /**
     * Reloads the transcript off the UI thread.
     *
     * <p>The store reads whole day files, which grow all day, so doing it inline would stall the
     * interface for as long as the read takes on a machine that is also running an emulator.
     */
    @FXML
    private void refresh() {
        statusLabel.setText("Loading transcript...");
        Task<List<ChatMessage>> load = new Task<>() {
            @Override
            protected List<ChatMessage> call() throws IOException {
                // Read wider than the view limit before filtering, otherwise selecting one channel
                // shows only the handful of its messages that fell inside the last N overall.
                List<ChatMessage> all = store.recent(viewLimit * 4);
                boolean hideChatter = checkHideChatter.isSelected();
                List<ChatMessage> kept = new java.util.ArrayList<>();
                for (ChatMessage m : all) {
                    if (!channelFilter.isEmpty() && !channelFilter.equals(m.channel())) {
                        continue;
                    }
                    if (hideChatter && (m.kind() == ChatMessage.Kind.SYSTEM
                            || dev.frostguard.api.chat.ChatLineCleaner.isNonSpeech(m.body()))) {
                        continue;
                    }
                    kept.add(m);
                }
                return kept.size() > viewLimit
                        ? kept.subList(kept.size() - viewLimit, kept.size())
                        : kept;
            }
        };
        load.setOnSucceeded(e -> Platform.runLater(() -> show(load.getValue())));
        load.setOnFailed(e -> Platform.runLater(() ->
                statusLabel.setText("Could not read the transcript: " + load.getException().getMessage())));
        Thread t = new Thread(load, "chat-transcript-load");
        t.setDaemon(true);
        t.start();
    }

    /** Writes the browser-rendered transcript beside the stored days. */
    @FXML
    private void exportHtml() {
        try {
            List<ChatMessage> messages = store.recent(viewLimit);
            Path out = baseDir(READER).resolve("transcript.html");
            Files.writeString(out, ChatDiscordRenderer.render(messages, ChatClock.zone()),
                    StandardCharsets.UTF_8);
            statusLabel.setText("Exported " + messages.size() + " message(s) to " + out);
        } catch (IOException e) {
            statusLabel.setText("Export failed: " + e.getMessage());
        }
    }

    private void show(List<ChatMessage> messages) {
        // Where the reader was, asked before the list is emptied. Clearing the children resets the
        // scroll position, so asking afterwards always answered "at the top" -- which is why the
        // panel opened on the oldest message it held and stayed there through every reload. A chat
        // window opens on the newest thing said; this one had been opening on the oldest.
        double was = scrollPane.getVvalue();
        boolean followNewest = messageList.getChildren().isEmpty() || was >= NEWEST_ENOUGH;

        messageList.getChildren().clear();
        assignedColours.clear();
        lastColour = null;

        if (messages.isEmpty()) {
            Label empty = new Label("No chat captured yet. Turn capture on in the Configure tab and "
                    + "the transcript fills in on the next pass.");
            empty.getStyleClass().add("label-muted");
            empty.setWrapText(true);
            messageList.getChildren().add(empty);
            statusLabel.setText(sizeLine(0));
            return;
        }

        shown.clear();
        cards.clear();
        selected.clear();
        for (ChatMessage m : messages) {
            String author = m.author().isBlank() ? "unknown" : m.author();
            HBox row = card(m, author);
            shown.add(m);
            cards.put(m, row);
            messageList.getChildren().add(row);
        }

        statusLabel.setText(sizeLine(messages.size()));
        // Pinned to the newest message, unless the reader had scrolled back to look at something.
        // A view that reloads itself and yanks you to the bottom while you are reading is worse
        // than one that never reloads at all.
        if (findBar.isVisible() && findField.getText() != null
                && !findField.getText().isBlank()) {
            // The cards are new objects after a reload, so the old highlights point at nodes that
            // are no longer on screen.
            runSearch(findField.getText());
        }
        if (followNewest) {
            pinToNewest();
        } else {
            Platform.runLater(() -> scrollPane.setVvalue(was));
        }
    }

    /**
     * Puts the view on the newest message and makes it stay there.
     *
     * <p>Setting the value once does not work. A ScrollPane measures against the content it has
     * laid out, and in the pulse the list is rebuilt in that is still the old height -- so 1.0 is
     * honoured against a viewport that has not grown yet, and the view lands part way up. Forcing
     * the layout first and setting it again on the following pulse is what holds it at the bottom
     * at every window size and message count.
     */
    private void pinToNewest() {
        scrollPane.applyCss();
        scrollPane.layout();
        scrollPane.setVvalue(1.0);
        Platform.runLater(() -> {
            scrollPane.layout();
            scrollPane.setVvalue(1.0);
        });
    }

    private String sizeLine(int shown) {
        try {
            return shown + " message(s) shown - transcript is "
                    + ChatTranscriptStore.humanSize(store.sizeBytes()) + " on disk";
        } catch (IOException e) {
            return shown + " message(s) shown";
        }
    }

    /**
     * One message as a card, with a bar in the author's colour down its left edge.
     *
     * <p>Every message carries its own name and time. Consecutive messages from one person used to
     * share a single name, which saved a little height and cost the thing the panel exists for: a
     * block of text with no name on it cannot be scanned for who said it, and that is the first
     * question anyone asks of a transcript.
     */
    private HBox card(ChatMessage m, String author) {
        Region gutter = new Region();
        gutter.getStyleClass().add("chat-gutter");
        gutter.setStyle("-fx-background-color: " + colourFor(author) + ";");

        VBox content = new VBox(3);
        content.getStyleClass().add("chat-card-body");
        content.setMaxWidth(Double.MAX_VALUE);
        content.setFillWidth(true);
        HBox.setHgrow(content, Priority.ALWAYS);

        content.getChildren().add(header(m, author));
        if (m.hasQuote()) {
            content.getChildren().add(quoteNode(m.quoted()));
        }
        content.getChildren().add(bodyNode(m));

        HBox row = new HBox(gutter, content);
        row.getStyleClass().add("chat-card");
        row.setOnMouseClicked(e -> {
            if (!e.isShortcutDown()) {
                selected.clear();
            }
            if (!selected.remove(m)) {
                selected.add(m);
            }
            repaintSelection();
            messageList.requestFocus();
        });
        ContextMenu menu = new ContextMenu();
        MenuItem copyOne = new MenuItem("Copy message");
        copyOne.setOnAction(e -> {
            if (!selected.contains(m)) {
                selected.clear();
                selected.add(m);
                repaintSelection();
            }
            copySelection();
        });
        MenuItem copyAll = new MenuItem("Copy everything shown");
        copyAll.setOnAction(e -> {
            selected.clear();
            selected.addAll(shown);
            repaintSelection();
            copySelection();
        });
        menu.getItems().addAll(copyOne, copyAll);
        row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));
        // Without this the card is only ever as wide as the text it happens to hold: a box's
        // maximum defaults to its computed size, so it never grows into the space beside it and
        // the message wraps into a narrow column with the rest of the panel left empty.
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private HBox header(ChatMessage m, String author) {
        HBox head = new HBox(7);
        head.setAlignment(Pos.BASELINE_LEFT);

        Label name = new Label(author);
        name.getStyleClass().add("chat-author");
        // The colour is per-author and computed, so it stays in Java; everything else about the
        // name lives in the stylesheet.
        name.setStyle("-fx-text-fill: " + colourFor(author) + ";");
        head.getChildren().add(name);

        if (!m.allianceTag().isBlank()) {
            head.getChildren().add(chip(m.allianceTag()));
        }

        // Beside the name rather than out at the far edge. Against the right margin the time sat
        // a long way from the person it belonged to, and on a wide window the eye had to travel
        // the width of the panel to pair them up.
        Label time = new Label(TIME.format(m.capturedAt().atZone(ChatClock.zone())));
        time.getStyleClass().add("chat-time");
        head.getChildren().add(time);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        head.getChildren().add(gap);
        head.setMaxWidth(Double.MAX_VALUE);
        return head;
    }

    /**
     * The message body: what was said, folded if it is a wall of it.
     *
     * <p>English only. The source text used to sit under every translation, and at a glance it read
     * as a second person talking rather than the same message twice. Nobody reading this speaks
     * every language in the alliance, which is the whole reason the translation is there.
     */
    private VBox bodyNode(ChatMessage m) {
        VBox box = new VBox(2);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setFillWidth(true);

        String shown = m.displayBody();
        box.getChildren().add(flowFor(shown, m));

        // A pasted strategy dump is one player's copy-paste and runs to five lines, burying the
        // conversation either side of it. Folded, it is still there to open.
        if (shown.length() > FOLD_LENGTH) {
            box.getChildren().set(0, flowFor(fold(shown), m));
            Label more = new Label(SHOW_MORE);
            more.getStyleClass().add("chat-more");
            more.setOnMouseClicked(e -> {
                boolean folded = SHOW_MORE.equals(more.getText());
                box.getChildren().set(0, flowFor(folded ? shown : fold(shown), m));
                more.setText(folded ? SHOW_LESS : SHOW_MORE);
            });
            box.getChildren().add(more);
        }
        return box;
    }

    private TextFlow flowFor(String text, ChatMessage m) {
        TextFlow flow = withMentions(text, bodyClass(m), m.mentions());
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private static String fold(String text) {
        return text.substring(0, FOLD_LENGTH).trim() + "\u2026";
    }

    /** Past this a message is a wall of text and is folded. */
    private static final int FOLD_LENGTH = 260;
    private static final String SHOW_MORE = "SHOW MORE";
    private static final String SHOW_LESS = "SHOW LESS";

    private static String bodyClass(ChatMessage m) {
        return switch (m.kind()) {
            case SYSTEM -> "chat-body-system";
            case EMOJI, STICKER -> "chat-body-emoji";
            default -> "chat-body";
        };
    }

    /**
     * The message being replied to, named and set behind a bar.
     *
     * <p>The game writes a quote as "Name: what they said". Saying "replying to" outright, and
     * setting the name apart from the words, lets a reader tell a reply to one person from a reply
     * to another without reading the strip. It sits under the header rather than above it --
     * emitted first, the person being quoted read as the person speaking, which is the single
     * thing that made the panel hardest to follow.
     */
    private VBox quoteNode(String quoted) {
        TextFlow flow = new TextFlow();
        Text marker = new Text("REPLYING TO  ");
        marker.getStyleClass().add("chat-reply-marker");
        flow.getChildren().add(marker);

        int colon = quoted.indexOf(':');
        if (colon > 0 && colon < MAX_QUOTED_NAME) {
            Text who = new Text(quoted.substring(0, colon).trim() + "  ");
            who.getStyleClass().add("chat-quote-who");
            flow.getChildren().add(who);
            flow.getChildren().addAll(mentionParts(quoted.substring(colon + 1).trim(),
                    "chat-quote-text", List.of()));
        } else {
            flow.getChildren().addAll(mentionParts(quoted, "chat-quote-text", List.of()));
        }
        flow.setMaxWidth(Double.MAX_VALUE);

        VBox wrap = new VBox(flow);
        wrap.getStyleClass().add("chat-quote");
        wrap.setMaxWidth(Double.MAX_VALUE);
        wrap.setFillWidth(true);
        return wrap;
    }

    /** Longer than this before a colon and it is a sentence, not a name. */
    private static final int MAX_QUOTED_NAME = 24;

    /** A line of text with the people in it picked out from the words around them. */
    private TextFlow withMentions(String body, String bodyStyle, List<String> named) {
        return new TextFlow(mentionParts(body, bodyStyle, named).toArray(Text[]::new));
    }

    /**
     * Splits a line into ordinary words and the people named in it.
     *
     * <p>A mention is what makes a feed a conversation rather than a list -- it says who is talking
     * to whom -- and it is invisible when set in the same colour as everything else. Addressing the
     * whole alliance gets its own colour because the game draws it as its own thing, and a
     * transcript that flattens the two loses the difference between a broadcast and a reply.
     */
    private java.util.List<Text> mentionParts(String body, String bodyStyle, List<String> named) {
        java.util.List<Text> parts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = MENTION.matcher(body);
        int at = 0;
        while (m.find()) {
            String name = longestNamed(body, m.start(), m.group(), named);
            if (m.start() > at) {
                Text plain = new Text(body.substring(at, m.start()));
                plain.getStyleClass().add(bodyStyle);
                parts.add(plain);
            }
            Text mention = new Text(name);
            mention.getStyleClass().add(name.equalsIgnoreCase("@All")
                    ? "chat-mention-all" : "chat-mention");
            parts.add(mention);
            at = m.start() + name.length();
        }
        if (at < body.length()) {
            Text tail = new Text(body.substring(at));
            tail.getStyleClass().add(bodyStyle);
            parts.add(tail);
        }
        if (parts.isEmpty()) {
            Text only = new Text(body);
            only.getStyleClass().add(bodyStyle);
            parts.add(only);
        }
        return parts;
    }

    /**
     * How far a mention actually runs.
     *
     * <p>Some players' names carry a space, so a mention cannot simply stop at the first one --
     * but nor can it always take the next capitalised word, which turned {@code "@Martinn No
     * worries"} into a mention of somebody called "Martinn No". The message already knows who it
     * named, parsed where the sender lines were available to check against, so the answer is looked
     * up rather than guessed: the longest name this message actually mentions wins, and where none
     * matches the mention is the one word.
     */
    private static String longestNamed(String body, int start, String oneWord,
            List<String> named) {
        String best = oneWord;
        for (String name : named) {
            String at = "@" + name;
            if (at.length() > best.length() && body.regionMatches(true, start, at, 0, at.length())) {
                best = body.substring(start, start + at.length());
            }
        }
        return best;
    }

    /** A name the game wrote with an "@" in front of it: one word unless the roster says longer. */
    private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile(
            "@[A-Za-z0-9_.-]{2,16}");

    private Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chat-chip");
        return chip;
    }

    private String colourFor(String author) {
        return assignedColours.computeIfAbsent(author, name -> {
            int h = 0;
            for (int i = 0; i < name.length(); i++) {
                h = h * 31 + name.charAt(i);
            }
            int first = Math.floorMod(h, AUTHOR_COLOURS.length);
            // The hash alone puts two greens or two reds side by side often enough to be the
            // thing you notice first, and the colour is here so a name can be picked out of the
            // list. Walking forward from the hashed slot keeps a person's colour stable for the
            // whole render while refusing one that neighbours the last speaker's.
            for (int step = 0; step < AUTHOR_COLOURS.length; step++) {
                String candidate = AUTHOR_COLOURS[(first + step) % AUTHOR_COLOURS.length];
                if (!sameFamily(candidate, lastColour) && !assignedColours.containsValue(candidate)) {
                    lastColour = candidate;
                    return candidate;
                }
            }
            // Everything is taken or adjacent -- more speakers than colours. Any is better than
            // none, so fall back to the hashed one rather than leaving the name unpainted.
            lastColour = AUTHOR_COLOURS[first];
            return lastColour;
        });
    }

    /**
     * Whether two swatches read as the same colour at a glance.
     *
     * <p>Compared by hue rather than by distance in RGB, because RGB distance calls a dark green
     * and a bright green far apart when the eye files them together, which is the whole complaint.
     */
    private static boolean sameFamily(String a, String b) {
        if (b == null) {
            return false;
        }
        double gap = Math.abs(hueOf(a) - hueOf(b));
        return Math.min(gap, 360 - gap) < DISTINCT_HUE_DEGREES;
    }

    private static double hueOf(String hex) {
        return javafx.scene.paint.Color.web(hex).getHue();
    }

    /** Closer than this around the wheel and two names look like the same colour. */
    private static final double DISTINCT_HUE_DEGREES = 40;

    /** One colour per author for as long as this render lasts. */
    private final java.util.Map<String, String> assignedColours = new java.util.LinkedHashMap<>();

    /** The last colour handed out, so the next one can be told apart from it. */
    private String lastColour;

    /** The messages currently drawn, in order, so Select All has something to mean. */
    private final List<ChatMessage> shown = new java.util.ArrayList<>();

    /** What the reader has picked out to copy. */
    private final java.util.Set<ChatMessage> selected = new java.util.LinkedHashSet<>();

    /** The card drawn for each message, so a selection can be repainted without a full rebuild. */
    private final java.util.Map<ChatMessage, Region> cards = new java.util.HashMap<>();

    private static final KeyCombination COPY =
            new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination SELECT_ALL =
            new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    /**
     * Puts the selected messages on the clipboard as plain text.
     *
     * <p>Written the way a person would quote it -- name, time, then what was said -- rather than
     * as the panel's own layout. What gets pasted into a message to somebody else should read as
     * chat, not as a dump of fields.
     */
    private void copySelection() {
        if (selected.isEmpty()) {
            statusLabel.setText("Nothing selected. Click a message first.");
            return;
        }
        StringBuilder out = new StringBuilder();
        for (ChatMessage m : shown) {
            if (!selected.contains(m)) {
                continue;
            }
            String author = m.author().isBlank() ? "unknown" : m.author();
            out.append('[').append(TIME.format(m.capturedAt().atZone(ChatClock.zone())))
                    .append("] ").append(author);
            if (!m.allianceTag().isBlank()) {
                out.append(" (").append(m.allianceTag()).append(')');
            }
            out.append(": ").append(m.displayBody()).append(System.lineSeparator());
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(out.toString().stripTrailing());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied " + selected.size() + " message(s).");
    }

    private void repaintSelection() {
        for (java.util.Map.Entry<ChatMessage, Region> e : cards.entrySet()) {
            e.getValue().pseudoClassStateChanged(PICKED, selected.contains(e.getKey()));
        }
    }

    private static final javafx.css.PseudoClass PICKED =
            javafx.css.PseudoClass.getPseudoClass("picked");

    /**
     * Sends the bot to capture chat now rather than waiting for the schedule.
     *
     * <p>Queued rather than run here: capture drives the emulator, and the queue is what keeps two
     * tasks from reaching for it at once. The panel does not wait for it either -- a pass takes
     * about five minutes, and a button that holds the interface for five minutes is a hang.
     */
    @FXML
    private void captureNow() {
        ProfileAux profile = currentProfile;
        if (profile == null) {
            statusLabel.setText("Select a profile first.");
            return;
        }
        TaskQueue queue = ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
        if (queue == null || !queue.isActive()) {
            statusLabel.setText("Start the bot first -- capture runs through the profile's queue.");
            return;
        }
        queue.runNow(TpDailyTaskEnum.CHAT_CAPTURE, false);
        statusLabel.setText("Capture queued for " + profile.getName()
                + ". It reads in the background; press Reload when it finishes.");
    }

    /** The profile whose settings and queue this panel is looking at. */
    private ProfileAux currentProfile;

    @Override
    public void onProfileLoad(ProfileAux profile) {
        currentProfile = profile;
        if (profile != null) {
            ChatClock.useSetting(profile.getConfig(
                    ConfigurationKeyEnum.CHAT_DISPLAY_TIMEZONE_STRING, String.class));
            Integer configured = profile.getConfig(
                    ConfigurationKeyEnum.CHAT_VIEW_MESSAGES_INT, Integer.class);
            int wanted = configured == null || configured <= 0 ? DEFAULT_VIEW_LIMIT : configured;
            if (wanted != viewLimit) {
                viewLimit = wanted;
                refresh();
            }
        }
    }

    /**
     * Everything matching what is in the find field, in the order it appears.
     *
     * <p>Whole messages rather than runs of characters. The panel draws a message as a small tree
     * of nodes -- name, badge, quote, body, sometimes a folded body -- and picking a span out of
     * that would mean rebuilding the text with the match split out, then rebuilding it again on
     * every keystroke. Somebody searching for "frostfire" wants the messages about Frostfire, and
     * those are what this finds.
     *
     * <p>Author and quoted line are searched as well as the body: "who said this" and "what was
     * somebody replying to" are the same question asked from two ends.
     */
    private final List<Integer> matches = new java.util.ArrayList<>();

    /** Which match the view is sitting on, or -1 when there are none. */
    private int matchCursor = -1;

    private static final KeyCombination FIND =
            new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);

    private static final javafx.css.PseudoClass FOUND =
            javafx.css.PseudoClass.getPseudoClass("found");
    private static final javafx.css.PseudoClass CURRENT =
            javafx.css.PseudoClass.getPseudoClass("current-match");

    private void openFind() {
        findBar.setVisible(true);
        findField.requestFocus();
        findField.selectAll();
    }

    @FXML
    private void closeFind() {
        findBar.setVisible(false);
        findField.clear();
        runSearch("");
        messageList.requestFocus();
    }

    private void runSearch(String needle) {
        matches.clear();
        matchCursor = -1;
        String wanted = needle == null ? "" : needle.strip().toLowerCase(java.util.Locale.ROOT);
        if (!wanted.isEmpty()) {
            for (int i = 0; i < shown.size(); i++) {
                if (haystack(shown.get(i)).contains(wanted)) {
                    matches.add(i);
                }
            }
        }
        paintMatches();
        if (!matches.isEmpty()) {
            matchCursor = 0;
            revealCurrent();
        }
        updateFindCount();
    }

    /**
     * Everything about a message worth matching against.
     *
     * <p>Both the source and the translation, not just what is on screen. The panel draws the
     * English where there is one, so searching only that meant a Russian message could not be
     * found by any word actually in it: "Всем спокойной ночи!!!" is displayed as "Good night
     * everyone!!!" and matched neither. Somebody who saw the message in the game and comes here
     * looking for it is searching for what they saw.
     */
    private String haystack(ChatMessage m) {
        return (m.displayBody() + " " + m.body() + " " + m.author() + " "
                + (m.quoted() == null ? "" : m.quoted())).toLowerCase(java.util.Locale.ROOT);
    }

    private void paintMatches() {
        java.util.Set<Integer> found = new java.util.HashSet<>(matches);
        for (int i = 0; i < shown.size(); i++) {
            Region card = cards.get(shown.get(i));
            if (card != null) {
                card.pseudoClassStateChanged(FOUND, found.contains(i));
                card.pseudoClassStateChanged(CURRENT, false);
            }
        }
    }

    @FXML
    private void findNext() {
        step(1);
    }

    @FXML
    private void findPrevious() {
        step(-1);
    }

    /** Moves to the next match, wrapping. A find that stops at the end is a find you have to redo. */
    private void step(int direction) {
        if (matches.isEmpty()) {
            return;
        }
        matchCursor = Math.floorMod(matchCursor + direction, matches.size());
        revealCurrent();
        updateFindCount();
    }

    private void revealCurrent() {
        for (int index : matches) {
            Region other = cards.get(shown.get(index));
            if (other != null) {
                other.pseudoClassStateChanged(CURRENT, false);
            }
        }
        Region card = cards.get(shown.get(matches.get(matchCursor)));
        if (card == null) {
            return;
        }
        card.pseudoClassStateChanged(CURRENT, true);
        scrollTo(card);
    }

    /**
     * Puts a card in the middle of the viewport.
     *
     * <p>The card's own position is a layout figure and knows nothing about the zoom transform on
     * the list, so it is scaled by hand. Measured raw, every match at 150% would be found a third
     * of the way further down than the view was sent.
     */
    private void scrollTo(Region card) {
        double listHeight = messageList.getBoundsInParent().getHeight();
        double viewport = scrollPane.getViewportBounds().getHeight();
        if (listHeight <= viewport) {
            return;
        }
        double middle = (card.getBoundsInParent().getMinY()
                + card.getBoundsInParent().getHeight() / 2) * zoom.getY();
        double target = (middle - viewport / 2) / (listHeight - viewport);
        scrollPane.setVvalue(Math.max(0, Math.min(1, target)));
    }

    private void updateFindCount() {
        String typed = findField.getText();
        if (typed == null || typed.isBlank()) {
            findCount.setText("");
        } else if (matches.isEmpty()) {
            findCount.setText("no matches");
        } else {
            findCount.setText((matchCursor + 1) + " of " + matches.size());
        }
    }

    /**
     * Where a reader keeps its transcript.
     *
     * <p>Mirrors what the capture routine writes. Kept as a plain path rather than shared with it
     * because the panel and the routine live in different modules, and one small duplicated string
     * is cheaper than a dependency between them.
     */
    private static Path baseDir(String reader) {
        Path base = Paths.get(System.getProperty("user.dir"), "telemetry", "chat");
        return "JAVA".equals(reader) ? base.resolveSibling("chat-java") : base;
    }
}
