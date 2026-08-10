package dev.frostguard.engine.service;

import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;

import java.util.*;

/**
 * Utility that analyses a {@link AutomationBlueprint} graph to detect
 * <em>back-edges</em> — connections where the target node has already been
 * visited on the current DFS path, which by definition form loops/cycles.
 *
 * <p>Used by:
 * <ul>
 *   <li>The canvas UI to render loop wires distinctly</li>
 *   <li>The code generator to emit loop-guard counters</li>
 *   <li>The ExecuteAll runtime to enforce loop limits</li>
 * </ul>
 */
public final class LoopDetector {

    private LoopDetector() { /* utility class */ }

    // ======================================================================
    // Data Structures
    // ======================================================================

    /**
     * Represents a back-edge (loop) in the flow graph.
     *
     * @param sourceId      the node whose output creates the loop
     * @param targetId      the earlier node being looped back to
     * @param isFalseBranch true if the back-edge comes from the "No/Not Found" output
     */
    public record BackEdge(int sourceId, int targetId, boolean isFalseBranch) {
        /** Stable key for maps and loop-counter variable names. */
        public String key() {
            return sourceId + (isFalseBranch ? "_F_" : "_T_") + targetId;
        }
    }

    // ======================================================================
    // Public API
    // ======================================================================

    /**
     * Detects all back-edges in the flow graph using iterative DFS
     * starting from the first node in the definition.
     *
     * @param def the task flow definition to analyse
     * @return a list of detected back-edges (empty if none)
     */
    public static List<BackEdge> detectBackEdges(AutomationBlueprint def) {
        if (def == null || def.getNodes().isEmpty()) return Collections.emptyList();

        // Build id → node map
        Map<Integer, AutomationStep> nodeMap = new LinkedHashMap<>();
        for (AutomationStep n : def.getNodes()) {
            nodeMap.put(n.getId(), n);
        }

        List<BackEdge> backEdges = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Set<Integer> onStack = new HashSet<>();      // nodes on the current DFS path
        Deque<int[]> stack = new ArrayDeque<>();      // [nodeId, phase] — phase 0=enter, 1=exit

        int startId = def.getNodes().get(0).getId();
        stack.push(new int[]{startId, 0});

        while (!stack.isEmpty()) {
            int[] frame = stack.pop();
            int nodeId = frame[0];
            int phase  = frame[1];

            if (phase == 1) {
                // Exiting this node — remove from path
                onStack.remove(nodeId);
                continue;
            }

            if (visited.contains(nodeId)) continue;
            visited.add(nodeId);
            onStack.add(nodeId);

            // Push exit marker so we know when to remove from onStack
            stack.push(new int[]{nodeId, 1});

            AutomationStep node = nodeMap.get(nodeId);
            if (node == null) continue;

            // Check both outgoing edges
            checkEdge(node.getNextNodeId(), nodeId, false, nodeMap, visited, onStack, stack, backEdges);
            checkEdge(node.getNextNodeFalseId(), nodeId, true, nodeMap, visited, onStack, stack, backEdges);
        }

        return backEdges;
    }

    /**
     * Checks whether connecting {@code sourceId → targetId} would create a
     * back-edge (cycle) in the current graph.  Useful for providing
     * immediate UI feedback when the user drags a wire.
     *
     * @param def      the current flow definition
     * @param sourceId the source node of the proposed connection
     * @param targetId the target node of the proposed connection
     * @return true if this connection creates a loop
     */
    public static boolean isBackEdge(AutomationBlueprint def, int sourceId, int targetId) {
        if (def == null || def.getNodes().isEmpty()) return false;

        // A back-edge exists if targetId can reach sourceId via forward traversal
        // (i.e. targetId is an ancestor of sourceId) — OR more simply, if
        // targetId appears on any path from start to sourceId.
        // The simplest check: can we reach sourceId starting from targetId?
        Map<Integer, AutomationStep> nodeMap = new LinkedHashMap<>();
        for (AutomationStep n : def.getNodes()) {
            nodeMap.put(n.getId(), n);
        }

        Set<Integer> reachable = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(targetId);

        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur <= 0 || reachable.contains(cur)) continue;
            reachable.add(cur);
            if (cur == sourceId) return true;

            AutomationStep node = nodeMap.get(cur);
            if (node == null) continue;
            if (node.getNextNodeId() > 0) queue.add(node.getNextNodeId());
            if (node.getNextNodeFalseId() > 0) queue.add(node.getNextNodeFalseId());
        }

        return false;
    }

    /**
     * Builds a set of back-edge keys for O(1) lookup.
     */
    public static Set<String> backEdgeKeySet(AutomationBlueprint def) {
        Set<String> keys = new HashSet<>();
        for (BackEdge be : detectBackEdges(def)) {
            keys.add(be.key());
        }
        return keys;
    }

    // ======================================================================
    // Internal
    // ======================================================================

    private static void checkEdge(int targetId, int sourceId, boolean isFalse,
                                   Map<Integer, AutomationStep> nodeMap,
                                   Set<Integer> visited, Set<Integer> onStack,
                                   Deque<int[]> stack, List<BackEdge> backEdges) {
        if (targetId <= 0) return;

        if (onStack.contains(targetId)) {
            // Target is on the current DFS path → back-edge (cycle)
            backEdges.add(new BackEdge(sourceId, targetId, isFalse));
        } else if (!visited.contains(targetId)) {
            stack.push(new int[]{targetId, 0});
        }
    }
}
