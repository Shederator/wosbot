package dev.frostguard.app.shared;

import javafx.scene.Node;

public final class StyleConstants {

    public static final String PRIORITY_LIST_VIEW = "priority-list-view";
    public static final String PRIORITY_LIST_CELL = "priority-list-cell";
    public static final String PRIORITY_LIST_CELL_HOVER = "priority-list-cell-hover";
    public static final String PRIORITY_LIST_CELL_DRAG_OVER_TOP = "priority-list-cell-drop-before";
    public static final String PRIORITY_LIST_CELL_DRAG_OVER_BOTTOM = "priority-list-cell-drop-after";
    public static final String PRIORITY_LIST_CELL_DISABLED = "priority-list-cell-muted";
    public static final String PRIORITY_DRAG_INDICATOR = "priority-rank-handle";
    public static final String PRIORITY_DRAG_INDICATOR_DISABLED = "priority-rank-handle-muted";
    public static final String PRIORITY_LABEL = "priority-rank";
    public static final String PRIORITY_LABEL_DISABLED = "priority-rank-muted";
    public static final String PRIORITY_NAME_LABEL = "priority-title";
    public static final String PRIORITY_NAME_LABEL_DISABLED = "priority-title-muted";

    private StyleConstants() {
    }

    public static void ensure(Node node, String styleClass) {
        if (!node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    public static void replace(Node node, String enabledClass, String disabledClass, boolean enabled) {
        node.getStyleClass().removeAll(enabledClass, disabledClass);
        node.getStyleClass().add(enabled ? enabledClass : disabledClass);
    }
}
