package dev.frostguard.api.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import dev.frostguard.api.configs.FlowStepKind;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents one atomic instruction within an operator-defined
 * automation workflow. Varying step types carry different payloads
 * through a flexible key-value attribute map, avoiding the need
 * for a class hierarchy.
 *
 * <h3>Attribute conventions per kind</h3>
 * <ul>
 *   <li><b>TAP_POINT</b> — tlX, tlY, brX, brY</li>
 *   <li><b>WAIT</b> — durationMs</li>
 *   <li><b>SWIPE</b> — startX, startY, endX, endY</li>
 *   <li><b>BACK_BUTTON</b> — (none)</li>
 *   <li><b>OCR_READ</b> — tlX, tlY, brX, brY, condition, expectedValue</li>
 *   <li><b>TEMPLATE_SEARCH</b> — templatePath, threshold, grayscale, tlX, brX</li>
 *   <li><b>NAVIGATE</b> — location</li>
 * </ul>
 *
 * <h3>JSON mapping</h3>
 * <p>Persistence binds to the private fields only. This class also exposes
 * legacy accessor shims ({@code getParams}, {@code getNextNodeId}, …) and
 * derived read-only views ({@code getSummary}, {@code isBranching}, …). Were
 * Jackson allowed to auto-detect those methods, every stored value would be
 * written two or three times under different names and each derived getter
 * would be invoked while writing — so one failing getter aborts the write
 * midway and leaves a truncated, unparseable document on disk. Field-only
 * visibility keeps the saved file canonical and makes future helper methods
 * inert by default instead of silently joining the output. {@link JsonAlias}
 * still accepts the historical key spellings when reading older files.</p>
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
public class AutomationStep {
    public static final String PARAM_NODE_NAME = "nodeName";
    public static final int NODE_NAME_MAX_LENGTH = 30;

    @JsonAlias("id")
    private int stepId;

    @JsonAlias("type")
    private FlowStepKind kind;

    @JsonAlias("params")
    @JsonSetter(nulls = Nulls.SKIP)
    private Map<String, String> attributes;

    @JsonAlias("executed")
    private boolean completed;

    @JsonAlias("canvasX")
    private double layoutX;

    @JsonAlias("canvasY")
    private double layoutY;

    @JsonAlias("nextNodeId")
    private int successorId  = -1;

    @JsonAlias("nextNodeFalseId")
    private int alternateId  = -1;

    @JsonAlias("lastOcrResult")
    private String lastReadValue = null;

    /** Creates a blank step with an empty attribute map. */
    public AutomationStep() {
        this.attributes = new HashMap<>();
        this.completed  = false;
    }

    /**
     * Creates a step with the given identifier and kind.
     *
     * @param stepId unique identifier within the workflow
     * @param kind   the operational type of this step
     */
    public AutomationStep(int stepId, FlowStepKind kind) {
        this.stepId     = stepId;
        this.kind       = kind;
        this.attributes = new HashMap<>();
        this.completed  = false;
    }

    /* ---- identity ---- */

    public int getStepId()              { return stepId; }
    public void setStepId(int stepId)   { this.stepId = stepId; }

    /* ---- kind ---- */

    public FlowStepKind getKind()                { return kind; }
    public void setKind(FlowStepKind kind)       { this.kind = kind; }

    /* ---- attributes ---- */

    public Map<String, String> getAttributes()                { return attributes; }

    /**
     * Replaces the attribute map, copying the supplied entries so the step
     * never aliases a caller-owned map and never holds a null map. A stored
     * node name is re-sanitized on the way in, because attribute maps also
     * arrive from hand-edited files.
     */
    public void setAttributes(Map<String, String> attrs) {
        Map<String, String> replacement = new HashMap<>();
        if (attrs != null) {
            replacement.putAll(attrs);
        }
        this.attributes = replacement;
        if (replacement.containsKey(PARAM_NODE_NAME)) {
            setNodeName(replacement.get(PARAM_NODE_NAME));
        }
    }

    /** Stores an attribute, keeping the node name sanitized and readable. */
    public void putAttribute(String key, String value) {
        if (PARAM_NODE_NAME.equals(key)) {
            setNodeName(value);
        } else {
            attributes.put(key, value);
        }
    }

    public String getAttribute(String key)              { return attributes.get(key); }

    public String getNodeName() {
        String value = getAttribute(PARAM_NODE_NAME);
        return value != null ? value : "";
    }

    public void setNodeName(String nodeName) {
        String sanitized = sanitizeNodeName(nodeName);
        if (sanitized.isEmpty()) {
            attributes.remove(PARAM_NODE_NAME);
        } else {
            attributes.put(PARAM_NODE_NAME, sanitized);
        }
    }

    public static String sanitizeNodeName(String nodeName) {
        if (nodeName == null) {
            return "";
        }
        String sanitized = nodeName.replace('\r', ' ').replace('\n', ' ').trim();
        if (sanitized.length() > NODE_NAME_MAX_LENGTH) {
            sanitized = sanitized.substring(0, NODE_NAME_MAX_LENGTH);
        }
        return sanitized;
    }

    /**
     * Reads an attribute as an integer, returning the supplied
     * fallback when the attribute is missing or non-numeric.
     */
    public int readIntAttribute(String key, int fallback) {
        String raw = attributes.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /* ---- execution state ---- */

    public boolean isCompleted()                   { return completed; }
    public void setCompleted(boolean completed)    { this.completed = completed; }

    /* ---- canvas layout position ---- */

    public double getLayoutX()          { return layoutX; }
    public void setLayoutX(double x)    { this.layoutX = x; }

    public double getLayoutY()          { return layoutY; }
    public void setLayoutY(double y)    { this.layoutY = y; }

    /* ---- flow graph edges ---- */

    public int getSuccessorId()           { return successorId; }
    public void setSuccessorId(int id)    { this.successorId = id; }

    public int getAlternateId()           { return alternateId; }
    public void setAlternateId(int id)    { this.alternateId = id; }

    /* ---- OCR result cache ---- */

    public String getLastReadValue()             { return lastReadValue; }
    public void setLastReadValue(String val)     { this.lastReadValue = val; }

    /* ---- structural queries ---- */

    /**
     * Whether this step type produces a conditional branch
     * (OCR_READ or TEMPLATE_SEARCH).
     */
    public boolean isBranching() {
        return kind == FlowStepKind.OCR_READ
                || kind == FlowStepKind.TEMPLATE_SEARCH;
    }

    /**
     * Whether a bounding region is defined via tlX/tlY/brX/brY attributes.
     */
    public boolean hasRegion() {
        return attributes.containsKey("tlX")
                && attributes.containsKey("tlY")
                && attributes.containsKey("brX")
                && attributes.containsKey("brY");
    }

    /** Whether a successor edge has been connected. */
    public boolean hasSuccessor() {
        return successorId >= 0;
    }

    /** Whether an alternate (false-branch) edge has been connected. */
    public boolean hasAlternate() {
        return alternateId >= 0;
    }

    /** Total number of attributes currently stored on this step. */
    public int attributeCount() {
        return attributes.size();
    }

    /** Removes all attributes from this step. */
    public void clearAttributes() {
        attributes.clear();
    }

    /**
     * Copies all attributes from the supplied map into this step's
     * attribute store, overwriting any existing entries with
     * matching keys.
     *
     * @param incoming the attributes to merge in
     */
    public void mergeAttributes(Map<String, String> incoming) {
        if (incoming != null) {
            attributes.putAll(incoming);
        }
    }

    /**
     * Creates a deep copy of this step with an independent
     * attribute map. The new step retains the same id, kind,
     * and graph edges but is not linked by reference.
     *
     * @return an independent clone of this step
     */
    public AutomationStep duplicate() {
        AutomationStep copy = new AutomationStep(this.stepId, this.kind);
        copy.attributes    = new HashMap<>(this.attributes);
        copy.completed     = this.completed;
        copy.layoutX       = this.layoutX;
        copy.layoutY       = this.layoutY;
        copy.successorId   = this.successorId;
        copy.alternateId   = this.alternateId;
        copy.lastReadValue = this.lastReadValue;
        return copy;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public int getId()                          { return stepId; }
    public void setId(int id)                   { this.stepId = id; }
    public FlowStepKind getType()               { return kind; }
    public void setType(FlowStepKind type)      { this.kind = type; }
    public Map<String, String> getParams()      { return attributes; }
    public void setParams(Map<String, String> p){ setAttributes(p); }
    public void setParam(String k, String v)    { putAttribute(k, v); }
    public String getParam(String k)            { return getAttribute(k); }
    public int getParamAsInt(String k, int d)   { return readIntAttribute(k, d); }
    public boolean isExecuted()                 { return completed; }
    public void setExecuted(boolean e)          { this.completed = e; }
    public double getCanvasX()                  { return layoutX; }
    public void setCanvasX(double x)            { this.layoutX = x; }
    public double getCanvasY()                  { return layoutY; }
    public void setCanvasY(double y)            { this.layoutY = y; }
    public int getNextNodeId()                  { return successorId; }
    public void setNextNodeId(int id)           { this.successorId = id; }
    public int getNextNodeFalseId()             { return alternateId; }
    public void setNextNodeFalseId(int id)      { this.alternateId = id; }
    public String getLastOcrResult()            { return lastReadValue; }
    public void setLastOcrResult(String r)      { this.lastReadValue = r; }

    /**
     * Constructs a concise human-readable summary of this step
     * suitable for display in workflow editor panels.
     *
     * @return a formatted description based on the step kind
     */
    public String describeBriefly() {
        return switch (kind) {
            case TAP_POINT -> String.format("Tap (%s→%s, %s→%s)",
                    resolveAttr("tlX"), resolveAttr("brX"),
                    resolveAttr("tlY"), resolveAttr("brY"));

            case WAIT -> String.format("Wait %sms",
                    resolveAttr("durationMs"));

            case SWIPE -> String.format("Swipe (%s,%s) → (%s,%s)",
                    resolveAttr("startX"), resolveAttr("startY"),
                    resolveAttr("endX"),   resolveAttr("endY"));

            case BACK_BUTTON -> "Back Button";

            case OCR_READ -> {
                String cond     = resolveAttrOr("condition", "CONTAINS");
                String expected = resolveAttrOr("expectedValue", "?");
                yield String.format("OCR (%s, %s)→(%s, %s) %s '%s'",
                        resolveAttr("tlX"), resolveAttr("tlY"),
                        resolveAttr("brX"), resolveAttr("brY"),
                        cond, expected);
            }

            case TEMPLATE_SEARCH -> {
                String tplPath   = resolveAttrOr("templatePath", "?");
                String threshold = resolveAttrOr("threshold", "90");
                boolean gs       = "true".equals(getAttribute("grayscale"));
                boolean bounded  = getAttribute("tlX") != null
                        && getAttribute("brX") != null;
                String suffix    = (gs ? " GS" : "")
                        + (bounded ? " [area]" : " [full]");
                yield String.format("Find: %s @%s%% %s",
                        tplPath, threshold, suffix);
            }

            case NAVIGATE -> String.format("Navigate: %s",
                    resolveAttrOr("location", "HOME"));
        };
    }

    /** Alias retained for backward compatibility. */
    public String getSummary() { return describeBriefly(); }

    /* ---- private attribute helpers ---- */

    private String resolveAttr(String key) {
        String val = getAttribute(key);
        return val != null ? val : "?";
    }

    private String resolveAttrOr(String key, String fallback) {
        String val = getAttribute(key);
        return val != null ? val : fallback;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s", stepId, describeBriefly());
    }
}
