package dev.frostguard.app.bootstrap;

import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Provides native-like edge & corner resizing for an UNDECORATED JavaFX Stage.
 * Tracks screen-coordinate deltas so resize works correctly in all directions
 * and at any window size.
 */
public class WindowResizer {

    private static final int RESIZE_MARGIN = 6;

    public static void makeResizable(Stage stage) {
        final ResizeListener listener = new ResizeListener(stage);

        stage.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(MouseEvent.MOUSE_MOVED, listener);
                oldScene.removeEventHandler(MouseEvent.MOUSE_PRESSED, listener);
                oldScene.removeEventHandler(MouseEvent.MOUSE_DRAGGED, listener);
                oldScene.removeEventHandler(MouseEvent.MOUSE_RELEASED, listener);
            }
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_MOVED, listener);
                newScene.addEventHandler(MouseEvent.MOUSE_PRESSED, listener);
                newScene.addEventHandler(MouseEvent.MOUSE_DRAGGED, listener);
                newScene.addEventHandler(MouseEvent.MOUSE_RELEASED, listener);
            }
        });

        if (stage.getScene() != null) {
            stage.getScene().addEventFilter(MouseEvent.MOUSE_MOVED, listener);
            stage.getScene().addEventHandler(MouseEvent.MOUSE_PRESSED, listener);
            stage.getScene().addEventHandler(MouseEvent.MOUSE_DRAGGED, listener);
            stage.getScene().addEventHandler(MouseEvent.MOUSE_RELEASED, listener);
        }
    }

    private static class ResizeListener implements EventHandler<MouseEvent> {
        private final Stage stage;
        private Cursor cursorEvent = Cursor.DEFAULT;
        private boolean resizing = false;

        // Screen coordinates at drag start
        private double dragStartScreenX;
        private double dragStartScreenY;
        // Stage geometry at drag start
        private double dragStartStageX;
        private double dragStartStageY;
        private double dragStartStageW;
        private double dragStartStageH;

        public ResizeListener(Stage stage) {
            this.stage = stage;
        }

        @Override
        public void handle(MouseEvent e) {
            EventType<? extends MouseEvent> type = e.getEventType();
            Scene scene = stage.getScene();
            if (scene == null) return;

            double mx = e.getSceneX();
            double my = e.getSceneY();
            double sw = scene.getWidth();
            double sh = scene.getHeight();

            if (MouseEvent.MOUSE_MOVED.equals(type)) {
                cursorEvent = getCursorForPosition(mx, my, sw, sh);
                scene.setCursor(cursorEvent);

            } else if (MouseEvent.MOUSE_PRESSED.equals(type)) {
                if (cursorEvent != Cursor.DEFAULT) {
                    resizing = true;
                    dragStartScreenX = e.getScreenX();
                    dragStartScreenY = e.getScreenY();
                    dragStartStageX = stage.getX();
                    dragStartStageY = stage.getY();
                    dragStartStageW = stage.getWidth();
                    dragStartStageH = stage.getHeight();
                    e.consume();
                }

            } else if (MouseEvent.MOUSE_DRAGGED.equals(type)) {
                if (!resizing) return;

                double dx = e.getScreenX() - dragStartScreenX;
                double dy = e.getScreenY() - dragStartScreenY;

                double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 200;
                double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 200;

                // Right edge
                if (cursorEvent == Cursor.E_RESIZE || cursorEvent == Cursor.SE_RESIZE || cursorEvent == Cursor.NE_RESIZE) {
                    double newW = Math.max(minW, dragStartStageW + dx);
                    stage.setWidth(newW);
                }

                // Bottom edge
                if (cursorEvent == Cursor.S_RESIZE || cursorEvent == Cursor.SE_RESIZE || cursorEvent == Cursor.SW_RESIZE) {
                    double newH = Math.max(minH, dragStartStageH + dy);
                    stage.setHeight(newH);
                }

                // Left edge
                if (cursorEvent == Cursor.W_RESIZE || cursorEvent == Cursor.SW_RESIZE || cursorEvent == Cursor.NW_RESIZE) {
                    double newW = Math.max(minW, dragStartStageW - dx);
                    double newX = dragStartStageX + (dragStartStageW - newW);
                    stage.setX(newX);
                    stage.setWidth(newW);
                }

                // Top edge
                if (cursorEvent == Cursor.N_RESIZE || cursorEvent == Cursor.NW_RESIZE || cursorEvent == Cursor.NE_RESIZE) {
                    double newH = Math.max(minH, dragStartStageH - dy);
                    double newY = dragStartStageY + (dragStartStageH - newH);
                    stage.setY(newY);
                    stage.setHeight(newH);
                }

                e.consume();

            } else if (MouseEvent.MOUSE_RELEASED.equals(type)) {
                resizing = false;
            }
        }

        private Cursor getCursorForPosition(double x, double y, double w, double h) {
            boolean left = x < RESIZE_MARGIN;
            boolean right = x > w - RESIZE_MARGIN;
            boolean top = y < RESIZE_MARGIN;
            boolean bottom = y > h - RESIZE_MARGIN;

            if (left && top)     return Cursor.NW_RESIZE;
            if (left && bottom)  return Cursor.SW_RESIZE;
            if (right && top)    return Cursor.NE_RESIZE;
            if (right && bottom) return Cursor.SE_RESIZE;
            if (left)            return Cursor.W_RESIZE;
            if (right)           return Cursor.E_RESIZE;
            if (top)             return Cursor.N_RESIZE;
            if (bottom)          return Cursor.S_RESIZE;
            return Cursor.DEFAULT;
        }
    }
}
