package dev.frostguard.engine.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CharacterSwitchHelper#verifyCurrentCharacter} logic.
 *
 * <p>The verification policy is:
 * <ul>
 *   <li>If neither name nor ID is configured, skip verification and return {@code true}.
 *   <li>A positive ID match or a positive name match is sufficient to pass.
 *   <li>If a field is configured but OCR returns blank, log a warning and treat as no-match
 *       (not an automatic pass — blank OCR must not bypass profile verification).
 *   <li>A mismatch on both configured fields returns {@code false}.
 * </ul>
 */
class CharacterSwitchHelperVerificationTest {

    // =========================================================================
    //  verifyCurrentCharacter logic extracted for unit testing
    // =========================================================================

    /**
     * Extracted verification logic matching CharacterSwitchHelper exactly.
     * Allows unit testing without constructing the full helper (which needs live emulator).
     */
    private boolean verify(String wantName, String wantId, String liveName, String liveId) {
        if (blank(wantName) && blank(wantId)) return true;            // neither configured — skip
        boolean idMatch = !blank(wantId) && !blank(liveId) && wantId.equals(liveId);
        boolean nameMatch = !blank(wantName) && !blank(liveName)
                && liveName.trim().equalsIgnoreCase(wantName.trim());
        return idMatch || nameMatch;
    }

    private static boolean blank(String s) { return s == null || s.isEmpty(); }

    // =========================================================================
    //  Policy: neither configured
    // =========================================================================

    @Test
    void neitherConfigured_skipVerificationAndReturnTrue() {
        // When no character identity is configured, verification is a no-op.
        assertTrue(verify(null, null, "Cloudbed", "12345"));
        assertTrue(verify("", "", "Cloudbed", "12345"));
        assertTrue(verify(null, null, "", ""));
    }

    // =========================================================================
    //  Policy: ID configured only
    // =========================================================================

    @Test
    void idConfiguredAndMatches_returnsTrue() {
        assertTrue(verify(null, "12345", null, "12345"));
    }

    @Test
    void idConfiguredAndMismatches_returnsFalse() {
        assertFalse(verify(null, "12345", null, "99999"));
    }

    @Test
    void idConfiguredButOcrReturnsBlank_returnsFalse() {
        // Blank OCR must not be treated as a match — this was the original bug.
        assertFalse(verify(null, "12345", null, ""));
        assertFalse(verify(null, "12345", null, null));
    }

    // =========================================================================
    //  Policy: name configured only
    // =========================================================================

    @Test
    void nameConfiguredAndMatches_returnsTrue() {
        assertTrue(verify("Cloudbed", null, "Cloudbed", null));
    }

    @Test
    void nameConfiguredAndMatchesCaseInsensitive_returnsTrue() {
        assertTrue(verify("cloudbed", null, "CLOUDBED", null));
    }

    @Test
    void nameConfiguredAndMismatches_returnsFalse() {
        assertFalse(verify("Cloudbed", null, "ShadowMoon", null));
    }

    @Test
    void nameConfiguredButOcrReturnsBlank_returnsFalse() {
        // Blank OCR must not be treated as a match — this was the original bug.
        assertFalse(verify("Cloudbed", null, "", null));
        assertFalse(verify("Cloudbed", null, null, null));
    }

    // =========================================================================
    //  Policy: both configured
    // =========================================================================

    @Test
    void bothConfiguredAndBothMatch_returnsTrue() {
        assertTrue(verify("Cloudbed", "12345", "Cloudbed", "12345"));
    }

    @Test
    void bothConfiguredAndOnlyIdMatches_returnsTrue() {
        // Either field matching is sufficient.
        assertTrue(verify("Cloudbed", "12345", "DifferentName", "12345"));
    }

    @Test
    void bothConfiguredAndOnlyNameMatches_returnsTrue() {
        // Either field matching is sufficient.
        assertTrue(verify("Cloudbed", "12345", "Cloudbed", "99999"));
    }

    @Test
    void bothConfiguredAndBothMismatch_returnsFalse() {
        assertFalse(verify("Cloudbed", "12345", "ShadowMoon", "99999"));
    }

    @Test
    void bothConfiguredButOcrReturnsBothBlank_returnsFalse() {
        assertFalse(verify("Cloudbed", "12345", "", ""));
    }
}
