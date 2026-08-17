package dev.frostguard.app.shared;

import dev.frostguard.api.domain.PriorityItemData;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class PriorityListView extends HBox {

    private static final DataFormat PRIORITY_DATA_FORMAT = new DataFormat("application/x-frostguard-priority-index");
    private static final double EDGE_SCROLL_ZONE = 72.0;
    private static final double MAX_SCROLL_SPEED = 0.014;
    private static final double MIN_SCROLL_SPEED = 0.002;
    private static final double ROW_HEIGHT = 36.0;

    private final ObservableList<PriorityItemData> items = FXCollections.observableArrayList();
    private final ListView<PriorityItemData> listView = new ListView<>(items);
    private final AnimationTimer autoScrollTimer;

    private Runnable onChangeCallback;
    private double scrollDirection;
    private double scrollSpeed;

    public PriorityListView() {
        autoScrollTimer = createAutoScrollTimer();
        configureList();
        getChildren().addAll(listView, createMoveControls());
        StyleConstants.ensure(listView, StyleConstants.PRIORITY_LIST_VIEW);
    }

    public void setItems(List<PriorityItemData> priorities) {
        items.setAll(priorities == null ? List.of() : priorities);
        sortByPriority();
    }

    public List<PriorityItemData> getItems() {
        return new ArrayList<>(items);
    }

    public void setOnChangeCallback(Runnable callback) {
        onChangeCallback = callback;
    }

    public String toConfigString() {
        return items.stream()
            .map(PriorityItemData::toConfigString)
            .collect(Collectors.joining("|"));
    }

    public void fromConfigString(String configString) {
        items.clear();
        if (configString == null || configString.isBlank()) {
            return;
        }

        for (String token : configString.split("\\|")) {
            PriorityItemData parsed = PriorityItemData.fromConfigString(token);
            if (parsed != null) {
                items.add(parsed);
            }
        }
        sortByPriority();
    }

    private void configureList() {
        listView.setCellFactory(ignored -> new PriorityItemCell());
        listView.setFixedCellSize(ROW_HEIGHT);
        listView.setPrefHeight(220);
        HBox.setHgrow(listView, Priority.ALWAYS);
    }

    private VBox createMoveControls() {
        VBox controls = new VBox(6, createMoveButton("Up", -1), createMoveButton("Down", 1));
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(0, 0, 0, 8));
        return controls;
    }

    private Button createMoveButton(String label, int direction) {
        Button button = new Button(label);
        button.setMinSize(54, 32);
        button.setPrefSize(54, 32);
        button.setOnAction(event -> moveSelectedItem(direction));
        return button;
    }

    private void moveSelectedItem(int direction) {
        int selectedIndex = listView.getSelectionModel().getSelectedIndex();
        int targetIndex = selectedIndex + direction;
        if (selectedIndex < 0 || targetIndex < 0 || targetIndex >= items.size()) {
            return;
        }

        PriorityItemData item = items.remove(selectedIndex);
        items.add(targetIndex, item);
        commitOrderChange(targetIndex);
    }

    private void commitOrderChange(int selectedIndex) {
        resequencePriorities();
        listView.getSelectionModel().select(selectedIndex);
        notifyChanged();
    }

    private void resequencePriorities() {
        for (int index = 0; index < items.size(); index++) {
            items.get(index).setPriority(index + 1);
        }
    }

    private void sortByPriority() {
        FXCollections.sort(items, Comparator.comparingInt(PriorityItemData::getPriority));
    }

    private void notifyChanged() {
        if (onChangeCallback != null) {
            onChangeCallback.run();
        }
    }

    private AnimationTimer createAutoScrollTimer() {
        return new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (scrollDirection == 0 || scrollSpeed <= 0) {
                    return;
                }

                ScrollBar scrollBar = findVerticalScrollBar();
                if (scrollBar == null || !scrollBar.isVisible()) {
                    return;
                }

                double nextValue = scrollBar.getValue() + (scrollDirection * scrollSpeed);
                scrollBar.setValue(Math.max(scrollBar.getMin(), Math.min(scrollBar.getMax(), nextValue)));
            }
        };
    }

    private ScrollBar findVerticalScrollBar() {
        for (var node : listView.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar scrollBar && scrollBar.getOrientation() == Orientation.VERTICAL) {
                return scrollBar;
            }
        }
        return null;
    }

    private void updateAutoScroll(double sceneY) {
        double yWithinList = sceneY - listView.localToScene(0, 0).getY();
        double listHeight = listView.getHeight();

        if (yWithinList < EDGE_SCROLL_ZONE) {
            scrollDirection = -1;
            scrollSpeed = speedFromEdge(yWithinList);
        } else if (yWithinList > listHeight - EDGE_SCROLL_ZONE) {
            scrollDirection = 1;
            scrollSpeed = speedFromEdge(listHeight - yWithinList);
        } else {
            stopAutoScroll();
        }
    }

    private double speedFromEdge(double distanceFromEdge) {
        double proximity = 1.0 - Math.max(0, Math.min(distanceFromEdge / EDGE_SCROLL_ZONE, 1.0));
        return MIN_SCROLL_SPEED + proximity * (MAX_SCROLL_SPEED - MIN_SCROLL_SPEED);
    }

    private void stopAutoScroll() {
        scrollDirection = 0;
        scrollSpeed = 0;
    }

    private final class PriorityItemCell extends ListCell<PriorityItemData> {

        private final HBox row = new HBox(8);
        private final Label dragHandle = new Label("::");
        private final CheckBox enabledCheckBox = new CheckBox();
        private final Label priorityLabel = new Label();
        private final Label nameLabel = new Label();

        private PriorityItemCell() {
            configureRow();
            configureDragAndDrop();
            StyleConstants.ensure(this, StyleConstants.PRIORITY_LIST_CELL);
        }

        @Override
        protected void updateItem(PriorityItemData item, boolean empty) {
            super.updateItem(item, empty);

            clearDropHints();
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            enabledCheckBox.setOnAction(null);
            enabledCheckBox.setSelected(item.isEnabled());
            priorityLabel.setText(item.isEnabled() ? visiblePriorityFor(item) + "." : "Off");
            nameLabel.setText(item.getName());
            enabledCheckBox.setOnAction(event -> toggleItem(item));

            applyEnabledState(item.isEnabled());
            setGraphic(row);
        }

        private void configureRow() {
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            dragHandle.setMinWidth(18);
            priorityLabel.setMinWidth(32);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            row.getChildren().addAll(dragHandle, enabledCheckBox, priorityLabel, nameLabel);
        }

        private void configureDragAndDrop() {
            setOnDragDetected(event -> {
                if (getItem() == null) {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(PRIORITY_DATA_FORMAT, getIndex());
                dragboard.setContent(content);
                autoScrollTimer.start();
                event.consume();
            });

            setOnDragOver(event -> {
                if (event.getGestureSource() == this || !event.getDragboard().hasContent(PRIORITY_DATA_FORMAT)) {
                    event.consume();
                    return;
                }

                event.acceptTransferModes(TransferMode.MOVE);
                updateAutoScroll(event.getSceneY());
                markDropHint(event.getY());
                event.consume();
            });

            setOnDragExited(event -> {
                clearDropHints();
                event.consume();
            });

            setOnDragDropped(event -> {
                boolean success = completeDrop(event.getDragboard(), event.getY());
                clearDropHints();
                stopAutoScroll();
                autoScrollTimer.stop();
                event.setDropCompleted(success);
                event.consume();
            });

            setOnDragDone(event -> {
                clearDropHints();
                stopAutoScroll();
                autoScrollTimer.stop();
                event.consume();
            });
        }

        private boolean completeDrop(Dragboard dragboard, double mouseY) {
            if (getItem() == null || !dragboard.hasContent(PRIORITY_DATA_FORMAT)) {
                return false;
            }

            int draggedIndex = (Integer) dragboard.getContent(PRIORITY_DATA_FORMAT);
            if (draggedIndex < 0 || draggedIndex >= items.size()) {
                return false;
            }

            boolean dropBefore = mouseY < getHeight() / 2;
            int currentIndex = getIndex();
            PriorityItemData draggedItem = items.remove(draggedIndex);

            int targetIndex = currentIndex;
            if (draggedIndex < currentIndex && dropBefore) {
                targetIndex = currentIndex - 1;
            } else if (draggedIndex > currentIndex && !dropBefore) {
                targetIndex = currentIndex + 1;
            }

            targetIndex = Math.max(0, Math.min(targetIndex, items.size()));
            items.add(targetIndex, draggedItem);
            commitOrderChange(targetIndex);
            return true;
        }

        private void markDropHint(double mouseY) {
            clearDropHints();
            getStyleClass().add(
                mouseY < getHeight() / 2
                    ? StyleConstants.PRIORITY_LIST_CELL_DRAG_OVER_TOP
                    : StyleConstants.PRIORITY_LIST_CELL_DRAG_OVER_BOTTOM
            );
        }

        private void clearDropHints() {
            getStyleClass().removeAll(
                StyleConstants.PRIORITY_LIST_CELL_DRAG_OVER_TOP,
                StyleConstants.PRIORITY_LIST_CELL_DRAG_OVER_BOTTOM
            );
        }

        private void toggleItem(PriorityItemData item) {
            item.setEnabled(enabledCheckBox.isSelected());
            applyEnabledState(item.isEnabled());
            listView.refresh();
            notifyChanged();
        }

        private int visiblePriorityFor(PriorityItemData currentItem) {
            int visiblePriority = 0;
            for (PriorityItemData item : items) {
                if (item.isEnabled()) {
                    visiblePriority++;
                }
                if (item == currentItem) {
                    break;
                }
            }
            return Math.max(1, visiblePriority);
        }

        private void applyEnabledState(boolean enabled) {
            StyleConstants.replace(dragHandle, StyleConstants.PRIORITY_DRAG_INDICATOR, StyleConstants.PRIORITY_DRAG_INDICATOR_DISABLED, enabled);
            StyleConstants.replace(priorityLabel, StyleConstants.PRIORITY_LABEL, StyleConstants.PRIORITY_LABEL_DISABLED, enabled);
            StyleConstants.replace(nameLabel, StyleConstants.PRIORITY_NAME_LABEL, StyleConstants.PRIORITY_NAME_LABEL_DISABLED, enabled);

            getStyleClass().remove(StyleConstants.PRIORITY_LIST_CELL_DISABLED);
            if (!enabled) {
                getStyleClass().add(StyleConstants.PRIORITY_LIST_CELL_DISABLED);
            }
        }
    }
}
