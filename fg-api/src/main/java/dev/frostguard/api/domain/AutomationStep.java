package dev.frostguard.api.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 */
public class AutomationStep {
    public static final String PARAM_NODE_NAME = "nodeName";
    public static final int NODE_NAME_MAX_LENGTH = 30;

    private int stepId;
    private FlowStepKind kind;
    private Map<String, String> attributes;
    private boolean completed;

    private double layoutX;
    private double layoutY;

    private int successorId  = -1;
    private int alternateId  = -1;
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
    @JsonAlias("id")
    public void setStepId(int stepId)   { this.stepId = stepId; }

    /* ---- kind ---- */

    public FlowStepKind getKind()                { return kind; }
    @JsonAlias("type")
    public void setKind(FlowStepKind kind)       { this.kind = kind; }

    /* ---- attributes ---- */

    public Map<String, String> getAttributes()                { return attributes; }
    @JsonAlias("params")
    public void setAttributes(Map<String, String> attrs)      {
        String existingNodeName = getNodeName();
        this.attributes = new HashMap<>();
        if (attrs != null) {
            this.attributes.putAll(attrs);
            if (this.attributes.containsKey(PARAM_NODE_NAME)) {
                setNodeName(this.attributes.remove(PARAM_NODE_NAME));
            } else if (!existingNodeName.isEmpty()) {
                setNodeName(existingNodeName);
            }
        }
    }

    @JsonIgnore
    public void putAttribute(String key, String value)  {
        if (PARAM_NODE_NAME.equals(key)) {
            setNodeName(value);
        } else {
            attributes.put(key, value);
        }
    }
    @JsonIgnore
    public String getAttribute(String key)              { return attributes.get(key); }

    @JsonIgnore
    public String getNodeName() {
        String value = getAttribute(PARAM_NODE_NAME);
        return value != null ? value : "";
    }

    @JsonProperty(value = PARAM_NODE_NAME, access = JsonProperty.Access.WRITE_ONLY)
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
    @JsonAlias("executed")
    public void setCompleted(boolean completed)    { this.completed = completed; }

    /* ---- canvas layout position ---- */

    public double getLayoutX()          { return layoutX; }
    @JsonAlias("canvasX")
    public void setLayoutX(double x)    { this.layoutX = x; }

    public double getLayoutY()          { return layoutY; }
    @JsonAlias("canvasY")
    public void setLayoutY(double y)    { this.layoutY = y; }

    /* ---- flow graph edges ---- */

    public int getSuccessorId()           { return successorId; }
    @JsonAlias("nextNodeId")
    public void setSuccessorId(int id)    { this.successorId = id; }

    public int getAlternateId()           { return alternateId; }
    @JsonAlias("nextNodeFalseId")
    public void setAlternateId(int id)    { this.alternateId = id; }

    /* ---- OCR result cache ---- */

    public String getLastReadValue()             { return lastReadValue; }
    @JsonAlias("lastOcrResult")
    public void setLastReadValue(String val)     { this.lastReadValue = val; }

    /* ---- structural queries ---- */

    /**
     * Whether this step type produces a conditional branch
     * (OCR_READ or TEMPLATE_SEARCH).
     */
    @JsonIgnore
    public boolean isBranching() {
        return kind == FlowStepKind.OCR_READ
                || kind == FlowStepKind.TEMPLATE_SEARCH;
    }

    /**
     * Whether a bounding region is defined via tlX/tlY/brX/brY attributes.
     */
    @JsonIgnore
    public boolean hasRegion() {
        return attributes.containsKey("tlX")
                && attributes.containsKey("tlY")
                && attributes.containsKey("brX")
                && attributes.containsKey("brY");
    }

    /** Whether a successor edge has been connected. */
    @JsonIgnore
    public boolean hasSuccessor() {
        return successorId >= 0;
    }

    /** Whether an alternate (false-branch) edge has been connected. */
    @JsonIgnore
    public boolean hasAlternate() {
        return alternateId >= 0;
    }

    /** Total number of attributes currently stored on this step. */
    @JsonIgnore
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

    @JsonIgnore public int getId()              { return stepId; }
    public void setId(int id)                   { this.stepId = id; }
    @JsonIgnore public FlowStepKind getType()   { return kind; }
    public void setType(FlowStepKind type)      { this.kind = type; }
    @JsonIgnore public Map<String, String> getParams() { return attributes; }
    public void setParams(Map<String, String> p){ setAttributes(p); }
    public void setParam(String k, String v)    { putAttribute(k, v); }
    public String getParam(String k)            { return getAttribute(k); }
    public int getParamAsInt(String k, int d)   { return readIntAttribute(k, d); }
    @JsonIgnore public boolean isExecuted()     { return completed; }
    public void setExecuted(boolean e)          { this.completed = e; }
    @JsonIgnore public double getCanvasX()      { return layoutX; }
    public void setCanvasX(double x)            { this.layoutX = x; }
    @JsonIgnore public double getCanvasY()      { return layoutY; }
    public void setCanvasY(double y)            { this.layoutY = y; }
    @JsonIgnore public int getNextNodeId()      { return successorId; }
    public void setNextNodeId(int id)           { this.successorId = id; }
    @JsonIgnore public int getNextNodeFalseId() { return alternateId; }
    public void setNextNodeFalseId(int id)      { this.alternateId = id; }
    @JsonIgnore public String getLastOcrResult(){ return lastReadValue; }
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
    @JsonIgnore
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
