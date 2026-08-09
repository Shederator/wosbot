package dev.frostguard.engine.service;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Transforms an {@link AutomationBlueprint} DAG into a compilable Java
 * source file that extends {@code DelayedTask} and runs as a state-machine.
 *
 * <p>The generated class supports branching nodes (OCR Read, Template
 * Search) and loop guards for back-edges detected by {@link LoopDetector}.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 *   String java = new TaskCodeGenerator()
 *       .generate(blueprint, "MyTask", "My Cool Task");
 * }</pre>
 */
public class TaskCodeGenerator {

    private static final String CUSTOM_TPL_SCHEME = "file://";

    // ── Helpers ───────────────────────────────────────────────────────

    /** Returns whitespace for the requested nesting depth (1 = 4 spaces). */
    private static String indent(int depth) {
        return "    ".repeat(depth);
    }

    private LoopDetector.BackEdge pickEdge(List<LoopDetector.BackEdge> edges, boolean falseBranch) {
        return edges.stream()
                .filter(e -> e.isFalseBranch() == falseBranch)
                .findFirst().orElse(null);
    }

    // ── Public entry point ────────────────────────────────────────────

    /**
     * Produces the full Java source for a compiled task class.
     *
     * @param blueprint the node DAG
     * @param className valid Java identifier for the class
     * @param taskName  human-readable label for log messages
     * @return compilable Java source string
     */
    public String generate(AutomationBlueprint blueprint, String className, String taskName) {
        StringBuilder out = new StringBuilder(4096);

        writeImports(out);
        writeClassOpening(out, className);
        writeConstructorAndKey(out, className);
        writeStartLocation(out, blueprint);
        writeStateMachine(out, blueprint, taskName);
        out.append("}\n");

        return out.toString();
    }

    // ── Top-level sections ────────────────────────────────────────────

    private void writeImports(StringBuilder out) {
        out.append("package dev.frostguard.engine.listener.task.impl;\n\n");
        out.append("import dev.frostguard.api.configs.TpDailyTaskEnum;\n");
        out.append("import dev.frostguard.api.domain.AreaData;\n");
        out.append("import dev.frostguard.api.domain.PointData;\n");
        out.append("import dev.frostguard.api.domain.AccountDescriptor;\n");
        out.append("import dev.frostguard.api.configs.TemplatesEnum;\n");
        out.append("import dev.frostguard.api.domain.ImageSearchResultData;\n");
        out.append("import dev.frostguard.engine.helper.TemplateSearchHelper;\n");
        out.append("import dev.frostguard.engine.schedule.DelayedTask;\n");
        out.append("import dev.frostguard.engine.schedule.LaunchPoint;\n");
        out.append("import java.time.LocalDateTime;\n\n");
    }

    private void writeClassOpening(StringBuilder out, String className) {
        out.append("public class ").append(className).append(" extends DelayedTask {\n\n");
    }

    private void writeConstructorAndKey(StringBuilder out, String className) {
        String i1 = indent(1), i2 = indent(2);
        out.append(i1).append("public ").append(className)
           .append("(AccountDescriptor profile, TpDailyTaskEnum tpTask) {\n");
        out.append(i2).append("super(profile, tpTask);\n");
        out.append(i1).append("}\n\n");

        out.append(i1).append("@Override\n");
        out.append(i1).append("protected Object getDistinctKey() {\n");
        out.append(i2).append("return \"").append(className).append("\";\n");
        out.append(i1).append("}\n\n");
    }

    private void writeStartLocation(StringBuilder out, AutomationBlueprint blueprint) {
        String loc = blueprint.getStartLocation();
        String mapped = switch (loc != null ? loc : "") {
            case "HOME"  -> "HOME";
            case "WORLD" -> "WORLD";
            default      -> "ANY";
        };
        String i1 = indent(1), i2 = indent(2);
        out.append(i1).append("@Override\n");
        out.append(i1).append("protected LaunchPoint getRequiredStartLocation() {\n");
        out.append(i2).append("return LaunchPoint.").append(mapped).append(";\n");
        out.append(i1).append("}\n\n");
    }

    // ── State machine ─────────────────────────────────────────────────

    private void writeStateMachine(StringBuilder out, AutomationBlueprint blueprint, String taskName) {
        List<LoopDetector.BackEdge> backEdges = LoopDetector.detectBackEdges(blueprint);
        Map<Integer, AutomationStep> nodeIndex = new LinkedHashMap<>();
        blueprint.getNodes().forEach(n -> nodeIndex.put(n.getId(), n));

        Map<Integer, List<LoopDetector.BackEdge>> edgesBySource = backEdges.stream()
                .collect(Collectors.groupingBy(LoopDetector.BackEdge::sourceId));

        String i1 = indent(1), i2 = indent(2), i3 = indent(3);

        out.append(i1).append("@Override\n");
        out.append(i1).append("protected void execute() {\n");
        out.append(i2).append("logInfo(\"Starting task: '").append(taskName).append("'\");\n");

        int entryId = blueprint.getNodes().isEmpty() ? -1 : blueprint.getNodes().get(0).getId();

        // loop counters
        for (LoopDetector.BackEdge be : backEdges) {
            out.append(i2).append("int __loopCount_").append(be.key()).append(" = 0;\n");
        }

        out.append(i2).append("int __state = ").append(entryId < 0 ? "-1" : String.valueOf(entryId)).append(";\n");
        out.append(i2).append("while (__state != -1) {\n");
        out.append(i3).append("checkPreemption();\n");
        out.append(i3).append("switch (__state) {\n");

        for (AutomationStep node : blueprint.getNodes()) {
            writeNodeCase(out, node,
                    edgesBySource.getOrDefault(node.getId(), Collections.emptyList()),
                    nodeIndex);
        }

        out.append(indent(4)).append("default: __state = -1; break;\n");
        out.append(i3).append("}\n");
        out.append(i2).append("}\n");

        writeRescheduleBlock(out);

        out.append(i2).append("logInfo(\"Generated task complete.\");\n");
        out.append(i1).append("}\n");
    }

    private void writeRescheduleBlock(StringBuilder out) {
        String i2 = indent(2), i3 = indent(3);
        out.append(i2).append("int __interval = getRepeatIntervalMinutes();\n");
        out.append(i2).append("if (__interval > 0) {\n");
        out.append(i3).append("reschedule(LocalDateTime.now().plusMinutes(__interval));\n");
        out.append(i3).append("logInfo(\"Task rescheduled in \" + __interval + \" minutes.\");\n");
        out.append(i2).append("} else {\n");
        out.append(i3).append("setRecurring(false);\n");
        out.append(i3).append("logInfo(\"Task interval is 0, disabling recurrence.\");\n");
        out.append(i2).append("}\n");
    }

    // ── Node case dispatch ────────────────────────────────────────────

    private void writeNodeCase(StringBuilder out, AutomationStep node,
                               List<LoopDetector.BackEdge> nodeEdges,
                               Map<Integer, AutomationStep> nodeIndex) {
        String i4 = indent(4), i5 = indent(5);
        out.append(i4).append("case ").append(node.getId()).append(": {\n");
        out.append(i5).append("// ").append(getNodeComment(node)).append("\n");

        switch (node.getType()) {
            case TAP_POINT       -> writeTap(out, node);
            case WAIT            -> writeWait(out, node);
            case SWIPE           -> writeSwipe(out, node);
            case BACK_BUTTON     -> writeBack(out, node);
            case OCR_READ        -> writeOcr(out, node, nodeEdges);
            case TEMPLATE_SEARCH -> writeTemplateSearch(out, node, nodeEdges);
            default              -> out.append(i5).append("// Unrecognised action\n");
        }

        if (node.getType() != FlowStepKind.OCR_READ
                && node.getType() != FlowStepKind.TEMPLATE_SEARCH) {
            int nextId = node.getNextNodeId();
            LoopDetector.BackEdge edge = pickEdge(nodeEdges, false);
            if (edge != null && nextId > 0) {
                writeLoopGuard(out, node, edge, nextId, -1, 5);
            } else {
                out.append(i5).append("__state = ").append(nextId > 0 ? nextId : -1).append(";\n");
            }
        }

        out.append(i5).append("break;\n");
        out.append(i4).append("}\n");
    }

    private String getNodeComment(AutomationStep node) {
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isBlank()) {
            return node.getType().getDisplayName();
        }
        return nodeName.replace('\r', ' ').replace('\n', ' ');
    }

    // ── Action emitters ───────────────────────────────────────────────

    private void writeTap(StringBuilder out, AutomationStep node) {
        int tlX = node.getParamAsInt("tlX", 0), tlY = node.getParamAsInt("tlY", 0);
        int brX = node.getParamAsInt("brX", 0), brY = node.getParamAsInt("brY", 0);
        String i5 = indent(5);

        // Emit shared-input-layer calls: bounded jitter for exact points,
        // in-area randomization for rectangles.
        if (tlX == brX && tlY == brY) {
            out.append(i5).append("tapNear(new PointData(")
               .append(tlX).append(", ").append(tlY).append("), 3);\n");
        } else {
            out.append(i5).append("tapInside(AreaData.of(")
               .append(tlX).append(", ").append(tlY).append(", ")
               .append(brX).append(", ").append(brY).append("));\n");
        }
    }

    private void writeWait(StringBuilder out, AutomationStep node) {
        out.append(indent(5)).append("sleepTask(")
           .append(node.getParamAsInt("durationMs", 1000)).append("L);\n");
    }

    private void writeSwipe(StringBuilder out, AutomationStep node) {
        out.append(indent(5)).append("swipe(new PointData(")
           .append(node.getParamAsInt("startX", 0)).append(", ")
           .append(node.getParamAsInt("startY", 0)).append("), new PointData(")
           .append(node.getParamAsInt("endX", 0)).append(", ")
           .append(node.getParamAsInt("endY", 0)).append("));\n");
    }

    private void writeBack(StringBuilder out, AutomationStep node) {
        out.append(indent(5)).append("pressBack();\n");
    }

    // ── OCR branching ─────────────────────────────────────────────────

    private void writeOcr(StringBuilder out, AutomationStep node,
                          List<LoopDetector.BackEdge> nodeEdges) {
        String cond     = node.getParam("condition") != null ? node.getParam("condition") : "CONTAINS";
        String expected = node.getParam("expectedValue") != null ? node.getParam("expectedValue") : "";
        int tX = node.getParamAsInt("tlX", 0), tY = node.getParamAsInt("tlY", 0);
        int bX = node.getParamAsInt("brX", 100), bY = node.getParamAsInt("brY", 100);

        String var = "__ocrText_" + node.getId();
        String i5 = indent(5), i6 = indent(6);

        out.append(i5).append("String ").append(var).append(" = \"\";\n");
        out.append(i5).append("try {\n");
        out.append(i6).append(var)
           .append(" = emuManager.readText(EMULATOR_NUMBER, new PointData(")
           .append(tX).append(", ").append(tY).append("), new PointData(")
           .append(bX).append(", ").append(bY).append("));\n");
        out.append(i5).append("} catch (Exception __ocrEx) {\n");
        out.append(i6).append("logInfo(\"OCR failed: \" + __ocrEx.getMessage());\n");
        out.append(i5).append("}\n");

        int trueNext  = node.getNextNodeId();
        int falseNext = node.getNextNodeFalseId();
        String expr   = ocrConditionExpr(var, cond, expected);

        LoopDetector.BackEdge trueEdge  = pickEdge(nodeEdges, false);
        LoopDetector.BackEdge falseEdge = pickEdge(nodeEdges, true);

        out.append(i5).append("if (").append(expr).append(") {\n");
        writeBranchTransition(out, node, trueEdge, trueNext, falseNext);
        out.append(i5).append("} else {\n");
        writeBranchTransition(out, node, falseEdge, falseNext, trueNext);
        out.append(i5).append("}\n");
    }

    private String ocrConditionExpr(String var, String cond, String expected) {
        return switch (cond) {
            case "EQUALS"       -> var + ".equalsIgnoreCase(\"" + expected + "\")";
            case "STARTS_WITH"  -> var + ".toLowerCase().startsWith(\"" + expected.toLowerCase() + "\")";
            case "ENDS_WITH"    -> var + ".toLowerCase().endsWith(\"" + expected.toLowerCase() + "\")";
            case "NOT_CONTAINS" -> "!" + var + ".toLowerCase().contains(\"" + expected.toLowerCase() + "\")";
            default             -> var + ".toLowerCase().contains(\"" + expected.toLowerCase() + "\")";
        };
    }

    // ── Template search branching ─────────────────────────────────────

    private void writeTemplateSearch(StringBuilder out, AutomationStep node,
                                     List<LoopDetector.BackEdge> nodeEdges) {
        String tmpl = node.getParam("templatePath");
        if (tmpl == null) tmpl = "GAME_HOME_FURNACE";

        boolean customFile  = tmpl.startsWith(CUSTOM_TPL_SCHEME);
        int     threshold   = node.getParamAsInt("threshold", 90);
        int     maxAttempts = node.getParamAsInt("maxAttempts", 1);
        int     delayMs     = node.getParamAsInt("delayMs", 300);
        boolean mono        = "true".equals(node.getParam("grayscale"));
        boolean tapOnHit    = "true".equals(node.getParam("tapIfFound"));
        int     offX        = node.getParamAsInt("offsetX", 0);
        int     offY        = node.getParamAsInt("offsetY", 0);

        String tlXs = node.getParam("tlX"), tlYs = node.getParam("tlY");
        String brXs = node.getParam("brX"), brYs = node.getParam("brY");
        boolean bounded = tlXs != null && tlYs != null && brXs != null && brYs != null;

        String var = "__tplResult_" + node.getId();
        String i5 = indent(5), i6 = indent(6);

        int trueNext  = node.getNextNodeId();
        int falseNext = node.getNextNodeFalseId();

        out.append(i5).append("ImageSearchResultData ").append(var).append(" = null;\n");
        out.append(i5).append("try {\n");

        if (customFile) {
            writeCustomSearch(out, tmpl, var, threshold, mono, bounded, tlXs, tlYs, brXs, brYs);
        } else {
            writeEnumSearch(out, tmpl, var, threshold, maxAttempts, delayMs, mono, bounded, tlXs, tlYs, brXs, brYs);
        }

        out.append(i5).append("} catch (Exception __tplEx) {\n");
        out.append(i6).append("logInfo(\"Template search failed: \" + __tplEx.getMessage());\n");
        out.append(i5).append("}\n");

        LoopDetector.BackEdge trueEdge  = pickEdge(nodeEdges, false);
        LoopDetector.BackEdge falseEdge = pickEdge(nodeEdges, true);

        out.append(i5).append("if (").append(var).append(" != null && ").append(var).append(".isFound()) {\n");

        if (tapOnHit) writeTapOnMatch(out, node, var, offX, offY);

        writeBranchTransition(out, node, trueEdge, trueNext, falseNext);
        out.append(i5).append("} else {\n");
        writeBranchTransition(out, node, falseEdge, falseNext, trueNext);
        out.append(i5).append("}\n");
    }

    private void writeCustomSearch(StringBuilder out, String tmpl, String var,
                                   int threshold, boolean mono, boolean bounded,
                                   String tlXs, String tlYs, String brXs, String brYs) {
        String i6 = indent(6);
        String path   = tmpl.substring(CUSTOM_TPL_SCHEME.length()).replace("\\", "\\\\");
        String method = mono ? "locatePatternMonoFromFile" : "locatePatternFromFile";

        out.append(i6).append(var).append(" = emuManager.").append(method)
           .append("(EMULATOR_NUMBER, \"").append(path).append("\", ");
        if (bounded) {
            out.append("new PointData(").append(tlXs).append(", ").append(tlYs).append("), ");
            out.append("new PointData(").append(brXs).append(", ").append(brYs).append("), ");
        }
        out.append(threshold).append(");\n");
    }

    private void writeEnumSearch(StringBuilder out, String tmpl, String var,
                                 int threshold, int maxAttempts, int delayMs,
                                 boolean mono, boolean bounded,
                                 String tlXs, String tlYs, String brXs, String brYs) {
        String i6 = indent(6);
        StringBuilder cfg = new StringBuilder("TemplateSearchHelper.SearchConfig.builder()");
        cfg.append(".withThreshold(").append(threshold).append(")");
        cfg.append(".withMaxAttempts(").append(maxAttempts).append(")");
        cfg.append(".withDelay(").append(delayMs).append("L)");
        if (bounded) {
            cfg.append(".withCoordinates(new PointData(").append(tlXs).append(", ").append(tlYs)
               .append("), new PointData(").append(brXs).append(", ").append(brYs).append("))");
        }
        cfg.append(".build()");

        String method = mono ? "locatePatternMono" : "locatePattern";
        out.append(i6).append(var).append(" = templateSearchHelper.")
           .append(method).append("(TemplatesEnum.").append(tmpl).append(", ")
           .append(cfg).append(");\n");
    }

    private void writeTapOnMatch(StringBuilder out, AutomationStep node,
                                 String var, int offX, int offY) {
        String i6 = indent(6);
        // Generated code goes through the shared input layer (DelayedTask API)
        // instead of low-level emulator taps, matching hand-written routines.
        if (offX != 0 || offY != 0) {
            String tapVar = "__tapPt_" + node.getId();
            out.append(i6).append("PointData ").append(tapVar)
               .append(" = new PointData(").append(var).append(".getPoint().getX() + ").append(offX)
               .append(", ").append(var).append(".getPoint().getY() + ").append(offY).append(");\n");
            out.append(i6).append("tapNear(").append(tapVar).append(", 3);\n");
        } else {
            out.append(i6).append("tapInside(").append(var).append(");\n");
        }
    }

    // ── Loop guard (unified) ──────────────────────────────────────────

    /**
     * Emits a loop-guarded state transition at the given indent depth.
     * Replaces the old pair of duplicate methods for top-level vs branch contexts.
     */
    private void writeLoopGuard(StringBuilder out, AutomationStep node,
                                LoopDetector.BackEdge edge,
                                int backTarget, int fwdTarget,
                                int depth) {
        int maxIter   = node.getParamAsInt("loopMaxIterations", 10);
        int loopDelay = node.getParamAsInt("loopDelayMs", 500);
        boolean fwd   = "CONTINUE".equals(node.getParam("loopExhaustedAction"));

        String d  = indent(depth);
        String d1 = indent(depth + 1);
        String counter = "__loopCount_" + edge.key();

        out.append(d).append(counter).append("++;\n");
        out.append(d).append("if (").append(counter).append(" > ").append(maxIter).append(") {\n");
        out.append(d1).append("logInfo(\"Loop limit reached (").append(maxIter).append(" iterations)\");\n");
        out.append(d1).append("__state = ").append(fwd && fwdTarget > 0 ? fwdTarget : -1).append(";\n");
        out.append(d).append("} else {\n");
        if (loopDelay > 0) {
            out.append(d1).append("sleepTask(").append(loopDelay).append("L);\n");
        }
        out.append(d1).append("__state = ").append(backTarget).append(";\n");
        out.append(d).append("}\n");
    }

    /**
     * Writes a state transition inside a branch body (if/else block),
     * optionally guarded by a loop counter if a back-edge applies.
     */
    private void writeBranchTransition(StringBuilder out, AutomationStep node,
                                       LoopDetector.BackEdge edge,
                                       int primary, int fallback) {
        String i6 = indent(6);
        if (edge != null && primary > 0) {
            writeLoopGuard(out, node, edge, primary, fallback > 0 ? fallback : -1, 6);
        } else {
            out.append(i6).append("__state = ").append(primary > 0 ? primary : -1).append(";\n");
        }
    }
}
