package dev.frostguard.app.panel.taskbuilder;

import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Manages the Task Builder canvas: grid rendering, pan, zoom, and transform.
 *
 * <p>Extracted from {@code TaskBuilderLayoutController} to separate canvas
 * concerns from node/wire/property logic.</p>
 */
public class TaskBuilderCanvasManager {

    private final StackPane canvasContainer;
    private final Pane flowCanvas;
    private final Label zoomLabel;

    // Canvas transform state
    private double canvasOffsetX = 0;
    private double canvasOffsetY = 0;
    private double zoomScale = 1.0;
    private double panStartX, panStartY;
    private double panStartOffsetX, panStartOffsetY;
    private boolean isPanning = false;

    /** Callback invoked when the user clicks the canvas background (to deselect). */
    private Runnable onBackgroundClick;

    public TaskBuilderCanvasManager(StackPane canvasContainer, Pane flowCanvas, Label zoomLabel) {
        this.canvasContainer = canvasContainer;
        this.flowCanvas = flowCanvas;
        this.zoomLabel = zoomLabel;
    }

    public void setOnBackgroundClick(Runnable callback) {
        this.onBackgroundClick = callback;
    }

    // ========================================================================
    // Initialization
    // ========================================================================

    /**
     * Sets up canvas clipping so nodes don't overflow the container.
     */
    public void setupClipping() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(canvasContainer.widthProperty());
        clip.heightProperty().bind(canvasContainer.heightProperty());
        canvasContainer.setClip(clip);

        flowCanvas.setMinSize(0, 0);
        flowCanvas.setPrefSize(0, 0);
        flowCanvas.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        flowCanvas.setManaged(false);

        canvasContainer.setMinSize(0, 0);
    }

    /**
     * Sets up pan (right-click / middle-click / Ctrl+drag / background left-click)
     * and zoom (scroll) interactions on the canvas.
     *
     * @param onDragWireUpdate  callback for updating drag wire on mouse move (may be null)
     * @param onFinishDragWire  callback for finishing a drag wire on mouse release (may be null)
     */
    public void setupInteractions(Runnable onDragWireUpdate, Runnable onFinishDragWire) {
        canvasContainer.setOnScroll(this::handleZoomScroll);

        flowCanvas.setOnMousePressed(e -> {
            boolean isBackgroundClick = e.isPrimaryButtonDown() && e.getTarget() == flowCanvas;
            if (e.isMiddleButtonDown() || e.isSecondaryButtonDown() || e.isControlDown() || isBackgroundClick) {
                isPanning = true;
                panStartX = e.getScreenX();
                panStartY = e.getScreenY();
                panStartOffsetX = canvasOffsetX;
                panStartOffsetY = canvasOffsetY;
                flowCanvas.setCursor(Cursor.CLOSED_HAND);

                if (isBackgroundClick && this.onBackgroundClick != null) {
                    this.onBackgroundClick.run();
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
                applyTransform();
                e.consume();
            }
        });

        flowCanvas.setOnMouseReleased(e -> {
            if (isPanning) {
                isPanning = false;
                flowCanvas.setCursor(Cursor.DEFAULT);
                e.consume();
            }
            if (onFinishDragWire != null) {
                onFinishDragWire.run();
            }
        });

        flowCanvas.setOnMouseMoved(e -> {
            if (onDragWireUpdate != null) {
                onDragWireUpdate.run();
            }
        });
    }

    // ========================================================================
    // Grid
    // ========================================================================

    /**
     * Draws the background dot grid on the canvas.
     */
    public void drawGrid() {
        for (double x = -500; x < 5000; x += 25) {
            for (double y = -500; y < 3500; y += 25) {
                Circle dot = new Circle(x, y, 1.0);
                dot.getStyleClass().add("canvas-grid-dot");
                dot.setMouseTransparent(true);
                flowCanvas.getChildren().add(dot);
            }
        }
    }

    // ========================================================================
    // Zoom controls
    // ========================================================================

    public void zoomIn() {
        zoomScale = Math.min(2.5, zoomScale * 1.15);
        applyTransform();
        updateZoomLabel();
    }

    public void zoomOut() {
        zoomScale = Math.max(0.3, zoomScale * 0.85);
        applyTransform();
        updateZoomLabel();
    }

    public void zoomFit() {
        zoomScale = 1.0;
        canvasOffsetX = 0;
        canvasOffsetY = 0;
        applyTransform();
        updateZoomLabel();
    }

    private void handleZoomScroll(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
        double newScale = zoomScale * factor;
        newScale = Math.max(0.30, Math.min(2.50, newScale));

        double mouseX = e.getX();
        double mouseY = e.getY();

        double scaleChange = newScale / zoomScale;
        canvasOffsetX = mouseX - scaleChange * (mouseX - canvasOffsetX);
        canvasOffsetY = mouseY - scaleChange * (mouseY - canvasOffsetY);

        zoomScale = newScale;
        applyTransform();
        updateZoomLabel();
        e.consume();
    }

    // ========================================================================
    // Transform
    // ========================================================================

    private void applyTransform() {
        flowCanvas.setTranslateX(canvasOffsetX);
        flowCanvas.setTranslateY(canvasOffsetY);
        flowCanvas.setScaleX(zoomScale);
        flowCanvas.setScaleY(zoomScale);
    }

    private void updateZoomLabel() {
        if (zoomLabel != null) {
            zoomLabel.setText(Math.round(zoomScale * 100) + "%");
        }
    }

    // ========================================================================
    // Accessors
    // ========================================================================

    public boolean isPanning() {
        return isPanning;
    }

    public double getZoomScale() {
        return zoomScale;
    }
}
