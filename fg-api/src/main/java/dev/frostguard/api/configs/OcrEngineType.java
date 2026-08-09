package dev.frostguard.api.configs;

public enum OcrEngineType {
    TESSERACT("Tesseract (Default)"),
    PADDLE_ONNX("PaddleOCR (Experimental)");

    private final String displayName;

    OcrEngineType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static OcrEngineType fromString(String str) {
        if (str == null || str.isBlank()) {
            return TESSERACT;
        }
        for (OcrEngineType type : values()) {
            if (type.name().equalsIgnoreCase(str) || type.displayName.equalsIgnoreCase(str)) {
                return type;
            }
        }
        return TESSERACT; // default fallback
    }
}
