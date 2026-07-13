package dev.frostguard.app.panel.misc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class GiftcodeLayoutController {

	private static final String API_URL = "http://gift-code-api.whiteout-bot.com/giftcode_api.php";
	private static final String API_KEY = "super_secret_bot_token_nobody_will_ever_find";

	@FXML
	private Button buttonFetch;

	@FXML
	private Button buttonCopyAll;

	@FXML
	private Label labelStatus;

	@FXML
	private VBox giftcodeListContainer;

	private final List<GiftcodeEntry> currentEntries = new ArrayList<>();

	@FXML
	private void initialize() {
		// Initial state set by FXML
	}

	@FXML
	private void handleFetch() {
		buttonFetch.setDisable(true);
		labelStatus.setText("Fetching gift codes...");
		giftcodeListContainer.getChildren().clear();
		currentEntries.clear();
		buttonCopyAll.setVisible(false);
		buttonCopyAll.setManaged(false);

		Label loadingLabel = new Label("Loading...");
		loadingLabel.getStyleClass().add("giftcode-placeholder");
		giftcodeListContainer.getChildren().add(loadingLabel);

		Thread fetchThread = new Thread(() -> {
			try {
				HttpClient client = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.build();

				HttpRequest request = HttpRequest.newBuilder()
						.uri(URI.create(API_URL))
						.header("X-API-Key", API_KEY)
						.header("Content-Type", "application/json")
						.GET()
						.build();

				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() == 200) {
					List<GiftcodeEntry> entries = parseResponse(response.body());
					Platform.runLater(() -> {
						currentEntries.clear();
						currentEntries.addAll(entries);
						populateGiftcodeCards(entries);
						labelStatus.setText("Fetched " + entries.size() + " gift code(s) successfully.");
						buttonFetch.setDisable(false);
						if (!entries.isEmpty()) {
							buttonCopyAll.setVisible(true);
							buttonCopyAll.setManaged(true);
						}
					});
				} else {
					Platform.runLater(() -> {
						giftcodeListContainer.getChildren().clear();
						Label errorLabel = new Label("Failed to fetch gift codes (HTTP " + response.statusCode() + ")");
						errorLabel.getStyleClass().add("giftcode-placeholder");
						giftcodeListContainer.getChildren().add(errorLabel);
						labelStatus.setText("Error: HTTP " + response.statusCode());
						buttonFetch.setDisable(false);
					});
				}
			} catch (Exception e) {
				Platform.runLater(() -> {
					giftcodeListContainer.getChildren().clear();
					Label errorLabel = new Label("Connection error: " + e.getMessage());
					errorLabel.getStyleClass().add("giftcode-placeholder");
					giftcodeListContainer.getChildren().add(errorLabel);
					labelStatus.setText("Error: " + e.getMessage());
					buttonFetch.setDisable(false);
				});
			}
		});
		fetchThread.setDaemon(true);
		fetchThread.start();
	}

	@FXML
	private void handleCopyAll() {
		if (currentEntries.isEmpty()) {
			return;
		}
		String allCodes = currentEntries.stream()
				.map(GiftcodeEntry::code)
				.collect(Collectors.joining("\n"));
		copyToClipboard(allCodes);
		showCopiedFeedback(buttonCopyAll, "Copy All", "Copied All!");
		labelStatus.setText("All " + currentEntries.size() + " gift code(s) copied to clipboard.");
	}

	/**
	 * Builds the card-based UI rows for each gift code entry.
	 */
	private void populateGiftcodeCards(List<GiftcodeEntry> entries) {
		giftcodeListContainer.getChildren().clear();

		if (entries.isEmpty()) {
			Label emptyLabel = new Label("No gift codes available at this time.");
			emptyLabel.getStyleClass().add("giftcode-placeholder");
			giftcodeListContainer.getChildren().add(emptyLabel);
			return;
		}

		for (int i = 0; i < entries.size(); i++) {
			GiftcodeEntry entry = entries.get(i);
			HBox row = createGiftcodeRow(entry, i + 1);
			giftcodeListContainer.getChildren().add(row);
		}
	}

	/**
	 * Creates a single styled row card for a gift code.
	 */
	private HBox createGiftcodeRow(GiftcodeEntry entry, int index) {
		// Left side: index + code + date
		Label indexLabel = new Label("#" + index);
		indexLabel.getStyleClass().add("giftcode-index-label");

		Label codeLabel = new Label(entry.code());
		codeLabel.getStyleClass().add("giftcode-code-label");

		Label dateLabel = new Label(entry.date());
		dateLabel.getStyleClass().add("giftcode-date-label");

		VBox codeInfo = new VBox(2, codeLabel, dateLabel);
		codeInfo.setAlignment(Pos.CENTER_LEFT);

		// Spacer
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		// Copy button
		Button copyBtn = new Button("Copy");
		copyBtn.getStyleClass().add("giftcode-copy-btn");
		copyBtn.setOnAction(e -> {
			copyToClipboard(entry.code());
			showCopiedFeedback(copyBtn, "Copy", "Copied!");
			labelStatus.setText("Copied: " + entry.code());
		});

		HBox row = new HBox(10, indexLabel, codeInfo, spacer, copyBtn);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("giftcode-row");

		return row;
	}

	/**
	 * Copies text to the system clipboard.
	 */
	private void copyToClipboard(String text) {
		ClipboardContent content = new ClipboardContent();
		content.putString(text);
		Clipboard.getSystemClipboard().setContent(content);
	}

	/**
	 * Shows a brief "Copied!" feedback on a button, then reverts.
	 */
	private void showCopiedFeedback(Button button, String originalText, String feedbackText) {
		String originalStyle = button.getStyle();
		button.setText(feedbackText);
		button.getStyleClass().add("giftcode-copy-btn-copied");

		PauseTransition pause = new PauseTransition(javafx.util.Duration.millis(1200));
		pause.setOnFinished(e -> {
			button.setText(originalText);
			button.getStyleClass().remove("giftcode-copy-btn-copied");
		});
		pause.play();
	}

	/**
	 * Parses the JSON response to extract gift codes.
	 * Expected format: {"codes":["CODE1 DD.MM.YYYY","CODE2 DD.MM.YYYY",...]}
	 */
	private List<GiftcodeEntry> parseResponse(String json) {
		List<GiftcodeEntry> entries = new ArrayList<>();

		int codesStart = json.indexOf("\"codes\"");
		if (codesStart == -1) {
			return entries;
		}

		int arrayStart = json.indexOf('[', codesStart);
		int arrayEnd = json.indexOf(']', arrayStart);
		if (arrayStart == -1 || arrayEnd == -1) {
			return entries;
		}

		String arrayContent = json.substring(arrayStart + 1, arrayEnd);

		String[] items = arrayContent.split(",");
		for (String item : items) {
			String trimmed = item.trim();
			if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
				trimmed = trimmed.substring(1, trimmed.length() - 1);
			}
			if (trimmed.isEmpty()) {
				continue;
			}

			int lastSpace = trimmed.lastIndexOf(' ');
			if (lastSpace > 0) {
				String code = trimmed.substring(0, lastSpace).trim();
				String date = trimmed.substring(lastSpace + 1).trim();
				entries.add(new GiftcodeEntry(code, date));
			} else {
				entries.add(new GiftcodeEntry(trimmed, "N/A"));
			}
		}

		return entries;
	}

	/**
	 * Represents a single gift code entry with code and date.
	 */
	public record GiftcodeEntry(String code, String date) {
	}
}
