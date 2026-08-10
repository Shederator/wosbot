package dev.frostguard.engine.service;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationStep;

/**
 * Centralised branching-logic evaluator for conditional flow nodes
 * (OCR Read, Template Search).
 *
 * <p>Before this class existed, the same condition evaluation was
 * duplicated in three places (code generator, DAG executor, and card
 * refresh). Any new condition type or change in semantics must be
 * applied here only.</p>
 */
public final class BranchEvaluator {

    private BranchEvaluator() { /* utility class */ }

    // ======================================================================
    // OCR
    // ======================================================================

    /**
     * Evaluates an OCR condition against the actual OCR result.
     *
     * @param ocrResult the text returned by the OCR engine (never null)
     * @param condition one of CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, NOT_CONTAINS
     * @param expected  the expected value to match against
     * @return {@code true} when the condition is satisfied ("Yes" branch)
     */
    public static boolean evaluateOcrCondition(String ocrResult, String condition, String expected) {
        if (ocrResult == null) ocrResult = "";
        if (expected == null) expected = "";
        if (condition == null) condition = "CONTAINS";

        String resultLower = ocrResult.toLowerCase();
        String expectedLower = expected.toLowerCase();

        return switch (condition) {
            case "EQUALS"       -> ocrResult.equalsIgnoreCase(expected);
            case "STARTS_WITH"  -> resultLower.startsWith(expectedLower);
            case "ENDS_WITH"    -> resultLower.endsWith(expectedLower);
            case "NOT_CONTAINS" -> !resultLower.contains(expectedLower);
            default             -> resultLower.contains(expectedLower); // CONTAINS
        };
    }

    // ======================================================================
    // Template Search
    // ======================================================================

    /**
     * Evaluates a Template Search node's last execution result.
     *
     * @param node the template search node (must have {@code __lastSearchFound} param set after execution)
     * @return {@code true} when the template was found ("Found" branch)
     */
    public static boolean evaluateTemplateResult(AutomationStep node) {
        return "true".equals(node.getParam("__lastSearchFound"));
    }

    // ======================================================================
    // Unified next-node resolver
    // ======================================================================

    /**
     * Determines the next node ID to execute after the given node,
     * taking branching into profile for OCR and Template Search nodes.
     *
     * @param node the node that was just executed
     * @return the ID of the next node, or {@code -1} to end execution
     */
    public static int resolveNextNode(AutomationStep node) {
        if (node.getType() == FlowStepKind.OCR_READ) {
            String result    = node.getLastOcrResult() != null ? node.getLastOcrResult() : "";
            String expected  = node.getParam("expectedValue") != null ? node.getParam("expectedValue") : "";
            String condition = node.getParam("condition") != null ? node.getParam("condition") : "CONTAINS";

            boolean matches = evaluateOcrCondition(result, condition, expected);
            return matches ? node.getNextNodeId() : node.getNextNodeFalseId();
        }

        if (node.getType() == FlowStepKind.TEMPLATE_SEARCH) {
            boolean found = evaluateTemplateResult(node);
            return found ? node.getNextNodeId() : node.getNextNodeFalseId();
        }

        // Non-branching nodes: follow the single output
        return node.getNextNodeId();
    }
}
