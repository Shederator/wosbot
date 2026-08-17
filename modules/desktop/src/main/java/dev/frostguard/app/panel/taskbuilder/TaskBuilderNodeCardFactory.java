package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationStep;
import dev.frostguard.engine.service.BranchEvaluator;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * Factory and utility class for creating and refreshing flow-node UI cards
 * on the Task Builder canvas.
 *
 * <p>Extracted from {@code TaskBuilderLayoutController} to separate the
 * visual card construction from the controller's orchestration logic.</p>
 */
public class TaskBuilderNodeCardFactory {

    // ========================================================================
    // Icon & Style mappings
    // ========================================================================

    public static String getIcon(FlowStepKind type) {
        return switch (type) {
            case TAP_POINT       -> "👆";
            case WAIT            -> "⏳";
            case SWIPE           -> "↔️";
            case BACK_BUTTON     -> "🔙";
            case OCR_READ        -> "📝";
            case TEMPLATE_SEARCH -> "🔍";
            case NAVIGATE        -> "🏠";
        };
    }

    public static String getHeaderStyleClass(FlowStepKind type) {
        return switch (type) {
            case TAP_POINT       -> "flow-node-header-tap";
            case WAIT            -> "flow-node-header-wait";
            case SWIPE           -> "flow-node-header-swipe";
            case BACK_BUTTON     -> "flow-node-header-back";
            case OCR_READ        -> "flow-node-header-ocr";
            case TEMPLATE_SEARCH -> "flow-node-header-template";
            case NAVIGATE        -> "flow-node-header-tap";
        };
    }

    public static String getAccentColor(FlowStepKind type) {
        return switch (type) {
            case TAP_POINT       -> "#7c3aed";
            case WAIT            -> "#155dfc";
            case SWIPE           -> "#3dc3c3";
            case BACK_BUTTON     -> "#ff8c42";
            case OCR_READ        -> "#59ba59";
            case TEMPLATE_SEARCH -> "#ef4444";
            case NAVIGATE        -> "#7c3aed";
        };
    }

    // ========================================================================
    // Card Refresh — Updates card content after execution
    // ========================================================================

    /**
     * Refreshes a node card's visual state: summary text, execution status dot,
     * OCR result banner, and Template Search result banner.
     *
     * @param node             the node whose card to refresh
     * @param nodeCards         map of nodeId → card VBox
     * @param inputPorts        port registry (for repositioning after banner appears)
     * @param outputPorts       port registry
     * @param outputPortsFalse  port registry (false branch)
     * @param wireRebuildAction callback to rebuild all wires after layout changes
     */
    public static void refreshCard(AutomationStep node,
                                    Map<Integer, VBox> nodeCards,
                                    Map<Integer, Circle> inputPorts,
                                    Map<Integer, Circle> outputPorts,
                                    Map<Integer, Circle> outputPortsFalse,
                                    Runnable wireRebuildAction) {

        VBox card = nodeCards.get(node.getId());
        if (card == null) return;

        // Update optional node name in header
        updateNodeNameLabel(card, node);

        // Update summary label in body
        updateSummaryLabel(card, node);

        // Update status dot in header
        updateStatusDot(card, node);

        // Update executed CSS class
        if (node.isExecuted()) {
            if (!card.getStyleClass().contains("flow-node-executed")) {
                card.getStyleClass().add("flow-node-executed");
            }
        } else {
            card.getStyleClass().remove("flow-node-executed");
        }

        // OCR result banner
        refreshOcrBanner(node, card, inputPorts, outputPorts, outputPortsFalse, wireRebuildAction);

        // Template search result banner
        refreshTemplateBanner(node, card, inputPorts, outputPorts, outputPortsFalse, wireRebuildAction);
    }

    private static void updateNodeNameLabel(VBox card, AutomationStep node) {
        if (card.getChildren().isEmpty() || !(card.getChildren().get(0) instanceof HBox header)) return;
        if (header.getChildren().size() < 2 || !(header.getChildren().get(1) instanceof VBox titleBlock)) return;

        for (javafx.scene.Node child : titleBlock.getChildren()) {
            if ("node-name-label".equals(child.getId()) && child instanceof Label label) {
                String nodeName = node.getNodeName();
                boolean hasName = !nodeName.isEmpty();
                label.setText(nodeName);
                label.setVisible(hasName);
                label.setManaged(hasName);
                return;
            }
        }
    }

    private static void updateSummaryLabel(VBox card, AutomationStep node) {
        if (card.getChildren().size() >= 3 && card.getChildren().get(2) instanceof VBox body) {
            if (!body.getChildren().isEmpty() && body.getChildren().get(0) instanceof Label l) {
                l.setText(node.getSummary());
            }
        }
    }

    private static void updateStatusDot(VBox card, AutomationStep node) {
        if (card.getChildren().get(0) instanceof HBox header) {
            if (!header.getChildren().isEmpty()) {
                javafx.scene.Node last = header.getChildren().get(header.getChildren().size() - 1);
                if (last instanceof Circle dot) {
                    dot.setFill(node.isExecuted() ? Color.web("#59ba59") : Color.web("#4a5568"));
                }
            }
        }
    }

    // ========================================================================
    // OCR Result Banner
    // ========================================================================

    private static void refreshOcrBanner(AutomationStep node, VBox card,
                                          Map<Integer, Circle> inputPorts,
                                          Map<Integer, Circle> outputPorts,
                                          Map<Integer, Circle> outputPortsFalse,
                                          Runnable wireRebuildAction) {
        card.getChildren().removeIf(n -> "ocr-result-banner".equals(n.getId()));

        if (node.getType() != FlowStepKind.OCR_READ || node.getLastOcrResult() == null) return;

        String result    = node.getLastOcrResult();
        String expected  = node.getParam("expectedValue") != null ? node.getParam("expectedValue") : "";
        String condition = node.getParam("condition") != null ? node.getParam("condition") : "CONTAINS";

        boolean matches = BranchEvaluator.evaluateOcrCondition(result, condition, expected);

        VBox banner = createResultBanner("ocr-result-banner");

        Label titleLbl = new Label("▸ OCR Result");
        titleLbl.setStyle("-fx-text-fill: #636a75; -fx-font-size: 9px; -fx-font-weight: 700;");

        Label resultLbl = new Label(result.isEmpty() ? "(empty)" : result);
        resultLbl.setWrapText(true);
        resultLbl.setStyle(
            "-fx-text-fill: #59ba59;" +
            "-fx-font-size: 11px;" +
            "-fx-font-family: 'JetBrains Mono', 'Consolas', 'Courier New', monospace;" +
            "-fx-background-color: #11141a;" +
            "-fx-background-radius: 4;" +
            "-fx-padding: 4 8;"
        );

        String matchColor = matches ? "#59ba59" : "#ef4444";
        String matchIcon  = matches ? "✓ YES" : "✗ NO";
        Label matchLbl = new Label(matchIcon + "  matches '" + expected + "'");
        matchLbl.setStyle(
            "-fx-text-fill: " + matchColor + ";" +
            "-fx-font-size: 10px;" +
            "-fx-font-weight: 700;"
        );

        banner.getChildren().addAll(titleLbl, resultLbl, matchLbl);
        card.getChildren().add(banner);

        repositionPortsAfterBanner(node, card, inputPorts, outputPorts, outputPortsFalse, wireRebuildAction);
    }

    // ========================================================================
    // Template Search Result Banner
    // ========================================================================

    private static void refreshTemplateBanner(AutomationStep node, VBox card,
                                               Map<Integer, Circle> inputPorts,
                                               Map<Integer, Circle> outputPorts,
                                               Map<Integer, Circle> outputPortsFalse,
                                               Runnable wireRebuildAction) {
        card.getChildren().removeIf(n -> "tpl-result-banner".equals(n.getId()));

        if (node.getType() != FlowStepKind.TEMPLATE_SEARCH) return;
        if (node.getParam("__lastSearchFound") == null) return;

        boolean found    = BranchEvaluator.evaluateTemplateResult(node);
        String matchPct  = node.getParam("__lastMatchPct") != null ? node.getParam("__lastMatchPct") : "?";
        String tappedAt  = node.getParam("__lastTappedAt");

        VBox banner = createResultBanner("tpl-result-banner");

        Label titleLbl = new Label("▸ Search Result");
        titleLbl.setStyle("-fx-text-fill: #636a75; -fx-font-size: 9px; -fx-font-weight: 700;");

        String matchColor = found ? "#59ba59" : "#ef4444";
        String matchIcon  = found ? "✓ FOUND" : "✗ NOT FOUND";
        Label matchLbl = new Label(matchIcon + "  (match: " + matchPct + "%)");
        matchLbl.setStyle(
            "-fx-text-fill: " + matchColor + ";" +
            "-fx-font-size: 11px;" +
            "-fx-font-weight: 700;" +
            "-fx-font-family: 'JetBrains Mono', 'Consolas', 'Courier New', monospace;" +
            "-fx-background-color: #11141a;" +
            "-fx-background-radius: 4;" +
            "-fx-padding: 4 8;"
        );

        banner.getChildren().addAll(titleLbl, matchLbl);

        if (tappedAt != null && !tappedAt.isEmpty()) {
            Label tapLbl = new Label("👆 Tapped at: " + tappedAt);
            tapLbl.setStyle(
                "-fx-text-fill: #fcd176;" +
                "-fx-font-size: 10px;" +
                "-fx-font-weight: 700;"
            );
            banner.getChildren().add(tapLbl);
        }

        card.getChildren().add(banner);

        repositionPortsAfterBanner(node, card, inputPorts, outputPorts, outputPortsFalse, wireRebuildAction);
    }

    // ========================================================================
    // Shared Helpers
    // ========================================================================

    private static VBox createResultBanner(String bannerId) {
        VBox banner = new VBox(4);
        banner.setId(bannerId);
        banner.setStyle(
            "-fx-background-color: #0d0f15;" +
            "-fx-background-radius: 0 0 10 10;" +
            "-fx-padding: 8 14 10 14;" +
            "-fx-border-color: #262a36; -fx-border-width: 1 0 0 0;"
        );
        return banner;
    }

    private static void repositionPortsAfterBanner(AutomationStep node, VBox card,
                                                     Map<Integer, Circle> inputPorts,
                                                     Map<Integer, Circle> outputPorts,
                                                     Map<Integer, Circle> outputPortsFalse,
                                                     Runnable wireRebuildAction) {
        Circle inPort  = inputPorts.get(node.getId());
        Circle outPort = outputPorts.get(node.getId());
        Circle fpPort  = outputPortsFalse.get(node.getId());
        if (inPort == null || outPort == null) return;

        Platform.runLater(() -> {
            if (fpPort != null) {
                updatePortPosFull(node, card, inPort, outPort, fpPort);
            } else {
                updatePortPos(node, card, inPort, outPort);
            }
            if (wireRebuildAction != null) {
                wireRebuildAction.run();
            }
        });
    }

    // ========================================================================
    // Port Positioning
    // ========================================================================

    /**
     * Positions ports with measured card dimensions (after layout).
     */
    public static void updatePortPos(AutomationStep node, VBox card, Circle in, Circle out) {
        boolean isBack = node != null && node.getType() == FlowStepKind.BACK_BUTTON;
        double defaultW = isBack ? 60 : 220;
        double defaultH = isBack ? 60 : 85;
        double h = card.getHeight() > 0 ? card.getHeight() : defaultH;
        double w = card.getWidth()  > 0 ? card.getWidth()  : defaultW;
        in.setCenterX(card.getLayoutX());
        in.setCenterY(card.getLayoutY() + h / 2);
        out.setCenterX(card.getLayoutX() + w);
        out.setCenterY(card.getLayoutY() + h / 2);
    }

    /**
     * Positions all three ports (in, true-out, false-out) with measured dimensions.
     */
    public static void updatePortPosFull(AutomationStep node, VBox card, Circle in, Circle out, Circle outFalse) {
        double defaultW = 220;
        double defaultH = 95;
        double h = card.getHeight() > 0 ? card.getHeight() : defaultH;
        double w = card.getWidth()  > 0 ? card.getWidth()  : defaultW;
        in.setCenterX(card.getLayoutX());
        in.setCenterY(card.getLayoutY() + h / 2);
        out.setCenterX(card.getLayoutX() + w);
        out.setCenterY(card.getLayoutY() + 26);
        outFalse.setCenterX(card.getLayoutX() + w);
        outFalse.setCenterY(card.getLayoutY() + 50);
        out.toFront();
        outFalse.toFront();
    }

    /**
     * Positions ports immediately using estimated default card dimensions (before CSS layout).
     */
    public static void updatePortPosWithDefaults(AutomationStep node, VBox card, Circle in, Circle out, Circle outFalse) {
        boolean isBack = node.getType() == FlowStepKind.BACK_BUTTON;
        double defaultW = isBack ? 60 : 220;
        double defaultH = isBack ? 60 : 85;
        double x = card.getLayoutX();
        double y = card.getLayoutY();

        in.setCenterX(x);
        in.setCenterY(y + defaultH / 2);
        out.setCenterX(x + defaultW);
        out.setCenterY(y + defaultH / 2);

        if (outFalse != null) {
            out.setCenterY(y + 26);
            outFalse.setCenterX(x + defaultW);
            outFalse.setCenterY(y + 50);
        }
    }
}
