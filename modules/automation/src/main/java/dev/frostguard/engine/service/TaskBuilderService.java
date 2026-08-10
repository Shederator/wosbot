package dev.frostguard.engine.service;

import dev.frostguard.vision.ocr.TesseractOcrProvider;
import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service that manages a Task Builder recording session.
 *
 * <p>Bridges the UI (TaskBuilderLayoutController) with the EmulatorController
 * to enable the "record → execute → capture" flow:</p>
 * <ol>
 *   <li>User picks coordinates/params on a screenshot preview</li>
 *   <li>User finalizes the node → this service executes it on the emulator</li>
 *   <li>Service captures a fresh screenshot for the next step</li>
 * </ol>
 */
public class TaskBuilderService {

    private static final Logger logger = LoggerFactory.getLogger(TaskBuilderService.class);

    /** Sentinel prefix used for user-selected custom template files. */
    private static final String FILE_PREFIX = "file://";

    private final EmulatorController emuManager;
    private final Path customTasksDir;
    private final ObjectMapper mapper;
    private AutomationBlueprint currentDefinition;
    private String activeEmulatorNumber;
    private TapInteractionService tapService;

    public TaskBuilderService() {
        this.emuManager = EmulatorController.getInstance();
        this.customTasksDir = Paths.get(System.getProperty("user.dir"), "custom_tasks");
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(customTasksDir);
        } catch (IOException e) {
            logger.error("Failed to create custom_tasks directory", e);
        }
    }

    public record CustomTaskSaveResult(Path javaFile, Path builderFile, String className) {}

    // ========================================================================
    // Session management
    // ========================================================================

    /**
     * Starts a new recording session.
     *
     * @param taskName        Name for the new task
     * @param emulatorNumber  Emulator to use for live preview/execution
     */
    public void startSession(String taskName, String emulatorNumber) {
        this.currentDefinition = new AutomationBlueprint(taskName);
        this.activeEmulatorNumber = emulatorNumber;
        this.tapService = TapInteractionService.forController(emuManager, emulatorNumber);
        logger.info("Task Builder session started: '{}' on emulator {}", taskName, emulatorNumber);
    }

    /**
     * Loads a saved builder definition into the current Task Builder session.
     */
    public AutomationBlueprint loadDefinition(File file, String emulatorNumber) throws IOException {
        AutomationBlueprint loaded = mapper.readValue(file, AutomationBlueprint.class);
        if (loaded.getNodes() == null) {
            loaded.setNodes(new java.util.ArrayList<>());
        }
        if (loaded.getName() == null || loaded.getName().trim().isEmpty()) {
            loaded.setName(stripBuilderExtension(file.getName()));
        }
        if (loaded.getStartLocation() == null || loaded.getStartLocation().trim().isEmpty()) {
            loaded.setStartLocation("ANY");
        }
        this.currentDefinition = loaded;
        this.activeEmulatorNumber = emulatorNumber;
        logger.info("Task Builder definition imported: '{}' from {}", loaded.getName(), file.getAbsolutePath());
        return loaded;
    }

    /**
     * Saves the editable flow as generated Java and a builder JSON snapshot.
     */
    public CustomTaskSaveResult saveCurrentTaskToCustomTasks(String preferredName) throws IOException {
        String displayName = preferredName == null || preferredName.trim().isEmpty()
                ? currentDefinition != null ? currentDefinition.getName() : null
                : preferredName.trim();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "Generated Task";
        }
        return saveCurrentTaskToCustomTasks(preferredName,
                customTasksDir.resolve(toJavaClassName(displayName) + ".json"));
    }

    /**
     * Saves the editable flow to a chosen builder JSON file and writes generated Java beside it.
     */
    public CustomTaskSaveResult saveCurrentTaskToCustomTasks(String preferredName, Path builderPath) throws IOException {
        if (currentDefinition == null) {
            throw new IllegalStateException("No active task builder session");
        }
        if (builderPath == null) {
            throw new IllegalArgumentException("Builder JSON path is required");
        }

        Path absoluteBuilderPath = builderPath.toAbsolutePath();
        Path parentDir = absoluteBuilderPath.getParent();
        if (parentDir == null) {
            parentDir = customTasksDir;
            absoluteBuilderPath = parentDir.resolve(absoluteBuilderPath.getFileName());
        }
        Files.createDirectories(parentDir);

        String displayName = preferredName == null || preferredName.trim().isEmpty()
                ? currentDefinition.getName()
                : preferredName.trim();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = stripBuilderExtension(absoluteBuilderPath.getFileName().toString());
        }

        currentDefinition.setName(displayName);
        currentDefinition.setUpdatedAt(System.currentTimeMillis());

        String className = toJavaIdentifier(stripBuilderExtension(absoluteBuilderPath.getFileName().toString()));
        Path javaPath = parentDir.resolve(className + ".java");

        TaskCodeGenerator generator = new TaskCodeGenerator();
        Files.writeString(javaPath, generator.generate(currentDefinition, className, displayName));
        mapper.writeValue(absoluteBuilderPath.toFile(), currentDefinition);

        logger.info("Saved custom task '{}' to {} and {}", className, javaPath, absoluteBuilderPath);
        return new CustomTaskSaveResult(javaPath, absoluteBuilderPath, className);
    }

    public Path getCustomTasksDirectory() {
        return customTasksDir;
    }

    /**
     * Captures a fresh screenshot from the active emulator.
     *
     * @return BufferedImage of the current emulator screen, or null on failure
     */
    public BufferedImage captureScreenshot() {
        if (activeEmulatorNumber == null) {
            logger.warn("No active emulator set for task builder");
            return null;
        }

        try {
            RawImageData rawImage = emuManager.captureScreen(activeEmulatorNumber);
            if (rawImage != null) {
                return TesseractOcrProvider.toBufferedImage(rawImage);
            }
        } catch (Exception e) {
            logger.error("Failed to capture screenshot for task builder", e);
        }
        return null;
    }

    // ========================================================================
    // Node execution
    // ========================================================================

    /**
     * Executes a finalized node on the emulator.
     * After execution, the node is marked as executed.
     *
     * @param node The node to execute
     * @return true if execution succeeded
     */
    public boolean executeNode(AutomationStep node) {
        if (activeEmulatorNumber == null) {
            logger.warn("Cannot execute node: no active emulator");
            return false;
        }

        try {
            boolean success = performNodeAction(node);
            if (success) {
                node.setExecuted(true);
                logger.info("Node executed successfully: {}", node.getSummary());
            }
            return success;
        } catch (Exception e) {
            logger.error("Failed to execute node: {}", node.getSummary(), e);
            return false;
        }
    }

    /**
     * Adds a node to the current definition and executes it.
     *
     * @param node The node to add and execute
     * @return true if both add and execute succeeded
     */
    public boolean addAndExecuteNode(AutomationStep node) {
        if (currentDefinition == null) {
            logger.warn("No active session. Call startSession() first.");
            return false;
        }
        currentDefinition.addNode(node);
        return executeNode(node);
    }

    /**
     * Adds a node to the current definition without executing it.
     */
    public void addNode(AutomationStep node) {
        if (currentDefinition == null) {
            logger.warn("No active session. Call startSession() first.");
            return;
        }
        currentDefinition.addNode(node);
    }

    /**
     * Removes a node from the current definition by index.
     */
    public void removeNode(int index) {
        if (currentDefinition != null) {
            currentDefinition.removeNode(index);
        }
    }

    // ========================================================================
    // Node action dispatch
    // ========================================================================

    /**
     * Performs the actual emulator action for a node.
     */
    private boolean performNodeAction(AutomationStep node) {
        return switch (node.getType()) {
            case TAP_POINT       -> executeTap(node);
            case WAIT            -> executeWait(node);
            case SWIPE           -> swipeScreen(node);
            case BACK_BUTTON     -> executeBackButton();
            case OCR_READ        -> executeOcr(node);
            case TEMPLATE_SEARCH -> executeTemplateSearch(node);
            case NAVIGATE        -> { logger.info("Navigate node recorded"); yield true; }
        };
    }

    // ── Tap ────────────────────────────────────────────────────────────────

    private boolean executeTap(AutomationStep node) {
        int tlX = node.getParamAsInt("tlX", -1);
        int tlY = node.getParamAsInt("tlY", -1);
        int brX = node.getParamAsInt("brX", -1);
        int brY = node.getParamAsInt("brY", -1);

        if (tlX < 0 || tlY < 0) {
            logger.warn("Invalid tap coordinates: TL({}, {})", tlX, tlY);
            return false;
        }

        // Route through the shared input layer so randomization and clamping
        // behave exactly like production task taps.
        tapInputService().tapInside(AreaData.of(tlX, tlY, Math.max(tlX, brX), Math.max(tlY, brY)));
        return true;
    }

    private TapInteractionService tapInputService() {
        if (tapService == null) {
            tapService = TapInteractionService.forController(emuManager, activeEmulatorNumber);
        }
        return tapService;
    }

    // ── Wait ───────────────────────────────────────────────────────────────

    private boolean executeWait(AutomationStep node) {
        int durationMs = node.getParamAsInt("durationMs", 1000);
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    // ── Swipe ──────────────────────────────────────────────────────────────

    private boolean swipeScreen(AutomationStep node) {
        int startX = node.getParamAsInt("startX", -1);
        int startY = node.getParamAsInt("startY", -1);
        int endX   = node.getParamAsInt("endX", -1);
        int endY   = node.getParamAsInt("endY", -1);

        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            logger.warn("Invalid swipe coordinates");
            return false;
        }

        emuManager.swipeScreen(activeEmulatorNumber,
                new PointData(startX, startY), new PointData(endX, endY));
        return true;
    }

    // ── Back Button ────────────────────────────────────────────────────────

    private boolean executeBackButton() {
        emuManager.pressBack(activeEmulatorNumber);
        return true;
    }

    // ── OCR ────────────────────────────────────────────────────────────────

    private boolean executeOcr(AutomationStep node) {
        int tlX = node.getParamAsInt("tlX", -1);
        int tlY = node.getParamAsInt("tlY", -1);
        int brX = node.getParamAsInt("brX", -1);
        int brY = node.getParamAsInt("brY", -1);

        if (tlX < 0 || tlY < 0 || brX < 0 || brY < 0) {
            logger.warn("OCR node missing region coordinates");
            node.setLastOcrResult("[missing coords]");
            return false;
        }

        try {
            RawImageData rawImage = emuManager.captureScreen(activeEmulatorNumber);
            if (rawImage == null) {
                node.setLastOcrResult("[no screenshot]");
                return false;
            }
            String ocrText = TesseractOcrProvider.recognizeText(rawImage,
                    new PointData(tlX, tlY), new PointData(brX, brY), "eng");
            node.setLastOcrResult(ocrText != null ? ocrText.trim() : "");
            logger.info("OCR result: '{}'", node.getLastOcrResult());
            return true;
        } catch (Exception ex) {
            logger.error("OCR execution failed", ex);
            node.setLastOcrResult("[error: " + ex.getMessage() + "]");
            return false;
        }
    }

    // ── Template Search ────────────────────────────────────────────────────

    private boolean executeTemplateSearch(AutomationStep node) {
        String templateName = node.getParam("templatePath");
        if (templateName == null || templateName.isEmpty()) {
            logger.warn("Template Search node missing templatePath");
            setTemplateResult(node, false, 0);
            return false;
        }

        try {
            int threshold       = node.getParamAsInt("threshold", 90);
            double thresholdPct = threshold;
            boolean grayscale   = "true".equals(node.getParam("grayscale"));
            int maxAttempts     = node.getParamAsInt("maxAttempts", 1);
            int delayMs         = node.getParamAsInt("delayMs", 300);

            // Optional search area
            PointData topLeft     = parseSearchAreaPoint(node, "tlX", "tlY");
            PointData bottomRight = parseSearchAreaPoint(node, "brX", "brY");
            boolean hasArea      = topLeft != null && bottomRight != null;

            // Execute the search with retries
            ImageSearchResultData result = null;
            boolean isCustomFile = templateName.startsWith(FILE_PREFIX);

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                result = isCustomFile
                        ? searchCustomTemplate(templateName, grayscale, hasArea, topLeft, bottomRight, thresholdPct)
                        : searchEnumTemplate(templateName, grayscale, hasArea, topLeft, bottomRight, thresholdPct);

                if (result != null && result.isFound()) break;
                if (attempt < maxAttempts) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }

            // Store result and optionally tap
            boolean found = result != null && result.isFound();
            double matchPct = result != null ? result.getMatchPercentage() : 0;
            setTemplateResult(node, found, matchPct);
            handleTapIfFound(node, result, found);

            logger.info("Template Search '{}': {} (match: {}%)", templateName,
                    found ? "FOUND" : "NOT FOUND", String.format("%.1f", matchPct));
            return true;

        } catch (IllegalArgumentException ex) {
            logger.error("Invalid template name: {}", templateName, ex);
            setTemplateResult(node, false, 0);
            return false;
        } catch (Exception ex) {
            logger.error("Template search execution failed", ex);
            setTemplateResult(node, false, 0);
            return false;
        }
    }

    /**
     * Parses an optional search area coordinate pair from node params.
     * Returns null if either param is missing.
     */
    private PointData parseSearchAreaPoint(AutomationStep node, String xKey, String yKey) {
        String xs = node.getParam(xKey);
        String ys = node.getParam(yKey);
        if (xs == null || ys == null) return null;
        try {
            return new PointData(Integer.parseInt(xs), Integer.parseInt(ys));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ImageSearchResultData searchCustomTemplate(String templateName, boolean grayscale,
                                                       boolean hasArea, PointData topLeft, PointData bottomRight,
                                                       double thresholdPct) {
        String absolutePath = templateName.substring(FILE_PREFIX.length());
        String method = grayscale ? "Grayscale" : "";

        if (hasArea) {
            return grayscale
                    ? emuManager.locatePatternMonoFromFile(activeEmulatorNumber, absolutePath, topLeft, bottomRight, thresholdPct)
                    : emuManager.locatePatternFromFile(activeEmulatorNumber, absolutePath, topLeft, bottomRight, thresholdPct);
        } else {
            return grayscale
                    ? emuManager.locatePatternMonoFromFile(activeEmulatorNumber, absolutePath, thresholdPct)
                    : emuManager.locatePatternFromFile(activeEmulatorNumber, absolutePath, thresholdPct);
        }
    }

    private ImageSearchResultData searchEnumTemplate(String templateName, boolean grayscale,
                                                     boolean hasArea, PointData topLeft, PointData bottomRight,
                                                     double thresholdPct) {
        TemplatesEnum template = TemplatesEnum.valueOf(templateName);

        if (hasArea) {
            return grayscale
                    ? emuManager.locatePatternMono(activeEmulatorNumber, template, topLeft, bottomRight, thresholdPct)
                    : emuManager.locatePattern(activeEmulatorNumber, template, topLeft, bottomRight, thresholdPct);
        } else {
            return grayscale
                    ? emuManager.locatePatternMono(activeEmulatorNumber, template, thresholdPct)
                    : emuManager.locatePattern(activeEmulatorNumber, template, thresholdPct);
        }
    }

    private void setTemplateResult(AutomationStep node, boolean found, double matchPct) {
        node.setParam("__lastSearchFound", String.valueOf(found));
        node.setParam("__lastMatchPct", String.format("%.1f", matchPct));
    }

    private void handleTapIfFound(AutomationStep node, ImageSearchResultData result, boolean found) {
        boolean tapIfFound = "true".equals(node.getParam("tapIfFound"));
        int offsetX = node.getParamAsInt("offsetX", 0);
        int offsetY = node.getParamAsInt("offsetY", 0);

        if (found && tapIfFound && result != null) {
            int tapX = result.getPoint().getX() + offsetX;
            int tapY = result.getPoint().getY() + offsetY;
            if (offsetX == 0 && offsetY == 0) {
                // No offset: tap inside the actual matched bounding region.
                tapInputService().tapInside(result);
            } else {
                tapInputService().tapNear(new PointData(tapX, tapY), TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
            }
            node.setParam("__lastTappedAt", tapX + ", " + tapY);
            logger.info("Tapped at {}, {}", tapX, tapY);
        } else {
            node.getParams().remove("__lastTappedAt");
        }
    }

    // ========================================================================
    // Getters & Setters
    // ========================================================================

    public AutomationBlueprint getCurrentDefinition() {
        return currentDefinition;
    }

    public String getActiveEmulatorNumber() {
        return activeEmulatorNumber;
    }

    public void setActiveEmulatorNumber(String emulatorNumber) {
        this.activeEmulatorNumber = emulatorNumber;
    }

    public boolean hasActiveSession() {
        return currentDefinition != null && activeEmulatorNumber != null;
    }

    private String toJavaClassName(String taskName) {
        String[] parts = taskName.trim().split("[^A-Za-z0-9]+");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            name.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                name.append(part.substring(1));
            }
        }
        if (name.isEmpty()) {
            name.append("GeneratedTask");
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, 'T');
        }

        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            sanitized.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
        }
        if (!sanitized.toString().endsWith("Task")) {
            sanitized.append("Task");
        }
        return sanitized.toString();
    }

    private String toJavaIdentifier(String fileBase) {
        String source = fileBase == null || fileBase.trim().isEmpty() ? "GeneratedTask" : fileBase.trim();
        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            sanitized.append(Character.isJavaIdentifierPart(ch) ? ch : '_');
        }
        while (!sanitized.isEmpty() && !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized.deleteCharAt(0);
        }
        return sanitized.isEmpty() ? "GeneratedTask" : sanitized.toString();
    }

    private String stripBuilderExtension(String fileName) {
        if (fileName != null && fileName.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - ".json".length());
        }
        return fileName;
    }
}
