package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;
import dev.frostguard.engine.service.BranchEvaluator;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.service.TaskBuilderService;
import dev.frostguard.engine.service.TaskCodeGenerator;
import dev.frostguard.engine.service.TemplatePathResolver;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;

import javax.imageio.ImageIO;

/**
 * Controller for the n8n-style visual Task Builder with fullscreen support.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Properly clipped canvas with pan + zoom (no overflow)</li>
 *   <li>Fullscreen mode via a dedicated floating window</li>
 *   <li>Full-size emulator preview that resizes with the panel</li>
 *   <li>Draggable node cards with connection wires</li>
 * </ul>
 */
public class TaskBuilderLayoutController {

    // ===== Root =====
    @FXML private BorderPane rootPane;

    // ===== Toolbar =====
    @FXML private ComboBox<AccountDescriptor> profileComboBox;
    @FXML private Button btnCapture;
    @FXML private TextField taskNameField;
    @FXML private ToggleButton btnTogglePreview;
    @FXML private Button btnFullscreen;

    // ===== Canvas =====
    @FXML private StackPane canvasContainer;
    @FXML private Pane flowCanvas;
    @FXML private Label zoomLabel;

    // ===== Properties Drawer (bottom of canvas) =====
    @FXML private VBox propsDrawer;
    @FXML private Label propNodeIcon;
    @FXML private Label propNodeTitle;
    @FXML private Label propStatusLabel;
    @FXML private TextField nodeNameField;
    @FXML private VBox tapPropsBox;
    @FXML private TextField tapTlXField, tapTlYField, tapBrXField, tapBrYField;
    @FXML private VBox swipePropsBox;
    @FXML private TextField swipeStartXField, swipeStartYField, swipeEndXField, swipeEndYField;
    @FXML private VBox waitPropsBox;
    @FXML private TextField waitMsField;
    @FXML private VBox backPropsBox;
    @FXML private VBox ocrPropsBox;
    @FXML private TextField ocrTlXField, ocrTlYField, ocrBrXField, ocrBrYField;
    @FXML private ComboBox<String> ocrConditionCombo;
    @FXML private TextField ocrExpectedField;
    @FXML private VBox templatePropsBox;
    @FXML private ComboBox<String> templateComboBox;
    @FXML private Button templateBrowseBtn;
    @FXML private Label templateCustomPathLabel;
    @FXML private TextField templateThresholdField;
    @FXML private CheckBox templateGrayscaleCheck;
    @FXML private TextField tplTlXField, tplTlYField, tplBrXField, tplBrYField;
    @FXML private TextField templateAttemptsField;
    @FXML private TextField templateDelayField;
    @FXML private CheckBox templateTapIfFoundCheck;
    @FXML private TextField templateOffsetXField, templateOffsetYField;

    /** Sentinel prefix stored in templatePath param to distinguish custom file paths from TemplatesEnum names. */
    private static final String CUSTOM_TEMPLATE_PREFIX = TemplatePathResolver.FILE_PREFIX;
    // ===== Preview Column (right) =====
    @FXML private VBox rightPanel;

    // ===== Preview =====
    @FXML private StackPane previewContainer;
    @FXML private ImageView previewImageView;
    @FXML private Label previewHint;
    @FXML private Pane previewCrosshairPane;
    @FXML private Rectangle selectionBox;
    @FXML private Line previewCrossX;
    @FXML private Line previewCrossY;
    @FXML private Label previewCoordsLabel;

    // ===== Status =====
    @FXML private Label statusLabel;
    @FXML private Label nodeCountLabel;

    // ===== State =====
    private final TaskBuilderService builderService = new TaskBuilderService();
    private final Map<Integer, VBox> nodeCards = new LinkedHashMap<>();
    private final Map<Integer, Circle> outputPorts = new HashMap<>();
    private final Map<Integer, Circle> outputPortsFalse = new HashMap<>(); // "No" branch port (OCR etc.)
    private final Map<Integer, Circle> inputPorts = new HashMap<>();
    private final Map<Integer, HBox> hoverMenus = new HashMap<>();
    private final Map<String, CubicCurve> connectionWires = new HashMap<>();
    private final List<javafx.scene.Node> wireOverlays = new ArrayList<>(); // wire label bg+text

    private AutomationStep selectedNode = null;
    private double ocrDragStartX = 0, ocrDragStartY = 0;
    private boolean hasPreviewImage = false;
    private boolean isBinding = false;

    // Start location toggle
    private String startLocationSetting = null; // null="Any", "World", "City"
    private Label startTitleLabel;               // dynamic title in the start card
    private final List<Button> startSegButtons = new ArrayList<>(); // segmented toggle buttons

    // Canvas transform state
    private double canvasOffsetX = 0, canvasOffsetY = 0;
    private double zoomScale = 1.0;
    private double panStartX, panStartY;
    private double panStartOffsetX, panStartOffsetY;
    private boolean isPanning = false;

    // Node drag
    private VBox draggingCard = null;
    private AutomationStep draggingNode = null;
    private double dragOffsetX, dragOffsetY;

    // Connection drag
    private CubicCurve dragWire = null;
    private int dragWireSourceId = -1;
    private boolean dragWireIsFalseBranch = false;

    // Fullscreen — expands within the same window by hiding launcher chrome
    private boolean isFullscreen = false;
    private final List<javafx.scene.Node> hiddenLauncherChrome = new ArrayList<>();
    private javafx.event.EventHandler<javafx.scene.input.KeyEvent> fullscreenEscHandler;

    // Layout
    private static final double NODE_START_X = 180;
    private static final double NODE_START_Y = 150;
    private static final double NODE_SPACING_X = 260;
    private static final double START_X = 40;
    private static final double START_Y = 170;

    // Emulator aspect ratio (720x1280 = 9:16)
    private static final double EMULATOR_ASPECT = 9.0 / 16.0;

    @FXML
    public void initialize() {
        setupProfiles();
        setupCanvasClipping();
        setupCanvasInteractions();
        setupPreviewPanelSizing();
        setupNodeNameField();
        drawCanvasGrid();
        drawStartNode();
        if (templateComboBox != null) {
            for (TemplatesEnum t : TemplatesEnum.values()) {
                templateComboBox.getItems().add(t.name());
            }
            javafx.util.Callback<ListView<String>, ListCell<String>> cellFactory = lv -> new ListCell<>() {
                private final ImageView imageView = new ImageView();
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item);
                        try {
                            TemplatesEnum t = TemplatesEnum.valueOf(item);
                            java.net.URL url = TemplatesEnum.class.getResource(t.resourcePath());
                            if (url != null) {
                                Image img = new Image(url.toExternalForm(), 24, 24, true, true);
                                imageView.setImage(img);
                                setGraphic(imageView);
                            } else {
                                setGraphic(null);
                            }
                        } catch (Exception ex) {
                            setGraphic(null);
                        }
                    }
                }
            };
            templateComboBox.setCellFactory(cellFactory);
            templateComboBox.setButtonCell(cellFactory.call(null));
        }
        if (ocrConditionCombo != null) {
            ocrConditionCombo.setItems(FXCollections.observableArrayList("CONTAINS", "EQUALS", "STARTS_WITH", "ENDS_WITH", "NOT_CONTAINS"));
            ocrConditionCombo.setValue("CONTAINS");
        }
        addAutoApplyListeners();
        setStatus("Ready — add nodes from the toolbox");
    }

    private void setupNodeNameField() {
        if (nodeNameField == null) {
            return;
        }

        UnaryOperator<TextFormatter.Change> lengthFilter = change -> {
            String nextText = change.getControlNewText();
            return nextText.length() <= AutomationStep.NODE_NAME_MAX_LENGTH ? change : null;
        };
        nodeNameField.setTextFormatter(new TextFormatter<>(lengthFilter));
        nodeNameField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedNode == null || isBinding) {
                return;
            }
            selectedNode.setNodeName(newValue);
            refreshCard(selectedNode);
        });
    }

    private void addAutoApplyListeners() {
        javafx.beans.value.ChangeListener<String> tapListener = (obs, oldV, newV) -> { if (!isBinding) handleApplyTapProps(null); };
        if (tapTlXField != null) tapTlXField.textProperty().addListener(tapListener);
        if (tapTlYField != null) tapTlYField.textProperty().addListener(tapListener);
        if (tapBrXField != null) tapBrXField.textProperty().addListener(tapListener);
        if (tapBrYField != null) tapBrYField.textProperty().addListener(tapListener);

        javafx.beans.value.ChangeListener<String> swipeListener = (obs, oldV, newV) -> { if (!isBinding) handleApplySwipeProps(null); };
        if (swipeStartXField != null) swipeStartXField.textProperty().addListener(swipeListener);
        if (swipeStartYField != null) swipeStartYField.textProperty().addListener(swipeListener);
        if (swipeEndXField != null) swipeEndXField.textProperty().addListener(swipeListener);
        if (swipeEndYField != null) swipeEndYField.textProperty().addListener(swipeListener);

        javafx.beans.value.ChangeListener<String> waitListener = (obs, oldV, newV) -> { if (!isBinding) handleApplyWaitProps(null); };
        if (waitMsField != null) waitMsField.textProperty().addListener(waitListener);

        javafx.beans.value.ChangeListener<String> ocrListener = (obs, oldV, newV) -> { if (!isBinding) handleApplyOcrProps(null); };
        if (ocrTlXField != null) ocrTlXField.textProperty().addListener(ocrListener);
        if (ocrTlYField != null) ocrTlYField.textProperty().addListener(ocrListener);
        if (ocrBrXField != null) ocrBrXField.textProperty().addListener(ocrListener);
        if (ocrBrYField != null) ocrBrYField.textProperty().addListener(ocrListener);
        if (ocrExpectedField != null) ocrExpectedField.textProperty().addListener(ocrListener);
        if (ocrConditionCombo != null) {
            ocrConditionCombo.valueProperty().addListener((obs, oldV, newV) -> { if (!isBinding) handleApplyOcrProps(null); });
        }

        javafx.beans.value.ChangeListener<String> tplListener = (obs, oldV, newV) -> { if (!isBinding) handleApplyTemplateProps(null); };
        if (templateThresholdField != null) templateThresholdField.textProperty().addListener(tplListener);
        if (tplTlXField != null) tplTlXField.textProperty().addListener(tplListener);
        if (tplTlYField != null) tplTlYField.textProperty().addListener(tplListener);
        if (tplBrXField != null) tplBrXField.textProperty().addListener(tplListener);
        if (tplBrYField != null) tplBrYField.textProperty().addListener(tplListener);
        if (templateAttemptsField != null) templateAttemptsField.textProperty().addListener(tplListener);
        if (templateDelayField != null) templateDelayField.textProperty().addListener(tplListener);
        if (templateOffsetXField != null) templateOffsetXField.textProperty().addListener(tplListener);
        if (templateOffsetYField != null) templateOffsetYField.textProperty().addListener(tplListener);
        if (templateComboBox != null) {
            templateComboBox.valueProperty().addListener((obs, oldV, newV) -> { if (!isBinding) handleApplyTemplateProps(null); });
        }
        if (templateGrayscaleCheck != null) {
            templateGrayscaleCheck.selectedProperty().addListener((obs, oldV, newV) -> { if (!isBinding) handleApplyTemplateProps(null); });
        }
        if (templateTapIfFoundCheck != null) {
            templateTapIfFoundCheck.selectedProperty().addListener((obs, oldV, newV) -> { if (!isBinding) handleApplyTemplateProps(null); });
        }
    }

    /**
     * Sets up the preview panel sizing so it respects the emulator aspect ratio
     * and fits within the available tab space without pushing the bottom bar out.
     */
    private void setupPreviewPanelSizing() {
        // Set initial compact width for non-fullscreen (embedded) mode
        rightPanel.setMinWidth(0);

        // Dynamically adjust the rightPanel width based on available height
        // so the preview maintains the correct 9:16 aspect ratio.
        previewContainer.heightProperty().addListener((obs, oldH, newH) -> {
            if (!isFullscreen && newH.doubleValue() > 0) {
                double idealWidth = newH.doubleValue() * EMULATOR_ASPECT;
                // Clamp between reasonable bounds
                idealWidth = Math.max(120, Math.min(idealWidth, 400));
                rightPanel.setPrefWidth(idealWidth);
                rightPanel.setMaxWidth(idealWidth);
            }
        });
    }

    @FXML
    private void handleTogglePreview(ActionEvent event) {
        if (rightPanel != null) {
            boolean show = btnTogglePreview.isSelected();
            rightPanel.setVisible(show);
            rightPanel.setManaged(show);
        }
    }

    // ==================== CANVAS CLIPPING (fixes overflow/underflow) ====================

    private void setupCanvasClipping() {
        // Clip the canvas container to its own bounds => no overflow
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(canvasContainer.widthProperty());
        clip.heightProperty().bind(canvasContainer.heightProperty());
        canvasContainer.setClip(clip);

        // The flowCanvas is positioned using translate/scale transforms, so its
        // pref/min/max sizes should NOT influence the HBox layout at all.
        // Config pref to USE_COMPUTED_SIZE and min/max to 0 ensures the StackPane
        // parent decides the size, not the other way around.
        flowCanvas.setMinSize(0, 0);
        flowCanvas.setPrefSize(0, 0);
        flowCanvas.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        flowCanvas.setManaged(false); // remove from StackPane layout entirely — position via transforms

        canvasContainer.setMinSize(0, 0);
    }

    // ==================== CANVAS PAN & ZOOM ====================

    private void setupCanvasInteractions() {
        // Zoom via scroll
        canvasContainer.setOnScroll(this::handleZoomScroll);

        // Pan via right-click, middle-click, Ctrl+drag, or simply left-click drag on canvas background
        flowCanvas.setOnMousePressed(e -> {
            boolean isBackgroundClick = e.isPrimaryButtonDown() && e.getTarget() == flowCanvas;
            if (e.isMiddleButtonDown() || e.isSecondaryButtonDown() || e.isControlDown() || isBackgroundClick) {
                isPanning = true;
                panStartX = e.getScreenX();
                panStartY = e.getScreenY();
                panStartOffsetX = canvasOffsetX;
                panStartOffsetY = canvasOffsetY;
                flowCanvas.setCursor(Cursor.CLOSED_HAND);
                
                if (isBackgroundClick) {
                    deselectNode();
                }
                e.consume();
            }
        });

        flowCanvas.setOnMouseDragged(e -> {
            if (isPanning) {
                double dx = e.getScreenX() - panStartX;
                double dy = e.getScreenY() - panStartY;
                canvasOffsetX = panStartOffsetX + dx;
                canvasOffsetY = panStartOffsetY + dy;
                applyCanvasTransform();
                e.consume();
            }
        });

        flowCanvas.setOnMouseReleased(e -> {
            if (isPanning) {
                isPanning = false;
                flowCanvas.setCursor(Cursor.DEFAULT);
                e.consume();
            }
            if (dragWire != null) {
                finishConnectionDrag(e);
            }
        });

        flowCanvas.setOnMouseMoved(e -> {
            if (dragWire != null) {
                updateDragWire(e.getX(), e.getY());
            }
        });
    }

    private void handleZoomScroll(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
        double newScale = zoomScale * factor;
        // Clamp between 30% and 250%
        newScale = Math.max(0.30, Math.min(2.50, newScale));

        // Zoom toward mouse pointer position
        double mouseX = e.getX();
        double mouseY = e.getY();

        double scaleChange = newScale / zoomScale;
        canvasOffsetX = mouseX - scaleChange * (mouseX - canvasOffsetX);
        canvasOffsetY = mouseY - scaleChange * (mouseY - canvasOffsetY);

        zoomScale = newScale;
        applyCanvasTransform();
        if (zoomLabel != null) zoomLabel.setText(Math.round(zoomScale * 100) + "%");
        e.consume();
    }

    // ==================== SESSION & EXPORT ====================

    @FXML
    private void handleNewSession(ActionEvent e) {
        String name = taskNameField.getText();
        if (name == null || name.trim().isEmpty()) name = "Untitled Task";
        AccountDescriptor sel = profileComboBox.getValue();
        if (sel == null) { setStatus("⚠ Select a profile"); return; }
        handleClearAll(null);
        builderService.startSession(name.trim(), sel.getEmulatorNumber());
        applyStartLocation(null); // reset start location for new session
        updateNodeCount();
        setStatus("🆕 Session: \"" + name.trim() + "\"");
    }

    @FXML private void handleCapture(ActionEvent e) {
        AccountDescriptor sel = profileComboBox.getValue();
        if (sel == null) { setStatus("⚠ Select a profile"); return; }
        btnCapture.setDisable(true);
        setStatus("📷 Capturing...");
        Thread t = new Thread(() -> { capturePreview(); Platform.runLater(() -> btnCapture.setDisable(false)); });
        t.setDaemon(true); t.start();
    }

    private void capturePreview() {
        try {
            AccountDescriptor sel = profileComboBox.getValue();
            if (sel == null) return;
            RawImageData raw = EmulatorController.getInstance().captureScreen(sel.getEmulatorNumber());
            if (raw != null) {
                BufferedImage bi = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(raw);
                Image fx = toFxImage(bi);
                Platform.runLater(() -> { previewImageView.setImage(fx); previewHint.setVisible(false); hasPreviewImage=true; setStatus("📷 Preview updated"); });
            } else { Platform.runLater(() -> setStatus("❌ Capture failed")); }
        } catch (Exception e) { Platform.runLater(() -> setStatus("❌ " + e.getMessage())); }
    }

    @FXML
    private void handleExportJson(ActionEvent e) {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def == null || def.getNodes().isEmpty()) {
            setStatus("Error: No task active or no nodes present.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Java Bot Task");
        String safeName = taskNameField.getText().replaceAll("[^a-zA-Z0-9_]", "");
        if (safeName.isEmpty()) safeName = "GeneratedTask";
        fileChooser.setInitialFileName(safeName + ".java");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Source Files", "*.java"));
        File file = fileChooser.showSaveDialog(rootPane.getScene().getWindow());

        if (file != null) {
            String className = file.getName().replace(".java", "");
            try (PrintWriter writer = new PrintWriter(file)) {
                TaskCodeGenerator generator = new TaskCodeGenerator();
                String code = generator.generate(def, className, taskNameField.getText());
                writer.write(code);
            } catch (Exception ex) {
                setStatus("Export failed: " + ex.getMessage());
                return;
            }
            setStatus("Exported Java class to: " + file.getName());
        }
    }

    @FXML
    private void handleSaveCustomTask(ActionEvent e) {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def == null || def.getNodes().isEmpty()) {
            setStatus("Error: No task active or no nodes present.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Custom Task Definition");
        Path customDir = builderService.getCustomTasksDirectory();
        if (customDir != null && customDir.toFile().isDirectory()) {
            fileChooser.setInitialDirectory(customDir.toFile());
        }
        String safeName = sanitizeFileBase(taskNameField.getText());
        fileChooser.setInitialFileName(safeName + ".json");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));

        File file = fileChooser.showSaveDialog(rootPane.getScene().getWindow());
        if (file == null) {
            return;
        }

        try {
            String name = taskNameField.getText();
            TaskBuilderService.CustomTaskSaveResult saved =
                    builderService.saveCurrentTaskToCustomTasks(name, ensureJsonExtension(file.toPath()));
            setStatus("Saved custom task: " + saved.className() + " (" + saved.builderFile().getFileName() + ")");
        } catch (Exception ex) {
            setStatus("Save failed: " + ex.getMessage());
        }
    }

    @FXML
    private void handleImportBuilderTask(ActionEvent e) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Task Builder Definition");
        Path customDir = builderService.getCustomTasksDirectory();
        if (customDir != null && customDir.toFile().isDirectory()) {
            fileChooser.setInitialDirectory(customDir.toFile());
        }
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));

        File file = fileChooser.showOpenDialog(rootPane.getScene().getWindow());
        if (file == null) {
            return;
        }

        AccountDescriptor selected = profileComboBox.getValue();
        String emulator = selected != null ? selected.getEmulatorNumber() : builderService.getActiveEmulatorNumber();
        if (emulator == null) {
            emulator = "0";
        }

        try {
            AutomationBlueprint imported = builderService.loadDefinition(file, emulator);
            renderImportedDefinition(imported);
            setStatus("Imported builder task: " + imported.getName());
        } catch (Exception ex) {
            setStatus("Import failed: " + ex.getMessage());
        }
    }

    private String sanitizeFileBase(String rawName) {
        String sanitized = rawName == null ? "" : rawName.replaceAll("[^a-zA-Z0-9_]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "GeneratedTask" : sanitized;
    }

    private Path ensureJsonExtension(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".json")) {
            return path;
        }
        Path parent = path.getParent();
        Path withExtension = Paths.get(fileName + ".json");
        return parent == null ? withExtension : parent.resolve(withExtension);
    }

    private void renderImportedDefinition(AutomationBlueprint definition) {
        clearFlowNodesFromCanvas();
        deselectNode();

        taskNameField.setText(definition.getName() != null ? definition.getName() : "Untitled Task");
        applyImportedStartLocation(definition.getStartLocation());

        for (AutomationStep node : definition.getNodes()) {
            createNodeCard(node);
        }

        Platform.runLater(() -> {
            rebuildAllWires();
            updateNodeCount();
        });
    }

    private void clearFlowNodesFromCanvas() {
        for (int id : new ArrayList<>(nodeCards.keySet())) {
            flowCanvas.getChildren().removeAll(nodeCards.get(id), inputPorts.get(id), outputPorts.get(id));
            Circle falsePort = outputPortsFalse.get(id);
            if (falsePort != null) {
                flowCanvas.getChildren().remove(falsePort);
            }
        }
        nodeCards.clear();
        inputPorts.clear();
        outputPorts.clear();
        outputPortsFalse.clear();

        hoverMenus.values().forEach(menu -> flowCanvas.getChildren().remove(menu));
        hoverMenus.clear();

        connectionWires.values().forEach(wire -> flowCanvas.getChildren().remove(wire));
        connectionWires.clear();
        wireOverlays.forEach(overlay -> flowCanvas.getChildren().remove(overlay));
        wireOverlays.clear();
    }

    private void applyImportedStartLocation(String startLocation) {
        startLocationSetting = switch (startLocation != null ? startLocation : "ANY") {
            case "WORLD" -> "World";
            case "HOME" -> "City";
            default -> null;
        };
        refreshStartLocationUi();
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def != null) {
            def.setStartLocation(startLocation != null ? startLocation : "ANY");
        }
    }

    private void refreshStartLocationUi() {
        String[] options = { "World", "City" };
        for (int i = 0; i < startSegButtons.size(); i++) {
            Button btn = startSegButtons.get(i);
            boolean active = options[i].equals(startLocationSetting);
            btn.getStyleClass().clear();
            btn.getStyleClass().add(active ? "start-seg-btn-active" : "start-seg-btn");
        }

        String titleText = switch (startLocationSetting != null ? startLocationSetting : "") {
            case "World" -> "▶ Start from World";
            case "City" -> "▶ Start from City";
            default -> "▶ Start from Any";
        };
        if (startTitleLabel != null) {
            startTitleLabel.setText(titleText);
        }
    }

    private void applyCanvasTransform() {
        flowCanvas.setTranslateX(canvasOffsetX);
        flowCanvas.setTranslateY(canvasOffsetY);
        flowCanvas.setScaleX(zoomScale);
        flowCanvas.setScaleY(zoomScale);
    }

    @FXML private void handleZoomIn(ActionEvent e) {
        zoomScale = Math.min(2.5, zoomScale * 1.15);
        applyCanvasTransform();
        if (zoomLabel != null) zoomLabel.setText(Math.round(zoomScale * 100) + "%");
    }

    @FXML private void handleZoomOut(ActionEvent e) {
        zoomScale = Math.max(0.3, zoomScale * 0.85);
        applyCanvasTransform();
        if (zoomLabel != null) zoomLabel.setText(Math.round(zoomScale * 100) + "%");
    }

    @FXML private void handleZoomFit(ActionEvent e) {
        zoomScale = 1.0;
        canvasOffsetX = 0;
        canvasOffsetY = 0;
        applyCanvasTransform();
        if (zoomLabel != null) zoomLabel.setText("100%");
    }

    // ==================== CANVAS GRID & START NODE ====================

    private void drawCanvasGrid() {
        for (double x = -500; x < 5000; x += 25) {
            for (double y = -500; y < 3500; y += 25) {
                Circle dot = new Circle(x, y, 1.0);
                dot.getStyleClass().add("canvas-grid-dot");
                dot.setMouseTransparent(true);
                flowCanvas.getChildren().add(dot);
            }
        }
    }

    private void drawStartNode() {
        VBox startCard = new VBox(0);
        startCard.getStyleClass().add("flow-start-card");
        startCard.setLayoutX(START_X);
        startCard.setLayoutY(START_Y);

        // ── Header (dynamic title only, no subtitle) ──
        HBox header = new HBox(6);
        header.getStyleClass().add("flow-start-header");
        header.setAlignment(Pos.CENTER_LEFT);
        startTitleLabel = new Label("▶ Start from Any");
        startTitleLabel.getStyleClass().add("flow-start-title");
        header.getChildren().add(startTitleLabel);

        // ── Body: togglable segmented buttons (World | City) ──
        HBox body = new HBox();
        body.getStyleClass().add("flow-start-body");
        body.setAlignment(Pos.CENTER);
        body.managedProperty().bind(body.visibleProperty());

        // Toggle visibility on header click
        header.setOnMouseClicked(ev -> {
            boolean nextVisible = !body.isVisible();
            body.setVisible(nextVisible);
            if (nextVisible) {
                header.setStyle("-fx-background-radius: 8 8 0 0;");
            } else {
                header.setStyle("-fx-background-radius: 8;");
            }
            ev.consume();
        });

        HBox segContainer = new HBox();
        segContainer.getStyleClass().add("start-seg-container");

        startSegButtons.clear();
        String[] options = { "World", "City" };
        for (String opt : options) {
            Button btn = new Button(opt);
            boolean isActive = opt.equals(startLocationSetting);
            btn.getStyleClass().add(isActive ? "start-seg-btn-active" : "start-seg-btn");
            btn.setOnAction(ev -> applyStartLocation(opt));
            startSegButtons.add(btn);
            segContainer.getChildren().add(btn);
        }

        body.getChildren().add(segContainer);
        startCard.getChildren().addAll(header, body);

        // ── Output port ──
        Circle startPort = new Circle(6);
        startPort.getStyleClass().add("flow-port");
        startPort.setCenterX(START_X + 140);
        startPort.setCenterY(START_Y + 18);
        startPort.setOnMouseClicked(ev -> {
            startConnectionDrag(0, false);
            ev.consume();
        });

        flowCanvas.getChildren().addAll(startCard, startPort);
    }

    /**
     * Applies or toggles the selected start location.
     * Clicking the already-active button deselects it (reverts to "Any").
     */
    private void applyStartLocation(String option) {
        // Toggle: if already selected, deselect to "Any"; null resets to Any
        if (option != null && option.equals(startLocationSetting)) {
            startLocationSetting = null;
        } else {
            startLocationSetting = option;
        }

        // Update segmented button styles
        String[] options = { "World", "City" };
        for (int i = 0; i < startSegButtons.size(); i++) {
            Button btn = startSegButtons.get(i);
            boolean isActive = options[i].equals(startLocationSetting);
            btn.getStyleClass().clear();
            btn.getStyleClass().add(isActive ? "start-seg-btn-active" : "start-seg-btn");
        }

        // Update title text
        String titleText = switch (startLocationSetting != null ? startLocationSetting : "") {
            case "World" -> "▶ Start from World";
            case "City"  -> "▶ Start from City";
            default      -> "▶ Start from Any";
        };
        if (startTitleLabel != null) startTitleLabel.setText(titleText);

        // Persist to definition
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def != null) {
            String mapped = switch (startLocationSetting != null ? startLocationSetting : "") {
                case "World" -> "WORLD";
                case "City"  -> "HOME";
                default      -> "ANY";
            };
            def.setStartLocation(mapped);
        }

        setStatus(startLocationSetting != null ? "Start location: " + startLocationSetting : "Start location: Any");
    }

    // ==================== EMULATOR PREVIEW ====================
    // Preview uses fixed fitWidth/fitHeight (316x576) set in FXML, matching the debugging tab.

    // ==================== FULLSCREEN MODE ====================

    @FXML
    private void handleToggleFullscreen(ActionEvent e) {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        if (isFullscreen) return;

        // Traverse up from rootPane to find the launcher's BorderPane:
        // rootPane → AnchorPane (mainContentPane) → StackPane (centerStack) → BorderPane (launcher root)
        BorderPane launcherRoot = findLauncherBorderPane();
        if (launcherRoot == null) {
            setStatus("⚠ Cannot enter fullscreen — launcher not found");
            return;
        }

        isFullscreen = true;
        hiddenLauncherChrome.clear();

        // Hide launcher chrome: top (title bar), left (sidebar), bottom (status bar)
        Node top = launcherRoot.getTop();
        Node left = launcherRoot.getLeft();
        Node bottom = launcherRoot.getBottom();

        if (top != null) {
            top.setVisible(false);
            top.setManaged(false);
            hiddenLauncherChrome.add(top);
        }
        if (left != null) {
            left.setVisible(false);
            left.setManaged(false);
            hiddenLauncherChrome.add(left);
        }
        if (bottom != null) {
            bottom.setVisible(false);
            bottom.setManaged(false);
            hiddenLauncherChrome.add(bottom);
        }

        // Preview column — wider in fullscreen
        rightPanel.setPrefWidth(390);
        rightPanel.setMaxWidth(390);

        // Update button text
        btnFullscreen.setText("✕ Exit Fullscreen");

        // ESC to exit fullscreen
        Scene scene = rootPane.getScene();
        if (scene != null) {
            fullscreenEscHandler = ke -> {
                if (ke.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    exitFullscreen();
                    ke.consume();
                }
            };
            scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, fullscreenEscHandler);
        }

        setStatus("Fullscreen mode — press ESC or click Exit to return");
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;

        // Mark as non-fullscreen BEFORE restoring so the height listener
        // can recalculate the proper aspect-ratio-based width.
        isFullscreen = false;

        // Restore all hidden launcher chrome
        for (javafx.scene.Node node : hiddenLauncherChrome) {
            node.setVisible(true);
            node.setManaged(true);
        }
        hiddenLauncherChrome.clear();

        // Remove ESC handler
        Scene scene = rootPane.getScene();
        if (scene != null && fullscreenEscHandler != null) {
            scene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, fullscreenEscHandler);
            fullscreenEscHandler = null;
        }

        // Reset preview column — let the height listener recalculate proper width
        rightPanel.setMaxWidth(400);
        btnFullscreen.setText("⛶ Fullscreen");
        setStatus("Exited fullscreen");
    }

    /**
     * Traverses up from rootPane to find the launcher's root BorderPane.
     * Expected hierarchy: rootPane → AnchorPane → StackPane → BorderPane (launcher root)
     */
    private BorderPane findLauncherBorderPane() {
        javafx.scene.Node current = rootPane.getParent();
        // Walk up at most 5 levels to find the BorderPane
        for (int i = 0; i < 5 && current != null; i++) {
            if (current instanceof BorderPane bp && bp != rootPane) {
                return bp;
            }
            current = current.getParent();
        }
        return null;
    }

    // ==================== NODE CREATION ====================

    @FXML private void handleAddTapNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.TAP_POINT); }
    @FXML private void handleAddWaitNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.WAIT); }
    @FXML private void handleAddSwipeNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.SWIPE); }
    @FXML private void handleAddBackNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.BACK_BUTTON); }
    @FXML private void handleAddOcrNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.OCR_READ); }
    @FXML private void handleAddTemplateNode(ActionEvent e) { addNodeToCanvas(FlowStepKind.TEMPLATE_SEARCH); }

    private void addNodeToCanvas(FlowStepKind type) {
        ensureSession();

        AutomationStep node = new AutomationStep(0, type);
        switch (type) {
            case TAP_POINT -> { node.setParam("x", "0"); node.setParam("y", "0"); }
            case WAIT -> node.setParam("durationMs", "1000");
            case SWIPE -> { node.setParam("startX","0"); node.setParam("startY","0");
                           node.setParam("endX","0"); node.setParam("endY","0"); }
            default -> {}
        }

        builderService.addNode(node);

        int count = builderService.getCurrentDefinition().getNodes().size();
        // Stagger position so nodes don't pile up (no auto-wire either)
        int col = (count - 1) % 4;
        int row = (count - 1) / 4;
        node.setCanvasX(NODE_START_X + col * NODE_SPACING_X);
        node.setCanvasY(NODE_START_Y + row * 120);

        createNodeCard(node);
        Platform.runLater(() -> rebuildAllWires());
        updateNodeCount();
        selectNodeOnCanvas(node);
        setStatus("Added: " + type.getDisplayName() + " — connect it manually by dragging ports.");
    }

    private VBox createNodeCard(AutomationStep node) {
        VBox card = new VBox(0);
        card.setLayoutX(node.getCanvasX());
        card.setLayoutY(node.getCanvasY());

        boolean isBackButton = (node.getType() == FlowStepKind.BACK_BUTTON);

        if (isBackButton) {
            card.getStyleClass().add("flow-node-back-card");
            card.setSpacing(0);
            card.setAlignment(Pos.CENTER);
            
            javafx.scene.shape.SVGPath path = new javafx.scene.shape.SVGPath();
            // A nice left-pointing arrow for "Back"
            path.setContent("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z");
            path.getStyleClass().add("flow-node-back-icon");
            path.setScaleX(0.9);
            path.setScaleY(0.9);
            
            Label lbl = new Label("Back");
            lbl.setStyle("-fx-text-fill: white; -fx-font-size: 9px; -fx-font-weight: 600; -fx-opacity: 0.8;");
            
            card.getChildren().addAll(path, lbl);
        } else {
            card.getStyleClass().add("flow-node");

            // ── Accent stripe (top border color indicator) ──
            String[] accentColor = { getAccentColor(node.getType()) };

            // ── Header ──
            HBox header = new HBox(8);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getStyleClass().addAll("flow-node-header", getHeaderStyle(node.getType()));
            header.setPadding(new Insets(9, 14, 9, 14));

            // Type icon in a circle badge
            Label iconLbl = new Label(getIcon(node.getType()));
            iconLbl.setStyle("-fx-font-size: 14px;");

            VBox titleBlock = new VBox(1);
            Label title = new Label(node.getType().getDisplayName());
            title.getStyleClass().add("flow-node-type-label");
            Label idLbl = new Label("ID: #" + node.getId());
            idLbl.getStyleClass().add("flow-node-id-label");
            Label nameLbl = new Label(node.getNodeName());
            nameLbl.setId("node-name-label");
            nameLbl.getStyleClass().add("flow-node-name-label");
            boolean hasName = !node.getNodeName().isEmpty();
            nameLbl.setVisible(hasName);
            nameLbl.setManaged(hasName);
            titleBlock.getChildren().addAll(title, idLbl, nameLbl);

            Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);

            // Status indicator dot
            Circle statusDot = new Circle(5);
            statusDot.setFill(node.isExecuted() ? Color.web("#59ba59") : Color.web("#4a5568"));
            statusDot.setStroke(Color.TRANSPARENT);

            header.getChildren().addAll(iconLbl, titleBlock, sp, statusDot);

            // ── Divider ──
            javafx.scene.shape.Line divider = new javafx.scene.shape.Line();
            divider.setStartX(0); divider.setEndX(220);
            divider.setStroke(Color.web(accentColor[0], 0.25));
            divider.setStrokeWidth(1);
            divider.setMouseTransparent(true);

            // ── Body ──
            VBox body = new VBox(4);
            body.getStyleClass().add("flow-node-body");
            body.setPadding(new Insets(10, 14, 10, 14));
            Label paramLbl = new Label(node.getSummary());
            paramLbl.getStyleClass().add("flow-node-param-label");
            paramLbl.setWrapText(true);
            body.getChildren().add(paramLbl);

            // ── Branching badge (OCR, Template Search) ──
            if (node.getType() == FlowStepKind.OCR_READ || node.getType() == FlowStepKind.TEMPLATE_SEARCH) {
                HBox branchBadge = new HBox(6);
                branchBadge.setAlignment(Pos.CENTER_LEFT);
                branchBadge.setPadding(new Insets(0, 0, 0, 0));
                String yesText = node.getType() == FlowStepKind.TEMPLATE_SEARCH ? "● Found" : "● Yes";
                String noText  = node.getType() == FlowStepKind.TEMPLATE_SEARCH ? "● Not Found" : "● No";
                Label yes = new Label(yesText); yes.setStyle("-fx-text-fill: #27ae60; -fx-font-size: 10px; -fx-font-weight: bold;");
                Label no  = new Label(noText);  no.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 10px; -fx-font-weight: bold;");
                branchBadge.getChildren().addAll(yes, new Label("  "), no);
                body.getChildren().add(branchBadge);
            }

            card.getChildren().addAll(header, divider, body);
        }

        // ── Ports ──
        // Input port (left side)
        Circle inPort = new Circle(8);
        inPort.getStyleClass().add("flow-port");
        inPort.setOnMouseClicked(ev -> {
            if (dragWire != null && dragWireSourceId >= 0) {
                completeConnection(node.getId());
                ev.consume();
            }
        });

        // Output port - default / "Yes" branch
        Circle outPort = new Circle(8);
        outPort.getStyleClass().add("flow-port");
        if (node.getType() == FlowStepKind.OCR_READ || node.getType() == FlowStepKind.TEMPLATE_SEARCH) {
            outPort.getStyleClass().add("flow-port-yes");
        }
        if (node.getNextNodeId() > 0) outPort.getStyleClass().add("flow-port-connected");
        outPort.setOnMouseClicked(ev -> {
            startConnectionDrag(node.getId(), false);
            ev.consume();
        });

        nodeCards.put(node.getId(), card);
        inputPorts.put(node.getId(), inPort);
        outputPorts.put(node.getId(), outPort);
        flowCanvas.getChildren().addAll(card, inPort, outPort);

        // ── Hover Menu ──
        HBox hoverMenu = new HBox();
        hoverMenu.getStyleClass().add("hover-menu-container");
        if (isBackButton) {
            // center on 60 width
            hoverMenu.layoutXProperty().bind(card.layoutXProperty().add(30).subtract(hoverMenu.widthProperty().divide(2)));
        } else {
            hoverMenu.layoutXProperty().bind(card.layoutXProperty().add(110).subtract(hoverMenu.widthProperty().divide(2)));
        }
        hoverMenu.layoutYProperty().bind(card.layoutYProperty().subtract(34));
        hoverMenu.visibleProperty().bind(javafx.beans.binding.Bindings.or(card.hoverProperty(), hoverMenu.hoverProperty()));

        javafx.scene.shape.SVGPath playIcon = new javafx.scene.shape.SVGPath();
        playIcon.setContent("M 8 5 L 19 12 L 8 19 Z");
        playIcon.getStyleClass().add("hover-menu-svg");
        playIcon.setScaleX(0.80);
        playIcon.setScaleY(0.80);
        
        Button btnExec = new Button();
        btnExec.setGraphic(playIcon);
        btnExec.getStyleClass().add("hover-menu-btn");
        btnExec.setOnAction(ev -> execNode(node));

        javafx.scene.shape.SVGPath delIcon = new javafx.scene.shape.SVGPath();
        delIcon.setContent("M 6 19 C 6 20.1 6.9 21 8 21 L 16 21 C 17.1 21 18 20.1 18 19 L 18 7 L 6 7 L 6 19 Z M 19 4 L 15.5 4 L 14.5 3 L 9.5 3 L 8.5 4 L 5 4 L 5 6 L 19 6 L 19 4 Z");
        delIcon.setScaleX(0.55);
        delIcon.setScaleY(0.55);
        delIcon.getStyleClass().add("hover-menu-svg");

        Button btnDel = new Button();
        btnDel.setGraphic(delIcon);
        btnDel.getStyleClass().add("hover-menu-btn");
        btnDel.setOnAction(ev -> {
            removeNode(node);
            deselectNode();
        });

        hoverMenu.getChildren().addAll(btnExec, btnDel);
        hoverMenus.put(node.getId(), hoverMenu);
        flowCanvas.getChildren().add(hoverMenu);

        // Branching nodes (OCR, Template Search): add a second "No" output port below the Yes port
        if (node.getType() == FlowStepKind.OCR_READ || node.getType() == FlowStepKind.TEMPLATE_SEARCH) {
            Circle outPortFalse = new Circle(8);
            outPortFalse.getStyleClass().addAll("flow-port", "flow-port-false");
            if (node.getNextNodeFalseId() > 0) outPortFalse.getStyleClass().add("flow-port-connected");
            outPortFalse.setOnMouseClicked(ev -> {
                startConnectionDrag(node.getId(), true);
                ev.consume();
            });
            outputPortsFalse.put(node.getId(), outPortFalse);
            flowCanvas.getChildren().add(outPortFalse);
        }

        // Position ports immediately with default size, then reposition after layout
        updatePortPosWithDefaults(node, card, inPort, outPort, outputPortsFalse.get(node.getId()));
        
        // Schedule a second pass after layout is complete
        Platform.runLater(() -> {
            card.applyCss();
            card.layout();
            Circle fp = outputPortsFalse.get(node.getId());
            if (fp != null) {
                updatePortPosFull(node, card, inPort, outPort, fp);
            } else {
                updatePortPos(node, card, inPort, outPort);
            }
        });

        // ── Drag handlers ──
        card.setOnMousePressed(me -> {
            if (me.isPrimaryButtonDown() && !me.isControlDown()) {
                draggingCard = card;
                draggingNode = node;
                dragOffsetX = me.getX();
                dragOffsetY = me.getY();
                selectNodeOnCanvas(node);
                card.toFront();
                inPort.toFront();
                outPort.toFront();
                Circle fp = outputPortsFalse.get(node.getId());
                if (fp != null) fp.toFront();
                me.consume();
            }
        });
        card.setOnMouseDragged(me -> {
            if (draggingCard == card) {
                double nx = card.getLayoutX() + me.getX() - dragOffsetX;
                double ny = card.getLayoutY() + me.getY() - dragOffsetY;
                card.setLayoutX(nx); card.setLayoutY(ny);
                node.setCanvasX(nx); node.setCanvasY(ny);
                Circle fp = outputPortsFalse.get(node.getId());
                if (fp != null) updatePortPosFull(node, card, inPort, outPort, fp);
                else updatePortPos(node, card, inPort, outPort);
                rebuildAllWires(); me.consume();
            }
        });
        card.setOnMouseReleased(me -> { draggingCard = null; draggingNode = null; me.consume(); });

        return card;
    }

    // Port positioning — delegates to TaskBuilderNodeCardFactory
    private void updatePortPosWithDefaults(AutomationStep node, VBox card, Circle in, Circle out, Circle outFalse) {
        TaskBuilderNodeCardFactory.updatePortPosWithDefaults(node, card, in, out, outFalse);
    }

    private void updatePortPos(AutomationStep node, VBox card, Circle in, Circle out) {
        TaskBuilderNodeCardFactory.updatePortPos(node, card, in, out);
    }

    private void updatePortPosFull(AutomationStep node, VBox card, Circle in, Circle out, Circle outFalse) {
        TaskBuilderNodeCardFactory.updatePortPosFull(node, card, in, out, outFalse);
    }

    // ==================== CONNECTIONS ====================

    /**
     * Unified connection drag for both Yes (default) and No (false) branches.
     * @param srcId  source node ID (0 = Start node)
     * @param isFalseBranch  true for the "No" output port, false for default/"Yes"
     */
    private void startConnectionDrag(int srcId, boolean isFalseBranch) {
        if (srcId > 0) {
            AutomationStep src = findNode(srcId);
            if (src != null) {
                if (isFalseBranch) src.setNextNodeFalseId(-1);
                else               src.setNextNodeId(-1);
            }
        }
        dragWireIsFalseBranch = isFalseBranch;
        dragWireSourceId = srcId;
        dragWire = new CubicCurve();
        dragWire.setFill(Color.TRANSPARENT);
        dragWire.setStroke(Color.web(isFalseBranch ? "#ef4444" : "#fcd176"));  // Frostguard Theme colors
        dragWire.setStrokeWidth(3);

        // Determine start position from the correct port
        if (srcId == 0) {
            dragWire.setStartX(START_X + 140);
            dragWire.setStartY(START_Y + 18);
        } else {
            Circle port = isFalseBranch ? outputPortsFalse.get(srcId) : outputPorts.get(srcId);
            if (port != null) {
                dragWire.setStartX(port.getCenterX());
                dragWire.setStartY(port.getCenterY());
            }
        }
        dragWire.setEndX(dragWire.getStartX());
        dragWire.setEndY(dragWire.getStartY());
        dragWire.setControlX1(dragWire.getStartX());
        dragWire.setControlY1(dragWire.getStartY());
        dragWire.setControlX2(dragWire.getStartX());
        dragWire.setControlY2(dragWire.getStartY());
        dragWire.setMouseTransparent(true);
        flowCanvas.getChildren().add(dragWire);
        setStatus(isFalseBranch ? "🔴 Drag to connect No branch..." : "🟡 Drag to connect...");
    }

    private void updateDragWire(double mx, double my) {
        if (dragWire == null) return;
        dragWire.setEndX(mx);
        dragWire.setEndY(my);
        double deltaX = mx - dragWire.getStartX();
        double deltaY = my - dragWire.getStartY();
        
        if (deltaX < 30) {
            double yDir = (deltaY < -40) ? -1 : 1; 
            double hOffset = Math.max(90, Math.abs(deltaX) * 0.15);
            double vOffset = Math.max(140, Math.abs(deltaX) * 0.15 + Math.abs(deltaY) * 0.1);
            
            dragWire.setControlX1(dragWire.getStartX() + hOffset);
            dragWire.setControlY1(dragWire.getStartY() + (vOffset * yDir));
            dragWire.setControlX2(mx - hOffset);
            dragWire.setControlY2(my + (vOffset * yDir));
        } else {
            double offset = Math.max(60, deltaX * 0.45);
            dragWire.setControlX1(dragWire.getStartX() + offset);
            dragWire.setControlY1(dragWire.getStartY());
            dragWire.setControlX2(mx - offset);
            dragWire.setControlY2(my);
        }
    }

    private void finishConnectionDrag(MouseEvent e) {
        if (dragWire == null) return;
        flowCanvas.getChildren().remove(dragWire);
        for (var entry : inputPorts.entrySet()) {
            Circle p = entry.getValue();
            double d = Math.sqrt(Math.pow(e.getX()-p.getCenterX(),2)+Math.pow(e.getY()-p.getCenterY(),2));
            if (d < 25) { completeConnection(entry.getKey()); dragWire=null; dragWireSourceId=-1; return; }
        }
        dragWire = null; dragWireSourceId = -1;
        setStatus("Connection cancelled");
    }

    private void completeConnection(int targetId) {
        if (dragWireSourceId < 0 || targetId == dragWireSourceId) { cancelDrag(); return; }
        if (dragWireSourceId > 0) {
            AutomationStep s = findNode(dragWireSourceId);
            if (s != null) {
                if (dragWireIsFalseBranch) {
                    s.setNextNodeFalseId(targetId);
                } else {
                    s.setNextNodeId(targetId);
                }
            }
        }
        if (dragWire != null) { flowCanvas.getChildren().remove(dragWire); dragWire = null; }

        // Check if this new connection creates a back-edge (loop)
        boolean isLoop = false;
        if (dragWireSourceId > 0) {
            AutomationBlueprint def = builderService.getCurrentDefinition();
            if (def != null) {
                isLoop = dev.frostguard.engine.service.LoopDetector.isBackEdge(def, dragWireSourceId, targetId);
                if (isLoop) {
                    // Set default loop guard params on the source node if not already set
                    AutomationStep src = findNode(dragWireSourceId);
                    if (src != null) {
                        if (src.getParam("loopMaxIterations") == null) src.setParam("loopMaxIterations", "10");
                        if (src.getParam("loopDelayMs") == null) src.setParam("loopDelayMs", "500");
                        if (src.getParam("loopExhaustedAction") == null) src.setParam("loopExhaustedAction", "END");
                    }
                }
            }
        }

        dragWireSourceId = -1;
        dragWireIsFalseBranch = false;
        rebuildAllWires();
        if (isLoop) {
            setStatus("⚠ Loop detected! Click the loop label on the wire to configure max iterations.");
        } else {
            setStatus("Connected");
        }
    }

    private void cancelDrag() {
        if (dragWire!=null) { flowCanvas.getChildren().remove(dragWire); dragWire=null; }
        dragWireSourceId = -1;
    }

    private void rebuildAllWires() {
        // Remove all wire overlays (label backgrounds + texts)
        wireOverlays.forEach(n -> flowCanvas.getChildren().remove(n));
        wireOverlays.clear();
        connectionWires.values().forEach(w -> flowCanvas.getChildren().remove(w));
        connectionWires.clear();
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def == null) return;

        // Detect back-edges for loop visualization
        Set<String> backEdgeKeys = new HashSet<>();
        Map<String, dev.frostguard.engine.service.LoopDetector.BackEdge> backEdgeMap = new HashMap<>();
        for (dev.frostguard.engine.service.LoopDetector.BackEdge be : dev.frostguard.engine.service.LoopDetector.detectBackEdges(def)) {
            String edgeKey = be.isFalseBranch()
                    ? be.sourceId() + "-F->" + be.targetId()
                    : be.sourceId() + "->" + be.targetId();
            backEdgeKeys.add(edgeKey);
            backEdgeMap.put(edgeKey, be);
        }

        for (AutomationStep n : def.getNodes()) {
            // True / default branch
            if (n.getNextNodeId() > 0) {
                String wireKey = n.getId() + "->" + n.getNextNodeId();
                boolean isBranching = (n.getType() == FlowStepKind.OCR_READ || n.getType() == FlowStepKind.TEMPLATE_SEARCH);
                boolean isLoop = backEdgeKeys.contains(wireKey);
                String color = isLoop ? "#f59e0b" : (isBranching ? "#59ba59" : "#4a5568");
                CubicCurve w = makeWire(outputPorts.get(n.getId()), inputPorts.get(n.getNextNodeId()), color);
                if (w != null) {
                    if (isLoop) {
                        w.getStrokeDashArray().addAll(8.0, 5.0);
                        w.setStrokeWidth(3.0);
                    }
                    connectionWires.put(wireKey, w);
                    flowCanvas.getChildren().add(0, w);
                    if (isLoop) {
                        int maxIter = n.getParamAsInt("loopMaxIterations", 10);
                        addEditableLoopLabel(w, maxIter, n, wireKey);
                    } else if (isBranching) {
                        String lbl = n.getType() == FlowStepKind.TEMPLATE_SEARCH ? "Found" : "Yes";
                        addWireLabel(w, lbl, "#59ba59", wireKey);
                    }
                }
            }
            // False / "No" branch (OCR, Template Search)
            if (n.getNextNodeFalseId() > 0) {
                String wireKey = n.getId() + "-F->" + n.getNextNodeFalseId();
                boolean isLoop = backEdgeKeys.contains(wireKey);
                String color = isLoop ? "#f59e0b" : "#e74c3c";
                CubicCurve w = makeWire(outputPortsFalse.get(n.getId()), inputPorts.get(n.getNextNodeFalseId()), color);
                if (w != null) {
                    if (isLoop) {
                        w.getStrokeDashArray().addAll(8.0, 5.0);
                        w.setStrokeWidth(3.0);
                    }
                    connectionWires.put(wireKey, w);
                    flowCanvas.getChildren().add(0, w);
                    if (isLoop) {
                        int maxIter = n.getParamAsInt("loopMaxIterations", 10);
                        addEditableLoopLabel(w, maxIter, n, wireKey);
                    } else {
                        String lbl = n.getType() == FlowStepKind.TEMPLATE_SEARCH ? "Not Found" : "No";
                        addWireLabel(w, lbl, "#e74c3c", wireKey);
                    }
                }
            }
        }
        // Start node wire
        if (!def.getNodes().isEmpty()) {
            AutomationStep first = def.getNodes().get(0);
            Circle inp = inputPorts.get(first.getId());
            if (inp != null) {
                CubicCurve sw = new CubicCurve();
                sw.setFill(Color.TRANSPARENT); sw.setStroke(Color.web("#59ba59")); sw.setStrokeWidth(2.5);
                sw.setMouseTransparent(true);
                sw.setStartX(START_X+140); sw.setStartY(START_Y+18);
                sw.setEndX(inp.getCenterX()); sw.setEndY(inp.getCenterY());
                double deltaX = sw.getEndX() - sw.getStartX();
                double offset = Math.max(80, Math.abs(deltaX) / 2.0);
                sw.setControlX1(sw.getStartX() + offset);
                sw.setControlY1(sw.getStartY());
                sw.setControlX2(sw.getEndX() - offset);
                sw.setControlY2(sw.getEndY());
                connectionWires.put("s->"+first.getId(), sw);
                flowCanvas.getChildren().add(0, sw);
            }
        }
    }

    /**
     * Adds an editable loop label on a back-edge wire.
     * The label shows "🔁 Loop (max N)" and is clickable — clicking it
     * opens an inline text field to change the max iteration count.
     */
    private void addEditableLoopLabel(CubicCurve wire, int maxIter, AutomationStep sourceNode, String wireKey) {
        // Bezier midpoint at t=0.5
        double mx = 0.125 * wire.getStartX() + 0.375 * wire.getControlX1()
                  + 0.375 * wire.getControlX2() + 0.125 * wire.getEndX();
        double my = 0.125 * wire.getStartY() + 0.375 * wire.getControlY1()
                  + 0.375 * wire.getControlY2() + 0.125 * wire.getEndY();

        String labelText = "🔁 Loop (max " + maxIter + ")";
        double bgW = labelText.length() * 7.5 + 24;
        double bgH = 24;
        double bgX = mx - bgW / 2;
        double bgY = my - bgH / 2 - 2;

        javafx.scene.shape.Rectangle bg = new javafx.scene.shape.Rectangle(bgX, bgY, bgW, bgH);
        bg.setFill(Color.web("#1e1e1e"));
        bg.setStroke(Color.web("#f59e0b"));
        bg.setStrokeWidth(2);
        bg.setArcWidth(10); bg.setArcHeight(10);
        bg.setCursor(Cursor.HAND);

        javafx.scene.text.Text lbl = new javafx.scene.text.Text(labelText);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        lbl.setFill(Color.web("#f59e0b"));
        lbl.setMouseTransparent(true);
        lbl.setX(bgX + 12);
        lbl.setY(bgY + 16);

        // Click to edit max iterations
        bg.setOnMouseClicked(e -> {
            e.consume();
            javafx.scene.control.TextField editField = new javafx.scene.control.TextField(String.valueOf(maxIter));
            editField.setLayoutX(bgX);
            editField.setLayoutY(bgY);
            editField.setPrefWidth(bgW);
            editField.setPrefHeight(bgH);
            editField.setStyle("-fx-background-color: #2d3748; -fx-text-fill: #f59e0b; -fx-border-color: #f59e0b; -fx-border-radius: 5;");
            
            editField.setOnAction(ev -> {
                try {
                    int newMax = Integer.parseInt(editField.getText().trim());
                    sourceNode.setParam("loopMaxIterations", String.valueOf(newMax));
                    rebuildAllWires();
                } catch (NumberFormatException ex) {
                    rebuildAllWires(); // restore original
                }
            });
            
            editField.focusedProperty().addListener((obs, oldV, newV) -> {
                if (!newV) {
                    try {
                        int newMax = Integer.parseInt(editField.getText().trim());
                        sourceNode.setParam("loopMaxIterations", String.valueOf(newMax));
                    } catch (NumberFormatException ex) {}
                    rebuildAllWires();
                }
            });

            flowCanvas.getChildren().remove(bg);
            flowCanvas.getChildren().remove(lbl);
            flowCanvas.getChildren().add(editField);
            wireOverlays.add(editField);
            editField.requestFocus();
            editField.selectAll();
        });

        // Hover effects
        bg.setOnMouseEntered(e -> bg.setFill(Color.web("#2d3748")));
        bg.setOnMouseExited(e -> bg.setFill(Color.web("#1e1e1e")));

        wireOverlays.add(bg);
        wireOverlays.add(lbl);
        flowCanvas.getChildren().addAll(bg, lbl);
    }

    private void addWireLabel(CubicCurve wire, String text, String color, String key) {
        // Bezier midpoint at t=0.5 for a cubic curve with symmetric controls
        double mx = 0.125 * wire.getStartX() + 0.375 * wire.getControlX1()
                  + 0.375 * wire.getControlX2() + 0.125 * wire.getEndX();
        double my = 0.125 * wire.getStartY() + 0.375 * wire.getControlY1()
                  + 0.375 * wire.getControlY2() + 0.125 * wire.getEndY();

        double bgW = text.length() * 9 + 18;
        double bgH = 22;
        double bgX = mx - bgW / 2;
        double bgY = my - bgH / 2 - 2;

        javafx.scene.shape.Rectangle bg = new javafx.scene.shape.Rectangle(bgX, bgY, bgW, bgH);
        bg.setFill(Color.web("#11141a"));
        bg.setStroke(Color.web(color));
        bg.setStrokeWidth(2);
        bg.setArcWidth(10); bg.setArcHeight(10);
        bg.setMouseTransparent(true);

        javafx.scene.text.Text lbl = new javafx.scene.text.Text(text);
        lbl.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        lbl.setFill(Color.web(color));
        lbl.setMouseTransparent(true);
        lbl.setX(bgX + 9);
        lbl.setY(bgY + 15);

        flowCanvas.getChildren().addAll(bg, lbl);
        wireOverlays.add(bg);
        wireOverlays.add(lbl);
    }

    private CubicCurve makeWire(Circle from, Circle to, String color) {
        if (from==null||to==null) return null;
        CubicCurve w = new CubicCurve();
        w.setFill(Color.TRANSPARENT); w.setStroke(Color.web(color)); w.setStrokeWidth(2.5);
        w.setMouseTransparent(true);
        w.setStartX(from.getCenterX()); w.setStartY(from.getCenterY());
        w.setEndX(to.getCenterX()); w.setEndY(to.getCenterY());
        
        double deltaX = w.getEndX() - w.getStartX();
        double deltaY = w.getEndY() - w.getStartY();
        
        if (deltaX < 30) {
            double yDir = (deltaY < -40) ? -1 : 1; 
            double hOffset = Math.max(90, Math.abs(deltaX) * 0.15);
            double vOffset = Math.max(140, Math.abs(deltaX) * 0.15 + Math.abs(deltaY) * 0.1);
            
            w.setControlX1(w.getStartX() + hOffset);
            w.setControlY1(w.getStartY() + (vOffset * yDir));
            w.setControlX2(w.getEndX() - hOffset);
            w.setControlY2(w.getEndY() + (vOffset * yDir));
        } else {
            double offset = Math.max(60, deltaX * 0.45);
            w.setControlX1(w.getStartX() + offset);
            w.setControlY1(w.getStartY());
            w.setControlX2(w.getEndX() - offset);
            w.setControlY2(w.getEndY());
        }
        return w;
    }

    // ==================== SELECTION & PROPERTIES ====================

    private void selectNodeOnCanvas(AutomationStep node) {
        isBinding = true;
        deselectNode();
        selectedNode = node;
        VBox card = nodeCards.get(node.getId());
        if (card != null) {
            card.getStyleClass().add("flow-node-selected");
            card.toFront();
            // Always keep ports above the card
            Circle ip = inputPorts.get(node.getId());
            Circle op = outputPorts.get(node.getId());
            Circle fp = outputPortsFalse.get(node.getId());
            if (ip != null) ip.toFront();
            if (op != null) op.toFront();
            if (fp != null) fp.toFront();
        }

        // Show the properties drawer
        propsDrawer.setVisible(true); propsDrawer.setManaged(true);
        propNodeIcon.setText(getIcon(node.getType()));
        propNodeTitle.setText(node.getType().getDisplayName() + " #" + node.getId());
        propStatusLabel.setText(node.isExecuted() ? "✅ Executed" : "⏳ Pending");
        if (nodeNameField != null) {
            nodeNameField.setText(node.getNodeName());
        }

        tapPropsBox.setVisible(false); tapPropsBox.setManaged(false);
        if (swipePropsBox != null) { swipePropsBox.setVisible(false); swipePropsBox.setManaged(false); }
        waitPropsBox.setVisible(false); waitPropsBox.setManaged(false);
        backPropsBox.setVisible(false); backPropsBox.setManaged(false);
        ocrPropsBox.setVisible(false); ocrPropsBox.setManaged(false);
        if (templatePropsBox != null) { templatePropsBox.setVisible(false); templatePropsBox.setManaged(false); }
        
        if (previewCrosshairPane != null) previewCrosshairPane.setVisible(false);
        if (selectionBox != null) selectionBox.setVisible(false);

        switch (node.getType()) {
            case TAP_POINT -> {
                tapPropsBox.setVisible(true); tapPropsBox.setManaged(true);
                tapTlXField.setText(node.getParam("tlX") != null ? node.getParam("tlX") : "0");
                tapTlYField.setText(node.getParam("tlY") != null ? node.getParam("tlY") : "0");
                tapBrXField.setText(node.getParam("brX") != null ? node.getParam("brX") : "0");
                tapBrYField.setText(node.getParam("brY") != null ? node.getParam("brY") : "0");
                if (hasPreviewImage) setStatus("Tap or format region on preview...");
            }
            case SWIPE -> {
                if (swipePropsBox != null) {
                    swipePropsBox.setVisible(true); swipePropsBox.setManaged(true);
                    swipeStartXField.setText(node.getParam("startX") != null ? node.getParam("startX") : "0");
                    swipeStartYField.setText(node.getParam("startY") != null ? node.getParam("startY") : "0");
                    swipeEndXField.setText(node.getParam("endX") != null ? node.getParam("endX") : "0");
                    swipeEndYField.setText(node.getParam("endY") != null ? node.getParam("endY") : "0");
                }
                if (hasPreviewImage) setStatus("Drag on the preview to draw swipe trajectory...");
            }
            case WAIT -> {
                waitPropsBox.setVisible(true); waitPropsBox.setManaged(true);
                waitMsField.setText(node.getParam("durationMs") != null ? node.getParam("durationMs") : "1000");
            }
            case BACK_BUTTON -> { backPropsBox.setVisible(true); backPropsBox.setManaged(true); }
            case OCR_READ -> {
                ocrPropsBox.setVisible(true); ocrPropsBox.setManaged(true);
                ocrTlXField.setText(node.getParam("tlX") != null ? node.getParam("tlX") : "0");
                ocrTlYField.setText(node.getParam("tlY") != null ? node.getParam("tlY") : "0");
                ocrBrXField.setText(node.getParam("brX") != null ? node.getParam("brX") : "100");
                ocrBrYField.setText(node.getParam("brY") != null ? node.getParam("brY") : "100");
                if (ocrConditionCombo != null) {
                    String cond = node.getParam("condition");
                    ocrConditionCombo.setValue(cond != null ? cond : "CONTAINS");
                }
                if (ocrExpectedField != null) {
                    ocrExpectedField.setText(node.getParam("expectedValue") != null ? node.getParam("expectedValue") : "");
                }
                if (hasPreviewImage) {
                    selectionBox.setVisible(true);
                    setStatus("Drag on the preview to draw bounds. Has 2 outputs: Yes / No.");
                }
            }
            case TEMPLATE_SEARCH -> {
                if (templatePropsBox != null) {
                    templatePropsBox.setVisible(true); templatePropsBox.setManaged(true);
                    String curTpl = node.getParam("templatePath");
                    if (TemplatePathResolver.isFileReference(curTpl)) {
                        // File-backed template (operator-chosen or bundled with a
                        // shared task) — add to the combo dynamically if absent.
                        if (!templateComboBox.getItems().contains(curTpl)) {
                            templateComboBox.getItems().add(curTpl);
                        }
                        templateComboBox.setValue(curTpl);
                        boolean picked = curTpl.startsWith(CUSTOM_TEMPLATE_PREFIX);
                        String shown = picked
                                ? curTpl.substring(CUSTOM_TEMPLATE_PREFIX.length())
                                : curTpl;
                        if (templateCustomPathLabel != null) {
                            templateCustomPathLabel.setText("📁 " + (picked ? "Custom: " : "Package: ")
                                    + Paths.get(shown).getFileName());
                            templateCustomPathLabel.setVisible(true);
                            templateCustomPathLabel.setManaged(true);
                        }
                    } else {
                        // Standard TemplatesEnum value
                        if (templateCustomPathLabel != null) {
                            templateCustomPathLabel.setVisible(false);
                            templateCustomPathLabel.setManaged(false);
                        }
                        if (curTpl != null && !curTpl.isEmpty() && templateComboBox.getItems().contains(curTpl)) {
                            templateComboBox.setValue(curTpl);
                        } else if (!templateComboBox.getItems().isEmpty()) {
                            templateComboBox.getSelectionModel().selectFirst();
                        }
                    }
                    templateThresholdField.setText(node.getParam("threshold") != null ? node.getParam("threshold") : "90");
                    if (templateGrayscaleCheck != null) {
                        templateGrayscaleCheck.setSelected("true".equals(node.getParam("grayscale")));
                    }
                    if (tplTlXField != null) tplTlXField.setText(node.getParam("tlX") != null ? node.getParam("tlX") : "");
                    if (tplTlYField != null) tplTlYField.setText(node.getParam("tlY") != null ? node.getParam("tlY") : "");
                    if (tplBrXField != null) tplBrXField.setText(node.getParam("brX") != null ? node.getParam("brX") : "");
                    if (tplBrYField != null) tplBrYField.setText(node.getParam("brY") != null ? node.getParam("brY") : "");
                    if (templateAttemptsField != null) templateAttemptsField.setText(node.getParam("maxAttempts") != null ? node.getParam("maxAttempts") : "1");
                    if (templateDelayField != null) templateDelayField.setText(node.getParam("delayMs") != null ? node.getParam("delayMs") : "300");
                    if (templateTapIfFoundCheck != null) templateTapIfFoundCheck.setSelected("true".equals(node.getParam("tapIfFound")));
                    if (templateOffsetXField != null) templateOffsetXField.setText(node.getParam("offsetX") != null ? node.getParam("offsetX") : "0");
                    if (templateOffsetYField != null) templateOffsetYField.setText(node.getParam("offsetY") != null ? node.getParam("offsetY") : "0");
                }
                if (hasPreviewImage) {
                    selectionBox.setVisible(true);
                    setStatus("Optionally drag on the preview to set search area bounds. Outputs: ✅ Found / ❌ Not Found.");
                }
            }
            default -> {}
        }
        isBinding = false;
    }

    private void deselectNode() {
        if (selectedNode != null) {
            VBox c = nodeCards.get(selectedNode.getId());
            if (c != null) c.getStyleClass().remove("flow-node-selected");
        }
        selectedNode = null;
        // Hide the properties drawer
        propsDrawer.setVisible(false); propsDrawer.setManaged(false);
    }

    @FXML private void handleDeselectNode(ActionEvent e) { deselectNode(); }

    // ==================== PROPERTY APPLY ====================

    @FXML private void handleApplyTapProps(ActionEvent e) {
        if (selectedNode == null) return;
        selectedNode.setParam("tlX", tapTlXField.getText());
        selectedNode.setParam("tlY", tapTlYField.getText());
        selectedNode.setParam("brX", tapBrXField.getText());
        selectedNode.setParam("brY", tapBrYField.getText());
        refreshCard(selectedNode);
    }

    @FXML private void handleApplySwipeProps(ActionEvent e) {
        if (selectedNode == null) return;
        selectedNode.setParam("startX", swipeStartXField.getText());
        selectedNode.setParam("startY", swipeStartYField.getText());
        selectedNode.setParam("endX", swipeEndXField.getText());
        selectedNode.setParam("endY", swipeEndYField.getText());
        refreshCard(selectedNode);
    }

    @FXML private void handleApplyWaitProps(ActionEvent e) {
        if (selectedNode == null) return;
        selectedNode.setParam("durationMs", waitMsField.getText());
        refreshCard(selectedNode);
    }

    @FXML private void handleApplyOcrProps(ActionEvent e) {
        if (selectedNode == null) return;
        selectedNode.setParam("tlX", ocrTlXField.getText());
        selectedNode.setParam("tlY", ocrTlYField.getText());
        selectedNode.setParam("brX", ocrBrXField.getText());
        selectedNode.setParam("brY", ocrBrYField.getText());
        if (ocrConditionCombo != null && ocrConditionCombo.getValue() != null)
            selectedNode.setParam("condition", ocrConditionCombo.getValue());
        if (ocrExpectedField != null)
            selectedNode.setParam("expectedValue", ocrExpectedField.getText());
        refreshCard(selectedNode);
    }

    @FXML
    private void handleBrowseCustomTemplate(ActionEvent e) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Custom Template Image");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        File chosen = fc.showOpenDialog(rootPane.getScene().getWindow());
        if (chosen != null) {
            String sentinel = CUSTOM_TEMPLATE_PREFIX + chosen.getAbsolutePath();
            if (!templateComboBox.getItems().contains(sentinel)) {
                templateComboBox.getItems().add(sentinel);
            }
            templateComboBox.setValue(sentinel);
            String fname = chosen.getName();
            if (templateCustomPathLabel != null) {
                templateCustomPathLabel.setText("📁 Custom: " + fname);
                templateCustomPathLabel.setVisible(true);
                templateCustomPathLabel.setManaged(true);
            }
        }
    }

    @FXML private void handleApplyTemplateProps(ActionEvent e) {
        if (selectedNode == null || templateComboBox == null) return;
        String val = templateComboBox.getValue();
        if (val != null) {
            selectedNode.setParam("templatePath", val);
        }
        selectedNode.setParam("threshold", templateThresholdField.getText());
        if (templateGrayscaleCheck != null) {
            selectedNode.setParam("grayscale", String.valueOf(templateGrayscaleCheck.isSelected()));
        }
        // Optional search area
        if (tplTlXField != null && !tplTlXField.getText().trim().isEmpty()) selectedNode.setParam("tlX", tplTlXField.getText().trim());
        else selectedNode.getParams().remove("tlX");
        if (tplTlYField != null && !tplTlYField.getText().trim().isEmpty()) selectedNode.setParam("tlY", tplTlYField.getText().trim());
        else selectedNode.getParams().remove("tlY");
        if (tplBrXField != null && !tplBrXField.getText().trim().isEmpty()) selectedNode.setParam("brX", tplBrXField.getText().trim());
        else selectedNode.getParams().remove("brX");
        if (tplBrYField != null && !tplBrYField.getText().trim().isEmpty()) selectedNode.setParam("brY", tplBrYField.getText().trim());
        else selectedNode.getParams().remove("brY");
        // Retry configs
        if (templateAttemptsField != null) selectedNode.setParam("maxAttempts", templateAttemptsField.getText().trim().isEmpty() ? "1" : templateAttemptsField.getText().trim());
        if (templateDelayField != null) selectedNode.setParam("delayMs", templateDelayField.getText().trim().isEmpty() ? "300" : templateDelayField.getText().trim());
        // Tap if Found
        if (templateTapIfFoundCheck != null) selectedNode.setParam("tapIfFound", String.valueOf(templateTapIfFoundCheck.isSelected()));
        if (templateOffsetXField != null) selectedNode.setParam("offsetX", templateOffsetXField.getText().trim().isEmpty() ? "0" : templateOffsetXField.getText().trim());
        if (templateOffsetYField != null) selectedNode.setParam("offsetY", templateOffsetYField.getText().trim().isEmpty() ? "0" : templateOffsetYField.getText().trim());
        refreshCard(selectedNode);
    }

    @FXML private void handlePreviewClicked(MouseEvent e) { } // Migrated logic to Released

    @FXML private void handlePreviewMousePressed(MouseEvent e) {
        if (selectedNode == null) return;
        if (selectedNode.getType() != FlowStepKind.OCR_READ && 
            selectedNode.getType() != FlowStepKind.TAP_POINT && 
            selectedNode.getType() != FlowStepKind.SWIPE &&
            selectedNode.getType() != FlowStepKind.TEMPLATE_SEARCH) {
            return;
        }

        double relX = e.getX() - 2.0;
        double relY = e.getY() - 2.0;
        if (relX < 0 || relX > previewImageView.getBoundsInLocal().getWidth() || relY < 0 || relY > previewImageView.getBoundsInLocal().getHeight()) return;

        ocrDragStartX = e.getX();
        ocrDragStartY = e.getY();
        
        selectionBox.setX(ocrDragStartX);
        selectionBox.setY(ocrDragStartY);
        selectionBox.setWidth(0);
        selectionBox.setHeight(0);
        
        if (selectedNode.getType() != FlowStepKind.SWIPE) {
            selectionBox.setVisible(true);
        } else {
            selectionBox.setVisible(false); // don't draw rect for swipe
        }
        
        previewCrosshairPane.setVisible(true);
        previewCrossX.setVisible(false);
        previewCrossY.setVisible(false);
        previewCoordsLabel.setVisible(false);
    }

    @FXML private void handlePreviewMouseDragged(MouseEvent e) {
        if (selectedNode == null) return;
        
        double currentX = Math.max(2, Math.min(e.getX(), previewImageView.getBoundsInLocal().getWidth() + 2));
        double currentY = Math.max(2, Math.min(e.getY(), previewImageView.getBoundsInLocal().getHeight() + 2));

        double minX = Math.min(ocrDragStartX, currentX);
        double minY = Math.min(ocrDragStartY, currentY);
        double width = Math.abs(currentX - ocrDragStartX);
        double height = Math.abs(currentY - ocrDragStartY);

        selectionBox.setX(minX);
        selectionBox.setY(minY);
        selectionBox.setWidth(width);
        selectionBox.setHeight(height);
    }

    @FXML private void handlePreviewMouseReleased(MouseEvent e) {
        if (selectedNode == null) return;
        if (previewImageView.getImage() == null) return;

        double imgW = previewImageView.getImage().getWidth();
        double imgH = previewImageView.getImage().getHeight();
        double scaleX = imgW / previewImageView.getBoundsInLocal().getWidth();
        double scaleY = imgH / previewImageView.getBoundsInLocal().getHeight();

        double currentX = Math.max(2, Math.min(e.getX(), previewImageView.getBoundsInLocal().getWidth() + 2));
        double currentY = Math.max(2, Math.min(e.getY(), previewImageView.getBoundsInLocal().getHeight() + 2));

        if (selectedNode.getType() == FlowStepKind.TAP_POINT || selectedNode.getType() == FlowStepKind.OCR_READ) {
            int tlX, tlY, brX, brY;
            if (selectionBox.getWidth() <= 1 && selectionBox.getHeight() <= 1) {
                // it was a simple click
                int px = (int) ((currentX - 2.0) * scaleX);
                int py = (int) ((currentY - 2.0) * scaleY);
                tlX = px; tlY = py; brX = px; brY = py;
            } else {
                tlX = (int) ((selectionBox.getX() - 2.0) * scaleX);
                tlY = (int) ((selectionBox.getY() - 2.0) * scaleY);
                brX = (int) (((selectionBox.getX() - 2.0) + selectionBox.getWidth()) * scaleX);
                brY = (int) (((selectionBox.getY() - 2.0) + selectionBox.getHeight()) * scaleY);
            }
            
            if (selectedNode.getType() == FlowStepKind.TAP_POINT) {
                tapTlXField.setText(String.valueOf(tlX));
                tapTlYField.setText(String.valueOf(tlY));
                tapBrXField.setText(String.valueOf(brX));
                tapBrYField.setText(String.valueOf(brY));
                handleApplyTapProps(null);
            } else {
                ocrTlXField.setText(String.valueOf(tlX));
                ocrTlYField.setText(String.valueOf(tlY));
                ocrBrXField.setText(String.valueOf(brX));
                ocrBrYField.setText(String.valueOf(brY));
                handleApplyOcrProps(null);
            }
        } else if (selectedNode.getType() == FlowStepKind.SWIPE) {
            int startX = (int) ((ocrDragStartX - 2.0) * scaleX);
            int startY = (int) ((ocrDragStartY - 2.0) * scaleY);
            int endX = (int) ((currentX - 2.0) * scaleX);
            int endY = (int) ((currentY - 2.0) * scaleY);
            swipeStartXField.setText(String.valueOf(startX));
            swipeStartYField.setText(String.valueOf(startY));
            swipeEndXField.setText(String.valueOf(endX));
            swipeEndYField.setText(String.valueOf(endY));
            handleApplySwipeProps(null);
        } else if (selectedNode.getType() == FlowStepKind.TEMPLATE_SEARCH) {
            // Template search: set optional search area
            if (selectionBox.getWidth() > 3 && selectionBox.getHeight() > 3) {
                int tlX = (int) ((selectionBox.getX() - 2.0) * scaleX);
                int tlY = (int) ((selectionBox.getY() - 2.0) * scaleY);
                int brX = (int) (((selectionBox.getX() - 2.0) + selectionBox.getWidth()) * scaleX);
                int brY = (int) (((selectionBox.getY() - 2.0) + selectionBox.getHeight()) * scaleY);
                if (tplTlXField != null) tplTlXField.setText(String.valueOf(tlX));
                if (tplTlYField != null) tplTlYField.setText(String.valueOf(tlY));
                if (tplBrXField != null) tplBrXField.setText(String.valueOf(brX));
                if (tplBrYField != null) tplBrYField.setText(String.valueOf(brY));
                handleApplyTemplateProps(null);
            }
        }
    }

    @FXML private void handlePreviewMouseEntered(MouseEvent e) {
        if (hasPreviewImage) previewCrosshairPane.setVisible(true);
    }

    @FXML private void handlePreviewMouseExited(MouseEvent e) {
        if (previewCrosshairPane != null) previewCrosshairPane.setVisible(false);
    }

    @FXML private void handlePreviewMouseMoved(MouseEvent e) {
        if (!hasPreviewImage || previewImageView.getImage() == null) return;

        double relX = e.getX() - 2.0;
        double relY = e.getY() - 2.0;
        
        if (relX < 0 || relX > previewImageView.getBoundsInLocal().getWidth() || relY < 0 || relY > previewImageView.getBoundsInLocal().getHeight()) {
            previewCrosshairPane.setVisible(false);
            return;
        }

        double imgW = previewImageView.getImage().getWidth();
        double imgH = previewImageView.getImage().getHeight();
        double scaleX = imgW / previewImageView.getBoundsInLocal().getWidth();
        double scaleY = imgH / previewImageView.getBoundsInLocal().getHeight();
        int realX = (int) (relX * scaleX);
        int realY = (int) (relY * scaleY);

        if (!previewCrosshairPane.isVisible()) {
            previewCrosshairPane.setVisible(true);
            previewCrossX.setVisible(true);
            previewCrossY.setVisible(true);
            previewCoordsLabel.setVisible(true);
        }

        previewCrossX.setStartY(2.0); previewCrossX.setEndY(previewImageView.getBoundsInLocal().getHeight() + 2.0);
        previewCrossX.setStartX(e.getX()); previewCrossX.setEndX(e.getX());
        previewCrossY.setStartX(2.0); previewCrossY.setEndX(previewImageView.getBoundsInLocal().getWidth() + 2.0);
        previewCrossY.setStartY(e.getY()); previewCrossY.setEndY(e.getY());
        
        previewCoordsLabel.setText(realX + ", " + realY);
        
        // Prevent coords label from clipping out of bounds
        double labelX = e.getX() + 10;
        double labelY = e.getY() + 10;
        if (labelX + 80 > previewImageView.getBoundsInLocal().getWidth() + 2.0) {
             labelX = e.getX() - 90;
        }
        if (labelY + 25 > previewImageView.getBoundsInLocal().getHeight() + 2.0) {
             labelY = e.getY() - 35;
        }
        previewCoordsLabel.setLayoutX(labelX);
        previewCoordsLabel.setLayoutY(labelY);
    }

    private void refreshCard(AutomationStep node) {
        TaskBuilderNodeCardFactory.refreshCard(
            node, nodeCards, inputPorts, outputPorts, outputPortsFalse,
            this::rebuildAllWires
        );
    }


    // ==================== EXECUTION ====================

    @FXML private void handleExecuteSelected(ActionEvent e) {
        if (selectedNode == null) return;
        execNode(selectedNode);
    }

    @FXML private void handleExecuteAll(ActionEvent e) {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def == null || def.getNodes().isEmpty()) { setStatus("⚠ No nodes"); return; }

        // Build id→node map for O(1) lookup
        Map<Integer, AutomationStep> nodeMap = new LinkedHashMap<>();
        for (AutomationStep n : def.getNodes()) nodeMap.put(n.getId(), n);

        // Determine the first node (connected from Start, or first in list)
        int firstId = def.getNodes().get(0).getId();

        setStatus("▶ Executing DAG...");
        AccountDescriptor profile = profileComboBox.getValue();
        Thread t = new Thread(() -> {
            try {
                String startLocStr = def.getStartLocation();
                if (profile != null && startLocStr != null && !startLocStr.equalsIgnoreCase("ANY")) {
                    Platform.runLater(() -> setStatus("▶ Navigating to " + startLocStr + "..."));
                    dev.frostguard.engine.schedule.LaunchPoint loc = 
                        dev.frostguard.engine.schedule.LaunchPoint.valueOf(startLocStr.toUpperCase());
                    dev.frostguard.engine.helper.NavigationHelper navHelper = 
                        new dev.frostguard.engine.helper.NavigationHelper(
                                dev.frostguard.engine.emulator.EmulatorController.getInstance(), 
                                profile.getEmulatorNumber(), 
                                profile
                        );
                    navHelper.ensureCorrectScreenLocation(loc);
                }
            } catch (Exception ex) {
                Platform.runLater(() -> setStatus("❌ Start Navigation failed: " + ex.getMessage()));
                return;
            }

            int currentId = firstId;
            while (currentId > 0) {
                AutomationStep current = nodeMap.get(currentId);
                if (current == null) break;

                boolean ok = builderService.executeNode(current);
                final AutomationStep nodeRef = current;
                Platform.runLater(() -> {
                    refreshCard(nodeRef);
                    if (selectedNode != null && selectedNode.getId() == nodeRef.getId())
                        propStatusLabel.setText(nodeRef.isExecuted() ? "✅" : "❌");
                });

                if (!ok) {
                    Platform.runLater(() -> setStatus("❌ Failed at node #" + nodeRef.getId()));
                    break;
                }

                // Determine next node based on branching (unified via BranchEvaluator)
                currentId = BranchEvaluator.resolveNextNode(current);

                try { Thread.sleep(400); } catch (InterruptedException ignored) { return; }
            }
            capturePreview();
            Platform.runLater(() -> setStatus("✅ DAG execution complete"));
        });
        t.setDaemon(true); t.start();
    }


    private void execNode(AutomationStep node) {
        setStatus("▶ " + node.getSummary() + "...");
        Thread t = new Thread(() -> {
            boolean ok = builderService.executeNode(node);
            Platform.runLater(() -> {
                refreshCard(node);
                propStatusLabel.setText(node.isExecuted() ? "✅ Executed" : "❌ Failed");
                setStatus(ok ? "✅ Done" : "❌ Failed");
            });
            if (ok) { try { Thread.sleep(800); } catch (InterruptedException ignored) {} capturePreview(); }
        });
        t.setDaemon(true); t.start();
    }

    // ==================== DELETE / CLEAR ====================

    @FXML private void handleDeleteSelected(ActionEvent e) {
        if (selectedNode == null) return;
        removeNode(selectedNode);
        deselectNode();
    }

    @FXML private void handleClearAll(ActionEvent e) {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def == null) return;
        // Remove all node cards and their ports (in, out, outFalse)
        for (int id : new ArrayList<>(nodeCards.keySet())) {
            flowCanvas.getChildren().removeAll(nodeCards.get(id), inputPorts.get(id), outputPorts.get(id));
            Circle fp = outputPortsFalse.get(id);
            if (fp != null) flowCanvas.getChildren().remove(fp);
        }
        nodeCards.clear(); inputPorts.clear(); outputPorts.clear(); outputPortsFalse.clear();
        hoverMenus.values().forEach(hm -> flowCanvas.getChildren().remove(hm));
        hoverMenus.clear();
        // Remove wires and wire labels (Yes/No)
        connectionWires.values().forEach(w -> flowCanvas.getChildren().remove(w));
        connectionWires.clear();
        wireOverlays.forEach(n -> flowCanvas.getChildren().remove(n));
        wireOverlays.clear();
        def.getNodes().clear();
        deselectNode(); updateNodeCount();
        setStatus("🗑 Cleared");
    }

    private void removeNode(AutomationStep node) {
        Circle falsePort = outputPortsFalse.remove(node.getId());
        if (falsePort != null) flowCanvas.getChildren().remove(falsePort);
        HBox hm = hoverMenus.remove(node.getId());
        if (hm != null) flowCanvas.getChildren().remove(hm);
        flowCanvas.getChildren().removeAll(nodeCards.remove(node.getId()), inputPorts.remove(node.getId()), outputPorts.remove(node.getId()));
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def != null) {
            def.getNodes().forEach(n -> {
                if (n.getNextNodeId() == node.getId()) n.setNextNodeId(-1);
                if (n.getNextNodeFalseId() == node.getId()) n.setNextNodeFalseId(-1);
            });
            def.getNodes().removeIf(n -> n.getId() == node.getId());
        }
        rebuildAllWires(); updateNodeCount();
        setStatus("🗑 Deleted #" + node.getId());
    }

    // ==================== SESSION / PROFILE / CAPTURE ====================

    private void setupProfiles() {
        if (profileComboBox == null) return;
        profileComboBox.setOnShowing(e -> {
            try {
                List<AccountDescriptor> profiles = ProfileService.obtain().fetchAllAccounts();
                if (profiles == null) return;
                AccountDescriptor cur = profileComboBox.getValue();
                profileComboBox.setItems(FXCollections.observableArrayList(profiles));
                profileComboBox.setConverter(new javafx.util.StringConverter<>() {
                    @Override public String toString(AccountDescriptor p) { return p==null?"":p.getName()+" (Emu "+p.getEmulatorNumber()+")"; }
                    @Override public AccountDescriptor fromString(String s) { return null; }
                });
                if (cur != null) for (AccountDescriptor p : profiles) { if (p.getEmulatorNumber()==cur.getEmulatorNumber()) { profileComboBox.getSelectionModel().select(p); break; } }
                else if (!profiles.isEmpty()) profileComboBox.getSelectionModel().selectFirst();
            } catch (Exception ex) { setStatus("Profile error: " + ex.getMessage()); }
        });
    }

    // ==================== HELPERS ====================

    private void ensureSession() {
        if (!builderService.hasActiveSession()) {
            AccountDescriptor sel = profileComboBox.getValue();
            String emu = sel != null ? sel.getEmulatorNumber() : "0";
            String name = taskNameField.getText();
            if (name == null || name.trim().isEmpty()) name = "Untitled Task";
            builderService.startSession(name.trim(), emu);
        }
    }

    private AutomationStep findNode(int id) {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        if (def==null) return null;
        return def.getNodes().stream().filter(n->n.getId()==id).findFirst().orElse(null);
    }

    private void updateNodeCount() {
        AutomationBlueprint def = builderService.getCurrentDefinition();
        int c = def != null ? def.getNodes().size() : 0;
        nodeCountLabel.setText(c + " node" + (c!=1?"s":""));
    }

    private void setStatus(String m) { if (statusLabel!=null) statusLabel.setText(m); }

    // Icon, style, and accent color — delegates to TaskBuilderNodeCardFactory
    private String getIcon(FlowStepKind t) {
        return TaskBuilderNodeCardFactory.getIcon(t);
    }

    private String getHeaderStyle(FlowStepKind t) {
        return TaskBuilderNodeCardFactory.getHeaderStyleClass(t);
    }

    private String getAccentColor(FlowStepKind t) {
        return TaskBuilderNodeCardFactory.getAccentColor(t);
    }

    private Image toFxImage(BufferedImage bi) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(bi,"png",b); return new Image(new ByteArrayInputStream(b.toByteArray())); }
        catch (Exception e) { return null; }
    }
}
