package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.StrokeLineCap;

import java.util.*;

/**
 * Manages connection wires (edges) between nodes on the Task Builder canvas.
 *
 * <p>Extracted from {@code TaskBuilderLayoutController} to isolate all wiring
 * logic: temporary drag wires, permanent connections, and Yes/No labels.</p>
 */
public class TaskBuilderWireManager {

    private final Pane flowCanvas;

    // References to node UI components (shared with controller)
    private final Map<Integer, VBox> nodeCards;
    private final Map<Integer, Circle> inputPorts;
    private final Map<Integer, Circle> outputPorts;
    private final Map<Integer, Circle> outputPortsFalse;

    // Wire tracking
    private final Map<String, CubicCurve> connectionWires = new LinkedHashMap<>();
    private final List<javafx.scene.Node> wireOverlays = new ArrayList<>();

    // Drag wire state
    private CubicCurve dragWire;
    private boolean isDragging = false;
    private int dragSourceNodeId = -1;
    private boolean dragFromFalsePort = false;

    public TaskBuilderWireManager(Pane flowCanvas,
                                   Map<Integer, VBox> nodeCards,
                                   Map<Integer, Circle> inputPorts,
                                   Map<Integer, Circle> outputPorts,
                                   Map<Integer, Circle> outputPortsFalse) {
        this.flowCanvas = flowCanvas;
        this.nodeCards = nodeCards;
        this.inputPorts = inputPorts;
        this.outputPorts = outputPorts;
        this.outputPortsFalse = outputPortsFalse;
    }

    // ========================================================================
    // Drag Wire (temporary wire while user is connecting nodes)
    // ========================================================================

    /**
     * Starts a connection drag from an output port.
     *
     * @param sourceNodeId    the ID of the source node
     * @param fromFalsePort   true if dragging from the "No/False" port
     * @param startX          starting X coordinate
     * @param startY          starting Y coordinate
     */
    public void startDrag(int sourceNodeId, boolean fromFalsePort, double startX, double startY) {
        dragSourceNodeId = sourceNodeId;
        dragFromFalsePort = fromFalsePort;
        isDragging = true;

        dragWire = new CubicCurve();
        dragWire.setStartX(startX);
        dragWire.setStartY(startY);
        dragWire.setEndX(startX);
        dragWire.setEndY(startY);
        dragWire.setControlX1(startX);
        dragWire.setControlY1(startY);
        dragWire.setControlX2(startX);
        dragWire.setControlY2(startY);
        dragWire.setFill(null);
        dragWire.setStroke(Color.web("#7c3aed88"));
        dragWire.setStrokeWidth(2);
        dragWire.getStrokeDashArray().addAll(6.0, 4.0);
        dragWire.setMouseTransparent(true);
        flowCanvas.getChildren().add(dragWire);
    }

    /**
     * Updates the drag wire endpoint to follow the mouse.
     *
     * @param mouseX current mouse X on canvas
     * @param mouseY current mouse Y on canvas
     */
    public void updateDrag(double mouseX, double mouseY) {
        if (!isDragging || dragWire == null) return;

        dragWire.setEndX(mouseX);
        dragWire.setEndY(mouseY);

        double deltaX = mouseX - dragWire.getStartX();
        double deltaY = mouseY - dragWire.getStartY();
        
        if (deltaX < 30) {
            double yDir = (deltaY < -40) ? -1 : 1; 
            double hOffset = Math.max(90, Math.abs(deltaX) * 0.15);
            double vOffset = Math.max(140, Math.abs(deltaX) * 0.15 + Math.abs(deltaY) * 0.1);
            
            dragWire.setControlX1(dragWire.getStartX() + hOffset);
            dragWire.setControlY1(dragWire.getStartY() + (vOffset * yDir));
            dragWire.setControlX2(mouseX - hOffset);
            dragWire.setControlY2(mouseY + (vOffset * yDir));
        } else {
            double offset = Math.max(60, deltaX * 0.45);
            dragWire.setControlX1(dragWire.getStartX() + offset);
            dragWire.setControlY1(dragWire.getStartY());
            dragWire.setControlX2(mouseX - offset);
            dragWire.setControlY2(mouseY);
        }
    }

    /**
     * Finishes a drag operation — checks if the mouse is over an input port
     * and if so, connects the nodes.
     *
     * @param definition   the current task flow definition
     * @param findNode     a function to find a node by ID
     * @return the target node ID if connected, or -1 if no connection was made
     */
    public int finishDrag(AutomationBlueprint definition, java.util.function.IntFunction<AutomationStep> findNode) {
        if (!isDragging) return -1;

        int targetNodeId = -1;

        if (dragWire != null) {
            // Find input port under the mouse
            for (Map.Entry<Integer, Circle> entry : inputPorts.entrySet()) {
                Circle port = entry.getValue();
                if (port.getBoundsInParent().contains(dragWire.getEndX(), dragWire.getEndY())) {
                    targetNodeId = entry.getKey();
                    break;
                }
            }

            // Connect if valid and not self-referencing
            if (targetNodeId > 0 && targetNodeId != dragSourceNodeId) {
                completeConnection(definition, findNode, dragSourceNodeId, targetNodeId, dragFromFalsePort);
            }

            flowCanvas.getChildren().remove(dragWire);
            dragWire = null;
        }

        isDragging = false;
        dragSourceNodeId = -1;
        dragFromFalsePort = false;

        return targetNodeId;
    }

    /**
     * Cancels the current drag without connecting.
     */
    public void cancelDrag() {
        if (dragWire != null) {
            flowCanvas.getChildren().remove(dragWire);
            dragWire = null;
        }
        isDragging = false;
        dragSourceNodeId = -1;
        dragFromFalsePort = false;
    }

    private void completeConnection(AutomationBlueprint definition,
                                     java.util.function.IntFunction<AutomationStep> findNode,
                                     int sourceId, int targetId, boolean isFalseBranch) {
        AutomationStep sourceNode = findNode.apply(sourceId);
        if (sourceNode == null) return;

        if (isFalseBranch) {
            sourceNode.setNextNodeFalseId(targetId);
        } else {
            sourceNode.setNextNodeId(targetId);
        }

        rebuildAllWires(definition, findNode);
    }

    // ========================================================================
    // Permanent Wires
    // ========================================================================

    /**
     * Rebuilds all permanent connection wires from the current definition.
     */
    public void rebuildAllWires(AutomationBlueprint definition, java.util.function.IntFunction<AutomationStep> findNode) {
        // Remove old wires and overlays
        connectionWires.values().forEach(w -> flowCanvas.getChildren().remove(w));
        connectionWires.clear();
        wireOverlays.forEach(n -> flowCanvas.getChildren().remove(n));
        wireOverlays.clear();

        if (definition == null) return;

        for (AutomationStep node : definition.getNodes()) {
            // True/Yes connection
            if (node.getNextNodeId() > 0) {
                Circle outPort = outputPorts.get(node.getId());
                Circle inPort  = inputPorts.get(node.getNextNodeId());
                if (outPort != null && inPort != null) {
                    boolean isBranching = isBranchingNode(node.getType());
                    CubicCurve wire = makeWire(outPort, inPort, isBranching ? "#59ba59" : "#7c3aed");
                    String key = node.getId() + "→" + node.getNextNodeId();
                    connectionWires.put(key, wire);
                    flowCanvas.getChildren().add(wire);

                    if (isBranching) {
                        addWireLabel(wire, "Yes", "#59ba59");
                    }
                }
            }

            // False/No connection
            if (node.getNextNodeFalseId() > 0) {
                Circle fpPort = outputPortsFalse.get(node.getId());
                Circle inPort = inputPorts.get(node.getNextNodeFalseId());
                if (fpPort != null && inPort != null) {
                    CubicCurve wire = makeWire(fpPort, inPort, "#ef4444");
                    String key = node.getId() + "→F" + node.getNextNodeFalseId();
                    connectionWires.put(key, wire);
                    flowCanvas.getChildren().add(wire);
                    addWireLabel(wire, "No", "#ef4444");
                }
            }
        }
    }

    private CubicCurve makeWire(Circle from, Circle to, String color) {
        double startX = from.getLayoutX();
        double startY = from.getLayoutY();
        double endX   = to.getLayoutX();
        double endY   = to.getLayoutY();

        double deltaX = endX - startX;
        double deltaY = endY - startY;

        CubicCurve wire = new CubicCurve();
        wire.setStartX(startX);
        wire.setStartY(startY);
        wire.setEndX(endX);
        wire.setEndY(endY);
        
        if (deltaX < 30) {
            double yDir = (deltaY < -40) ? -1 : 1; 
            double hOffset = Math.max(90, Math.abs(deltaX) * 0.15);
            double vOffset = Math.max(140, Math.abs(deltaX) * 0.15 + Math.abs(deltaY) * 0.1);
            
            wire.setControlX1(startX + hOffset);
            wire.setControlY1(startY + (vOffset * yDir));
            wire.setControlX2(endX - hOffset);
            wire.setControlY2(endY + (vOffset * yDir));
        } else {
            // Forward edge logic
            double offset = Math.max(60, deltaX * 0.45);
            wire.setControlX1(startX + offset);
            wire.setControlY1(startY);
            wire.setControlX2(endX - offset);
            wire.setControlY2(endY);
        }

        wire.setFill(null);
        wire.setStroke(Color.web(color));
        wire.setStrokeWidth(2);
        wire.setStrokeLineCap(StrokeLineCap.ROUND);
        wire.setMouseTransparent(true);
        return wire;
    }

    private void addWireLabel(CubicCurve wire, String text, String color) {
        Label label = new Label(text);
        label.setStyle(
            "-fx-font-size: 9px; -fx-font-weight: 700; " +
            "-fx-text-fill: " + color + "; " +
            "-fx-background-color: #1e2233cc; " +
            "-fx-background-radius: 6; -fx-padding: 1 6;"
        );
        label.setMouseTransparent(true);

        double midX = (wire.getStartX() + wire.getEndX()) / 2;
        double midY = (wire.getStartY() + wire.getEndY()) / 2;
        label.setLayoutX(midX - 12);
        label.setLayoutY(midY - 10);

        wireOverlays.add(label);
        flowCanvas.getChildren().add(label);
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private boolean isBranchingNode(FlowStepKind type) {
        return type == FlowStepKind.OCR_READ || type == FlowStepKind.TEMPLATE_SEARCH;
    }

    public boolean isDragging() {
        return isDragging;
    }

    public Map<String, CubicCurve> getConnectionWires() {
        return connectionWires;
    }

    public List<javafx.scene.Node> getWireOverlays() {
        return wireOverlays;
    }
}
