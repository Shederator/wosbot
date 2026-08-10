package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationStep;

import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Manages the emulator preview panel: crosshair rendering, coordinate tracking,
 * and selection box for region-based actions (tap zones, OCR regions, etc.).
 *
 * <p>Extracted from {@code TaskBuilderLayoutController} to isolate the preview
 * interaction logic from the main controller.</p>
 */
public class TaskBuilderPreviewManager {

    private final ImageView previewImageView;
    private final Pane crosshairPane;
    private final Line crossX;
    private final Line crossY;
    private final Label coordsLabel;
    private final Rectangle selectionBox;

    private boolean hasPreviewImage = false;
    private double dragStartX, dragStartY;

    /** Callback to apply coordinate values to a node. */
    public interface CoordinateApplier {
        /** Called when the user releases the mouse, providing the computed coordinates. */
        void applyCoordinates(AutomationStep node, int tlX, int tlY, int brX, int brY);
    }

    public TaskBuilderPreviewManager(ImageView previewImageView,
                                      Pane crosshairPane,
                                      Line crossX, Line crossY,
                                      Label coordsLabel,
                                      Rectangle selectionBox) {
        this.previewImageView = previewImageView;
        this.crosshairPane = crosshairPane;
        this.crossX = crossX;
        this.crossY = crossY;
        this.coordsLabel = coordsLabel;
        this.selectionBox = selectionBox;
    }

    // ========================================================================
    // State
    // ========================================================================

    public void setHasPreviewImage(boolean has) {
        this.hasPreviewImage = has;
    }

    public boolean hasPreviewImage() {
        return hasPreviewImage;
    }

    // ========================================================================
    // Mouse events — these should be called directly from the FXML handlers
    // ========================================================================

    /**
     * Handles mouse press on the preview pane.
     */
    public void handleMousePressed(MouseEvent e) {
        if (previewImageView.getImage() == null) return;
        dragStartX = e.getX();
        dragStartY = e.getY();
        selectionBox.setVisible(true);
        selectionBox.setX(dragStartX);
        selectionBox.setY(dragStartY);
        selectionBox.setWidth(0);
        selectionBox.setHeight(0);
    }

    /**
     * Handles mouse drag on the preview pane.
     */
    public void handleMouseDragged(MouseEvent e) {
        if (previewImageView.getImage() == null) return;

        double currentX = Math.max(2, Math.min(e.getX(), previewImageView.getBoundsInLocal().getWidth() + 2));
        double currentY = Math.max(2, Math.min(e.getY(), previewImageView.getBoundsInLocal().getHeight() + 2));

        double minX = Math.min(dragStartX, currentX);
        double minY = Math.min(dragStartY, currentY);
        double width = Math.abs(currentX - dragStartX);
        double height = Math.abs(currentY - dragStartY);

        selectionBox.setX(minX);
        selectionBox.setY(minY);
        selectionBox.setWidth(width);
        selectionBox.setHeight(height);
    }

    /**
     * Handles mouse release on the preview pane.
     * Computes the actual image coordinates and invokes the applier callback.
     *
     * @param e            the mouse event
     * @param selectedNode the currently selected node (may be null)
     * @param applier      callback to apply computed coordinates to the node/UI
     */
    public void handleMouseReleased(MouseEvent e, AutomationStep selectedNode, CoordinateApplier applier) {
        if (selectedNode == null || previewImageView.getImage() == null) return;

        double imgW = previewImageView.getImage().getWidth();
        double imgH = previewImageView.getImage().getHeight();
        double scaleX = imgW / previewImageView.getBoundsInLocal().getWidth();
        double scaleY = imgH / previewImageView.getBoundsInLocal().getHeight();

        double currentX = Math.max(2, Math.min(e.getX(), previewImageView.getBoundsInLocal().getWidth() + 2));
        double currentY = Math.max(2, Math.min(e.getY(), previewImageView.getBoundsInLocal().getHeight() + 2));

        FlowStepKind type = selectedNode.getType();

        if (type == FlowStepKind.TAP_POINT || type == FlowStepKind.OCR_READ) {
            int tlX, tlY, brX, brY;
            if (selectionBox.getWidth() <= 1 && selectionBox.getHeight() <= 1) {
                // Simple click — point tap
                int px = (int) ((currentX - 2.0) * scaleX);
                int py = (int) ((currentY - 2.0) * scaleY);
                tlX = px; tlY = py; brX = px; brY = py;
            } else {
                // Region selection
                tlX = (int) ((selectionBox.getX() - 2.0) * scaleX);
                tlY = (int) ((selectionBox.getY() - 2.0) * scaleY);
                brX = (int) (((selectionBox.getX() - 2.0) + selectionBox.getWidth()) * scaleX);
                brY = (int) (((selectionBox.getY() - 2.0) + selectionBox.getHeight()) * scaleY);
            }
            applier.applyCoordinates(selectedNode, tlX, tlY, brX, brY);

        } else if (type == FlowStepKind.SWIPE) {
            int startX = (int) ((dragStartX - 2.0) * scaleX);
            int startY = (int) ((dragStartY - 2.0) * scaleY);
            int endX   = (int) ((currentX - 2.0) * scaleX);
            int endY   = (int) ((currentY - 2.0) * scaleY);
            applier.applyCoordinates(selectedNode, startX, startY, endX, endY);

        } else if (type == FlowStepKind.TEMPLATE_SEARCH) {
            if (selectionBox.getWidth() > 3 && selectionBox.getHeight() > 3) {
                int tlX = (int) ((selectionBox.getX() - 2.0) * scaleX);
                int tlY = (int) ((selectionBox.getY() - 2.0) * scaleY);
                int brX = (int) (((selectionBox.getX() - 2.0) + selectionBox.getWidth()) * scaleX);
                int brY = (int) (((selectionBox.getY() - 2.0) + selectionBox.getHeight()) * scaleY);
                applier.applyCoordinates(selectedNode, tlX, tlY, brX, brY);
            }
        }
    }

    /**
     * Shows the crosshair when mouse enters the preview.
     */
    public void handleMouseEntered(MouseEvent e) {
        if (hasPreviewImage) crosshairPane.setVisible(true);
    }

    /**
     * Hides the crosshair when mouse exits the preview.
     */
    public void handleMouseExited(MouseEvent e) {
        if (crosshairPane != null) crosshairPane.setVisible(false);
    }

    /**
     * Updates crosshair position and coordinate label on mouse move.
     */
    public void handleMouseMoved(MouseEvent e) {
        if (!hasPreviewImage || previewImageView.getImage() == null) return;

        double relX = e.getX() - 2.0;
        double relY = e.getY() - 2.0;

        double viewWidth  = previewImageView.getBoundsInLocal().getWidth();
        double viewHeight = previewImageView.getBoundsInLocal().getHeight();

        if (relX < 0 || relX > viewWidth || relY < 0 || relY > viewHeight) {
            crosshairPane.setVisible(false);
            return;
        }

        double imgW = previewImageView.getImage().getWidth();
        double imgH = previewImageView.getImage().getHeight();
        double scaleX = imgW / viewWidth;
        double scaleY = imgH / viewHeight;
        int realX = (int) (relX * scaleX);
        int realY = (int) (relY * scaleY);

        if (!crosshairPane.isVisible()) {
            crosshairPane.setVisible(true);
            crossX.setVisible(true);
            crossY.setVisible(true);
            coordsLabel.setVisible(true);
        }

        crossX.setStartY(2.0);
        crossX.setEndY(viewHeight + 2.0);
        crossX.setStartX(e.getX());
        crossX.setEndX(e.getX());

        crossY.setStartX(2.0);
        crossY.setEndX(viewWidth + 2.0);
        crossY.setStartY(e.getY());
        crossY.setEndY(e.getY());

        coordsLabel.setText(realX + ", " + realY);

        // Prevent label from clipping out of bounds
        double labelX = e.getX() + 10;
        double labelY = e.getY() + 10;
        if (labelX + 80 > viewWidth + 2.0) {
            labelX = e.getX() - 90;
        }
        if (labelY + 25 > viewHeight + 2.0) {
            labelY = e.getY() - 35;
        }
        coordsLabel.setLayoutX(labelX);
        coordsLabel.setLayoutY(labelY);
    }
}
