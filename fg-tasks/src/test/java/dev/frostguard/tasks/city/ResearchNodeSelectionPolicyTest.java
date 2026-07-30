package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.city.ResearchNodeSelectionPolicy.ResearchNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchNodeSelectionPolicyTest {

    @Test
    void keepsRowsTopFirstButChoosesLowestProgressWithinTheRow() {
        var nodes = List.of(
                node(2, 3, 360, 400),
                node(1, 3, 140, 650),
                node(0, 3, 580, 650),
                node(0, 3, 360, 900));

        var rows = ResearchNodeSelectionPolicy.rows(nodes);

        assertEquals(3, rows.size());
        assertEquals(2, rows.get(0).candidates().get(0).currentLevel());
        assertEquals(0, rows.get(1).candidates().get(0).currentLevel());
        assertEquals(new PointData(580, 650), rows.get(1).candidates().get(0).badgePoint());
        assertEquals(0, rows.get(2).candidates().get(0).currentLevel());
    }

    @Test
    void groupsSlightlyMisalignedBadgesIntoOneRow() {
        var rows = ResearchNodeSelectionPolicy.rows(List.of(
                node(1, 3, 140, 600),
                node(0, 3, 360, 620),
                node(2, 3, 580, 590)));

        assertEquals(1, rows.size());
        assertEquals(List.of(0, 1, 2), rows.get(0).candidates().stream()
                .map(ResearchNode::currentLevel)
                .toList());
    }

    @Test
    void supportsLaterBattleDenominatorsWithoutChangingSelectionRules() {
        var rows = ResearchNodeSelectionPolicy.rows(List.of(
                node(3, 6, 140, 500),
                node(1, 5, 360, 500),
                node(2, 4, 580, 500)));

        assertEquals(List.of(1, 2, 3), rows.get(0).candidates().stream()
                .map(ResearchNode::currentLevel)
                .toList());
    }

    private static ResearchNode node(int current, int maximum, int x, int y) {
        return new ResearchNode(current, maximum, new PointData(x, y));
    }
}
