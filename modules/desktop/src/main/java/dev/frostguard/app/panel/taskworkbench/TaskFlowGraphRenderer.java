package dev.frostguard.app.panel.taskworkbench;

import dev.frostguard.api.domain.TaskExecutionEventData;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskFlowDefinitionData;
import dev.frostguard.api.domain.TaskFlowEdgeData;
import dev.frostguard.api.domain.TaskFlowNodeData;
import dev.frostguard.api.domain.TaskStepStatus;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class TaskFlowGraphRenderer {

    static final double MIN_WIDTH = 900;

    private static final double NODE_WIDTH = 300;
    private static final double NODE_HEIGHT = 62;
    private static final double NODE_GAP = 54;
    private static final double GRAPH_MARGIN = 28;

    private TaskFlowGraphRenderer() {
    }

    static void render(Pane graphPane, TaskFlowDefinitionData flow,
            TaskExecutionSnapshotData snapshot) {
        graphPane.getChildren().clear();
        double graphWidth = Math.max(MIN_WIDTH, graphPane.getWidth());
        double centerX = graphWidth / 2;
        double graphHeight = GRAPH_MARGIN * 2
                + flow.nodes().size() * NODE_HEIGHT
                + Math.max(0, flow.nodes().size() - 1) * NODE_GAP;
        graphPane.setMinHeight(graphHeight);
        graphPane.setPrefHeight(graphHeight);

        Map<String, Integer> nodeIndexes = new HashMap<>();
        for (int index = 0; index < flow.nodes().size(); index++) {
            nodeIndexes.put(flow.nodes().get(index).id(), index);
        }
        int forwardLane = 0;
        int backwardLane = 0;
        for (TaskFlowEdgeData edge : flow.edges()) {
            int from = nodeIndexes.get(edge.fromStepId());
            int to = nodeIndexes.get(edge.toStepId());
            boolean adjacentForward = to == from + 1;
            int lane = adjacentForward ? 0 : to > from ? ++forwardLane : ++backwardLane;
            drawEdge(graphPane, edge, from, to, centerX, lane, adjacentForward);
        }
        for (int index = 0; index < flow.nodes().size(); index++) {
            drawNode(graphPane, flow.nodes().get(index), index, centerX, snapshot);
        }
    }

    private static void drawEdge(Pane graphPane, TaskFlowEdgeData edge, int fromIndex, int toIndex,
            double centerX, int lane, boolean adjacentForward) {
        double fromY = nodeY(fromIndex) + (toIndex > fromIndex ? NODE_HEIGHT : 0);
        double toY = nodeY(toIndex) + (toIndex > fromIndex ? 0 : NODE_HEIGHT);
        Path path = new Path(new MoveTo(centerX, fromY));
        double labelX;
        double labelY;
        if (adjacentForward) {
            path.getElements().add(new LineTo(centerX, toY));
            labelX = centerX + 10;
            labelY = (fromY + toY) / 2;
        } else {
            double direction = toIndex > fromIndex ? 1 : -1;
            double laneX = centerX + direction * (NODE_WIDTH / 2 + 54 + lane * 34);
            path.getElements().addAll(
                    new LineTo(laneX, fromY),
                    new LineTo(laneX, toY),
                    new LineTo(centerX, toY));
            labelX = laneX + (direction > 0 ? 7 : -132);
            labelY = (fromY + toY) / 2;
        }
        path.getStyleClass().add("workbench-flow-edge");
        path.setMouseTransparent(true);
        graphPane.getChildren().add(path);

        double arrowDirection = toIndex > fromIndex ? 1 : -1;
        Polygon arrow = new Polygon(
                centerX, toY,
                centerX - 5, toY - arrowDirection * 8,
                centerX + 5, toY - arrowDirection * 8);
        arrow.getStyleClass().add("workbench-flow-arrow");
        arrow.setMouseTransparent(true);
        graphPane.getChildren().add(arrow);

        if (!edge.label().isBlank()) {
            Text label = new Text(edge.label());
            label.getStyleClass().add("workbench-edge-label");
            label.setLayoutX(labelX);
            label.setLayoutY(labelY);
            label.setMouseTransparent(true);
            graphPane.getChildren().add(label);
        }
    }

    private static void drawNode(Pane graphPane, TaskFlowNodeData node, int index,
            double centerX, TaskExecutionSnapshotData snapshot) {
        NodeProgress progress = progressFor(node.id(), snapshot);
        Label title = new Label(node.label());
        title.getStyleClass().add("workbench-node-title");
        title.setWrapText(true);
        Label status = new Label(progress.label());
        status.getStyleClass().add("workbench-node-status");
        VBox content = new VBox(4, title, status);
        content.getStyleClass().addAll("workbench-flow-node", "workbench-node-" + progress.style());
        content.setLayoutX(centerX - NODE_WIDTH / 2);
        content.setLayoutY(nodeY(index));
        content.setMinSize(NODE_WIDTH, NODE_HEIGHT);
        content.setPrefSize(NODE_WIDTH, NODE_HEIGHT);
        content.setMaxSize(NODE_WIDTH, NODE_HEIGHT);
        graphPane.getChildren().add(content);
    }

    static NodeProgress progressFor(String stepId, TaskExecutionSnapshotData snapshot) {
        TaskStepStatus latest = null;
        int completed = 0;
        for (TaskExecutionEventData event : snapshot.history()) {
            if (!Objects.equals(stepId, event.stepId())) {
                continue;
            }
            latest = event.status();
            if (event.status() == TaskStepStatus.COMPLETED) {
                completed++;
            }
        }
        if (Objects.equals(stepId, snapshot.currentStepId())) {
            latest = TaskStepStatus.STARTED;
        } else if (Objects.equals(stepId, snapshot.nextStepId())) {
            latest = TaskStepStatus.WAITING;
        }
        if (latest == null) {
            return new NodeProgress("not-run", "NOT RUN");
        }
        String style = latest.name().toLowerCase();
        String label = latest == TaskStepStatus.STARTED ? "RUNNING" : latest.name();
        if (completed > 1) {
            label += " - " + completed + " completed";
        } else if (completed == 1 && latest == TaskStepStatus.WAITING) {
            label += " - 1 completed";
        }
        return new NodeProgress(style, label);
    }

    private static double nodeY(int index) {
        return GRAPH_MARGIN + index * (NODE_HEIGHT + NODE_GAP);
    }

    record NodeProgress(String style, String label) {
    }
}
