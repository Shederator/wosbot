package dev.frostguard.app.panel.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.vision.ocr.OcrEngine;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

public class DebuggingLayoutController {

    @FXML
    private Rectangle selectionBox;

    private double dragStartX = -1;
    private double dragStartY = -1;
    private boolean isDragging = false;

    @FXML
    private StackPane imageContainer;

    @FXML
    private ImageView screenshotImageView;

    @FXML
    private Label dragDropHint;

    @FXML
    private Pane crosshairPane;

    @FXML
    private Line crosshairX;

    @FXML
    private Line crosshairY;

    @FXML
    private Label coordinatesLabel;

    @FXML
    private TextField searchTextField;

    @FXML
    private ListView<File> templateListView;

    @FXML
    private ComboBox<String> actionComboBox;

    @FXML
    private Button btnRunAction;

    @FXML
    private TextArea logTextArea;

    @FXML
    private ComboBox<AccountDescriptor> profileComboBox;

    @FXML
    private Button btnCaptureAdb;

    private File currentScreenshotFile;
    private List<File> allTemplates = new ArrayList<>();

    @FXML
    public void initialize() {
        // Apply rounded clip to screenshot image
        Rectangle clip = new Rectangle(316, 576);
        clip.setArcWidth(30);
        clip.setArcHeight(30);
        screenshotImageView.setClip(clip);

        setupDragAndDrop();
        setupProfiles();
        loadTemplates();
        setupSearchFilter();
        setupActions();
    }

    private void setupProfiles() {
        if (profileComboBox != null) {
            profileComboBox.setOnShowing(e -> {
                try {
                    List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
                    if (profiles != null) {
                        AccountDescriptor currentlySelected = profileComboBox.getValue();
                        profileComboBox.setItems(FXCollections.observableArrayList(profiles));
                        profileComboBox.setConverter(new javafx.util.StringConverter<>() {
                            @Override
                            public String toString(AccountDescriptor p) {
                                return p == null ? "" : p.getName() + " (Emulator " + p.getEmulatorNumber() + ")";
                            }

                            @Override
                            public AccountDescriptor fromString(String string) {
                                return null;
                            }
                        });

                        if (currentlySelected != null) {
                            // Try to maintain selection
                            for (AccountDescriptor p : profiles) {
                                if (p.getEmulatorNumber() == currentlySelected.getEmulatorNumber()) {
                                    profileComboBox.getSelectionModel().select(p);
                                    break;
                                }
                            }
                        } else if (!profiles.isEmpty()) {
                            profileComboBox.getSelectionModel().selectFirst();
                        }
                    }
                } catch (Exception ex) {
                    log("Error loading profiles: " + ex.getMessage());
                }
            });
        }
    }

    @FXML
    private void handleMouseMoved(MouseEvent event) {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null)
            return;

        // Unlock if the user explicitly moves the mouse inside the container
        if (isCrosshairLocked) {
            isCrosshairLocked = false;
        }

        Image img = screenshotImageView.getImage();
        double mouseX = event.getX();
        double mouseY = event.getY();

        // Ensure coordinates are within image view bounds
        double viewWidth = 316.0; // matched fitWidth
        double viewHeight = 576.0; // matched fitHeight

        // Offset handle logic - the imageContainer is slightly larger than the clip
        double offsetX = (imageContainer.getWidth() - viewWidth) / 2.0;
        double offsetY = (imageContainer.getHeight() - viewHeight) / 2.0;

        double relX = mouseX - offsetX;
        double relY = mouseY - offsetY;

        if (relX < 0 || relX > viewWidth || relY < 0 || relY > viewHeight) {
            crosshairPane.setVisible(false);
            return;
        }

        double origWidth = img.getWidth();
        double origHeight = img.getHeight();

        int imgX = (int) ((relX / viewWidth) * origWidth);
        int imgY = (int) ((relY / viewHeight) * origHeight);

        imgX = Math.max(0, Math.min(imgX, (int) origWidth - 1));
        imgY = Math.max(0, Math.min(imgY, (int) origHeight - 1));

        crosshairX.setStartX(0);
        crosshairX.setEndX(imageContainer.getWidth());
        crosshairX.setStartY(mouseY);
        crosshairX.setEndY(mouseY);

        crosshairY.setStartX(mouseX);
        crosshairY.setEndX(mouseX);
        crosshairY.setStartY(0);
        crosshairY.setEndY(imageContainer.getHeight());

        coordinatesLabel.setText(String.format("X: %d\nY: %d", imgX, imgY));

        // Keep label within bounds
        double labelX = mouseX + 15;
        double labelY = mouseY + 15;
        if (labelX + coordinatesLabel.getWidth() > imageContainer.getWidth()) {
            labelX = mouseX - 15 - coordinatesLabel.getWidth();
        }
        if (labelY + coordinatesLabel.getHeight() > imageContainer.getHeight()) {
            labelY = mouseY - 15 - coordinatesLabel.getHeight();
        }

        coordinatesLabel.setLayoutX(labelX);
        coordinatesLabel.setLayoutY(labelY);

        if (!crosshairPane.isVisible() && !isDragging) {
            crosshairPane.setVisible(true);
            crosshairX.setVisible(true);
            crosshairY.setVisible(true);
            coordinatesLabel.setVisible(true);
        }
    }

    @FXML
    private void handleMousePressed(MouseEvent event) {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null)
            return;

        dragStartX = event.getX();
        dragStartY = event.getY();
        isDragging = true;

        selectionBox.setX(dragStartX);
        selectionBox.setY(dragStartY);
        selectionBox.setWidth(0);
        selectionBox.setHeight(0);
        selectionBox.setVisible(true);

        // Hide crosshair lines when dragging
        crosshairX.setVisible(false);
        crosshairY.setVisible(false);
        coordinatesLabel.setVisible(false);
    }

    @FXML
    private void handleMouseDragged(MouseEvent event) {
        if (!isDragging || currentScreenshotFile == null)
            return;

        double currentX = event.getX();
        double currentY = event.getY();

        // Clamp to imageContainer bounds
        currentX = Math.max(0, Math.min(currentX, imageContainer.getWidth()));
        currentY = Math.max(0, Math.min(currentY, imageContainer.getHeight()));

        double x = Math.min(dragStartX, currentX);
        double y = Math.min(dragStartY, currentY);
        double width = Math.abs(currentX - dragStartX);
        double height = Math.abs(currentY - dragStartY);

        selectionBox.setX(x);
        selectionBox.setY(y);
        selectionBox.setWidth(width);
        selectionBox.setHeight(height);

        if (!crosshairPane.isVisible()) {
            crosshairPane.setVisible(true);
        }
    }

    @FXML
    private void handleMouseReleased(MouseEvent event) {
        if (!isDragging)
            return;
        isDragging = false;

        if ("OCR".equals(actionComboBox.getValue())) {
            performOCROnSelection();
        }
    }

    @FXML
    private void handleMouseEntered(MouseEvent event) {
        if (currentScreenshotFile != null && !isCrosshairLocked) {
            crosshairPane.setVisible(true);
        }
    }

    @FXML
    private void handleMouseExited(MouseEvent event) {
        if (crosshairPane != null && !isCrosshairLocked) {
            crosshairPane.setVisible(false);
        }
    }

    private void setupSearchFilter() {
        if (searchTextField != null) {
            searchTextField.textProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue == null || newValue.trim().isEmpty()) {
                    templateListView.getItems().setAll(allTemplates);
                } else {
                    String lowerCaseFilter = newValue.toLowerCase();
                    List<File> filteredList = new ArrayList<>();
                    for (File file : allTemplates) {
                        if (file.getName().toLowerCase().contains(lowerCaseFilter)) {
                            filteredList.add(file);
                        }
                    }
                    templateListView.getItems().setAll(filteredList);
                }
            });
        }
    }

    private void setupDragAndDrop() {
        imageContainer.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                boolean isImage = db.getFiles().stream().anyMatch(file -> {
                    String name = file.getName().toLowerCase();
                    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".bmp");
                });
                if (isImage) {
                    event.acceptTransferModes(TransferMode.COPY);
                    imageContainer.getStyleClass().add("drag-hover");
                }
            }
            event.consume();
        });

        imageContainer.setOnDragExited(event -> {
            imageContainer.getStyleClass().remove("drag-hover");
            event.consume();
        });

        imageContainer.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            imageContainer.getStyleClass().remove("drag-hover");

            if (db.hasFiles()) {
                for (File file : db.getFiles()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".bmp")) {
                        loadScreenshot(file);
                        success = true;
                        break;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        imageContainer.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                    if (event.isShortcutDown() && event.getCode() == javafx.scene.input.KeyCode.V) {
                        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                        if (clipboard.hasImage()) {
                            Image image = clipboard.getImage();
                            screenshotImageView.setImage(image);
                            dragDropHint.setVisible(false);
                            try {
                                File tempFile = File.createTempFile("clipboard_screenshot", ".png");
                                tempFile.deleteOnExit();
                                javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png", tempFile);
                                currentScreenshotFile = tempFile;
                                log("Loaded screenshot from clipboard");
                                log("Size: " + image.getWidth() + "x" + image.getHeight());
                            } catch (Exception e) {
                                log("Error saving clipboard image to temp file: " + e.getMessage());
                            }
                        } else if (clipboard.hasFiles()) {
                            for (File file : clipboard.getFiles()) {
                                String name = file.getName().toLowerCase();
                                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp")) {
                                    loadScreenshot(file);
                                    break;
                                }
                            }
                        }
                    }
                });
            }
        });
    }

    private void loadScreenshot(File file) {
        try {
            Image image = new Image(new FileInputStream(file));
            screenshotImageView.setImage(image);
            dragDropHint.setVisible(false);
            currentScreenshotFile = file;
            log("Loaded screenshot: " + file.getAbsolutePath());
            log("Size: " + image.getWidth() + "x" + image.getHeight());
        } catch (FileNotFoundException e) {
            log("Error loading screenshot: " + e.getMessage());
        }
    }

    private void loadTemplates() {
        List<File> possibleTemplateDirs = new ArrayList<>();
        possibleTemplateDirs.add(new File("modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("../modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("../../modules/vision/src/main/resources/templates"));
        possibleTemplateDirs.add(new File("templates"));

        File currentDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (currentDir != null && currentDir.exists()) {
            possibleTemplateDirs.add(new File(currentDir, "modules/vision/src/main/resources/templates"));
            currentDir = currentDir.getParentFile();
        }

        File templateDir = null;
        for (File dir : possibleTemplateDirs) {
            if (dir.exists() && dir.isDirectory()) {
                templateDir = dir;
                break;
            }
        }

        allTemplates.clear();
        if (templateDir != null && templateDir.exists() && templateDir.isDirectory()) {
            findImageFiles(templateDir, allTemplates);
            log("Found " + allTemplates.size() + " templates.");
        } else {
            log("Template directory not found. Please verify location.");
            log("User Dir: " + System.getProperty("user.dir"));
        }

        templateListView.getItems().setAll(allTemplates);
        templateListView.setCellFactory(param -> new ListCell<File>() {
            private ImageView imageView = new ImageView();

            @Override
            protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(file.getName());
                    try {
                        Image icon = new Image(new FileInputStream(file), 32, 32, true, true);
                        imageView.setImage(icon);
                        setGraphic(imageView);
                    } catch (FileNotFoundException e) {
                        setGraphic(null);
                    }
                }
            }
        });
    }

    private void findImageFiles(File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    findImageFiles(file, list);
                } else {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".bmp")) {
                        list.add(file);
                    }
                }
            }
        }
    }

    private void setupActions() {
        actionComboBox.setItems(FXCollections.observableArrayList("Template Search", "OCR"));
        actionComboBox.getSelectionModel().selectFirst();
        
        // pernerch/2026-07-02: Dynamic visibility control for template search UI elements
        // Template list and search field are only shown when "Template Search" action is selected
        // This prevents UI clutter when using OCR mode
        actionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            boolean isTemplateSearch = "Template Search".equals(newValue);
            searchTextField.setVisible(isTemplateSearch);
            searchTextField.setManaged(isTemplateSearch);
            templateListView.setVisible(isTemplateSearch);
            templateListView.setManaged(isTemplateSearch);
        });
        
        // Initialize visibility based on initial selection
        boolean isTemplateSearch = "Template Search".equals(actionComboBox.getValue());
        searchTextField.setVisible(isTemplateSearch);
        searchTextField.setManaged(isTemplateSearch);
        templateListView.setVisible(isTemplateSearch);
        templateListView.setManaged(isTemplateSearch);
    }

    @FXML
    private void handleRunAction(ActionEvent event) {
        String action = actionComboBox.getValue();

        if ("OCR".equals(action)) {
            performOCROnSelection();
            return;
        }

        File template = templateListView.getSelectionModel().getSelectedItem();

        if (currentScreenshotFile == null) {
            log("Error: No screenshot loaded. Please drag and drop an image first.");
            return;
        }

        if (template == null) {
            log("Error: Please select a template from the list.");
            return;
        }

        log("Running action: " + action + " with template: " + template.getName());

        try {
            // Get relative path for OpenCvPatternLocator, e.g., /templates/home/petsButton.png
            String absPath = template.getAbsolutePath().replace("\\", "/");
            int templatesIndex = absPath.indexOf("/templates/");
            if (templatesIndex == -1) {
                log("Error: Selected template is not in a '/templates/' directory.");
                return;
            }
            String templateResourcePath = absPath.substring(templatesIndex);

            byte[] screenshotBytes = Files.readAllBytes(currentScreenshotFile.toPath());

            // Assuming we search the whole screenshot image
            Image img = screenshotImageView.getImage();
            int width = (int) img.getWidth();
            int height = (int) img.getHeight();

            ImageSearchResultData result = OpenCvPatternLocator.locatePattern(screenshotBytes, templateResourcePath,
                    new PointData(0, 0), new PointData(width, height), 70.0);

            if (result.isFound()) {
                log("Match Found! Coordinates: X=" + result.getPoint().getX() + ", Y=" + result.getPoint().getY()
                        + " (Accuracy: " + String.format("%.2f", result.getMatchPercentage()) + "%)");

                // Pin Crosshair at found location
                displayCrosshairAt((int) result.getPoint().getX(), (int) result.getPoint().getY());
            } else {
                log("No match found. Best Match: " + String.format("%.2f", result.getMatchPercentage()) + "%");
            }
        } catch (Exception e) {
            log("Error executing OpenCvPatternLocator: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean isCrosshairLocked = false;

    private void displayCrosshairAt(int imgX, int imgY) {
        if (screenshotImageView.getImage() == null || imageContainer == null)
            return;

        Image screenshotImage = screenshotImageView.getImage();

        double viewWidth = 316.0; // matched fitWidth
        double viewHeight = 576.0; // matched fitHeight

        // Offset logic - imageContainer vs clip
        double offsetX = (imageContainer.getWidth() - viewWidth) / 2.0;
        double offsetY = (imageContainer.getHeight() - viewHeight) / 2.0;

        double origWidth = screenshotImage.getWidth();
        double origHeight = screenshotImage.getHeight();

        double relX = (imgX / origWidth) * viewWidth;
        double relY = (imgY / origHeight) * viewHeight;

        double uiX = relX + offsetX;
        double uiY = relY + offsetY;

        // Force crosshair lines onto UI coordinate position
        crosshairX.setStartX(0);
        crosshairX.setEndX(imageContainer.getWidth());
        crosshairX.setStartY(uiY);
        crosshairX.setEndY(uiY);

        crosshairY.setStartX(uiX);
        crosshairY.setEndX(uiX);
        crosshairY.setStartY(0);
        crosshairY.setEndY(imageContainer.getHeight());

        coordinatesLabel.setText(String.format("X: %d\nY: %d", imgX, imgY));

        // Keep label within bounds
        double labelX = uiX + 15;
        double labelY = uiY + 15;
        if (labelX + coordinatesLabel.getWidth() > imageContainer.getWidth()) {
            labelX = uiX - 15 - coordinatesLabel.getWidth();
        }
        if (labelY + coordinatesLabel.getHeight() > imageContainer.getHeight()) {
            labelY = uiY - 15 - coordinatesLabel.getHeight();
        }

        coordinatesLabel.setLayoutX(labelX);
        coordinatesLabel.setLayoutY(labelY);

        crosshairPane.setVisible(true);
        crosshairX.setVisible(true);
        crosshairY.setVisible(true);
        coordinatesLabel.setVisible(true);

        isCrosshairLocked = true; // Block hover from hiding immediately
    }

    private void performOCROnSelection() {
        if (currentScreenshotFile == null || screenshotImageView.getImage() == null) {
            log("Error: No screenshot loaded.");
            return;
        }
        if (selectionBox.getWidth() <= 0 || selectionBox.getHeight() <= 0 || !selectionBox.isVisible()) {
            log("Error: No region selected. Please click and drag to select a region.");
            return;
        }

        double viewWidth = 316.0;
        double viewHeight = 576.0;

        double offsetX = (imageContainer.getWidth() - viewWidth) / 2.0;
        double offsetY = (imageContainer.getHeight() - viewHeight) / 2.0;

        Image img = screenshotImageView.getImage();
        double origWidth = img.getWidth();
        double origHeight = img.getHeight();

        double relStartX = selectionBox.getX() - offsetX;
        double relStartY = selectionBox.getY() - offsetY;

        int imgStartX = (int) ((relStartX / viewWidth) * origWidth);
        int imgStartY = (int) ((relStartY / viewHeight) * origHeight);

        double relEndX = (selectionBox.getX() + selectionBox.getWidth()) - offsetX;
        double relEndY = (selectionBox.getY() + selectionBox.getHeight()) - offsetY;

        int imgEndX = (int) ((relEndX / viewWidth) * origWidth);
        int imgEndY = (int) ((relEndY / viewHeight) * origHeight);

        imgStartX = Math.max(0, Math.min(imgStartX, (int) origWidth - 1));
        imgStartY = Math.max(0, Math.min(imgStartY, (int) origHeight - 1));
        imgEndX = Math.max(0, Math.min(imgEndX, (int) origWidth - 1));
        imgEndY = Math.max(0, Math.min(imgEndY, (int) origHeight - 1));

        int width = imgEndX - imgStartX;
        int height = imgEndY - imgStartY;

        if (width <= 0 || height <= 0) {
            log("Invalid region selected for OCR.");
            return;
        }

        log(String.format("Performing OCR on Region: TopLeft(%d, %d), BottomRight(%d, %d)", imgStartX, imgStartY,
                imgEndX, imgEndY));

        try {
            String result = OcrEngine.readFromFile(currentScreenshotFile, imgStartX, imgStartY,
                    width, height, "eng");
            log("OCR Output:\n" + result);
        } catch (Exception e) {
            log("OCR Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @FXML
    private void handleCaptureAdb(ActionEvent event) {
        AccountDescriptor selected = profileComboBox.getValue();
        if (selected == null) {
            log("Error: Please select a profile first.");
            return;
        }

        Thread captureThread = new Thread(() -> {
            javafx.application.Platform.runLater(() -> {
                btnCaptureAdb.setDisable(true);
                log("Capturing screenshot from " + selected.getName() + " (Emulator " + selected.getEmulatorNumber()
                        + ")...");
            });

            try {
                // Initialize backend if this is run independently before the engine starts
                try {
                    dev.frostguard.engine.emulator.EmulatorController.getInstance().captureScreen(selected.getEmulatorNumber());
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("Backend not initialised")) {
                        dev.frostguard.engine.emulator.EmulatorController.getInstance().initialize();
                    }
                }

                // We use EmulatorController to get the raw image dynamically
                dev.frostguard.api.domain.RawImageData rawImage = dev.frostguard.engine.emulator.EmulatorController.getInstance()
                        .captureScreen(selected.getEmulatorNumber());

                if (rawImage != null) {
                    java.awt.image.BufferedImage bImg = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(rawImage);

                    File tempFile = File.createTempFile("adb_screenshot", ".png");
                    javax.imageio.ImageIO.write(bImg, "png", tempFile);

                    javafx.application.Platform.runLater(() -> {
                        log("Success: Saved to temporary file " + tempFile.getAbsolutePath());
                        loadScreenshot(tempFile);
                    });
                } else {
                    javafx.application.Platform.runLater(() -> {
                        log("Error: Failed to capture ADB image.");
                    });
                }
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    log("Adb Capture Error: " + e.getMessage());
                    e.printStackTrace();
                });
            } finally {
                javafx.application.Platform.runLater(() -> {
                    btnCaptureAdb.setDisable(false);
                });
            }
        });
        captureThread.start();
    }

    private void log(String message) {
        logTextArea.appendText(message + "\n");
    }
}
