package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.PointData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ResearchNodeSelectionPolicy {

    static final int ROW_Y_TOLERANCE = 45;

    private ResearchNodeSelectionPolicy() {}

    static List<ResearchRow> rows(List<ResearchNode> nodes) {
        List<ResearchNode> ordered = nodes.stream()
                .sorted(Comparator.comparingInt(node -> node.badgePoint().getY()))
                .toList();
        List<List<ResearchNode>> grouped = new ArrayList<>();
        for (ResearchNode node : ordered) {
            List<ResearchNode> row = grouped.isEmpty() ? null : grouped.get(grouped.size() - 1);
            if (row == null || Math.abs(rowCenterY(row) - node.badgePoint().getY()) > ROW_Y_TOLERANCE) {
                row = new ArrayList<>();
                grouped.add(row);
            }
            row.add(node);
        }
        return grouped.stream()
                .map(row -> new ResearchRow(row.stream()
                        .sorted(Comparator.comparingInt(ResearchNode::currentLevel)
                                .thenComparingInt(node -> node.badgePoint().getX()))
                        .toList()))
                .toList();
    }

    private static int rowCenterY(List<ResearchNode> row) {
        return (int) Math.round(row.stream()
                .mapToInt(node -> node.badgePoint().getY())
                .average()
                .orElse(0));
    }

    record ResearchNode(int currentLevel, int maximumLevel, PointData badgePoint) {

        ResearchNode {
            if (currentLevel < 0 || maximumLevel < 1 || currentLevel >= maximumLevel) {
                throw new IllegalArgumentException("Research progress must be incomplete and non-negative.");
            }
        }

        PointData tapTarget() {
            return new PointData(badgePoint.getX() - 3, badgePoint.getY() - 54);
        }
    }

    record ResearchRow(List<ResearchNode> candidates) {

        int minimumLevel() {
            return candidates.get(0).currentLevel();
        }
    }
}
