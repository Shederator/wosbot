package dev.frostguard.engine.helper;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.helper.CharacterSwitchHelper.MatchResult;
import dev.frostguard.engine.helper.CharacterSwitchHelper.MatchStatus;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Unit test coverage for character verification policy, alliance tag normalization,
 * and Chief Profile coordinate invariants.
 */
class CharacterSwitchMatchPolicyTest {

    @Test
    void savedChiefProfileHasSettingsAnchorInProductionRegion() throws IOException {
        OpenCvPatternLocator.loadNativeLibrary();
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/startup/chief-profile-settings-redacted.png"))) {
            var area = CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA;
            var hit = OpenCvPatternLocator.locatePattern(stream.readAllBytes(),
                    TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON.getTemplate(),
                    area.topLeft(), area.bottomRight(), 80);
            assertTrue(hit.isFound());
        }
    }

    @Nested
    @DisplayName("Chief Profile Coordinate Geometry Invariants")
    class CoordinateInvariants {

        @Test
        @DisplayName("Row 1 Name area sits strictly above Row 2 ID area")
        void nameAreaSitsAboveIdArea() {
            var nameArea = CommonGameAreas.CHARACTER_NAME_OCR_AREA;
            var idArea = CommonGameAreas.CHARACTER_ID_OCR_AREA;

            // Name is Row 1: Y 830..885
            assertEquals(280, nameArea.topLeft().getX());
            assertEquals(830, nameArea.topLeft().getY());
            assertEquals(600, nameArea.bottomRight().getX());
            assertEquals(885, nameArea.bottomRight().getY());

            // ID is Row 2: Y 895..935
            assertEquals(315, idArea.topLeft().getX());
            assertEquals(895, idArea.topLeft().getY());
            assertEquals(465, idArea.bottomRight().getX());
            assertEquals(935, idArea.bottomRight().getY());

            // Guarantee non-overlapping vertical order (Name strictly above ID)
            assertTrue(nameArea.bottomRight().getY() <= idArea.topLeft().getY(),
                    "Name area bottom must be <= ID area top");
        }
    }

    @Nested
    @DisplayName("Alliance Tag Normalization & nameMatch")
    class NameMatchPolicy {

        @Test
        @DisplayName("Matches base name when live OCR includes 3-char alliance tag")
        void matchesBaseNameWithAllianceTag() {
            assertTrue(CharacterSwitchHelper.nameMatch("[TAG]Example Hero", "Example Hero"));
            assertTrue(CharacterSwitchHelper.nameMatch("[ABC]Second Hero", "Second Hero"));
            assertTrue(CharacterSwitchHelper.nameMatch("[DEF]Third Hero", "Third Hero"));
            assertTrue(CharacterSwitchHelper.nameMatch("[XYZ]Fourth Hero", "Fourth Hero"));
        }

        @Test
        void keepsShortNamesWithExplicitTags() {
            assertTrue(CharacterSwitchHelper.nameMatch("[TAG]Snow", "Snow"));
            assertTrue(CharacterSwitchHelper.nameMatch("Example  Hero", "example hero"));
        }

        @Test
        void rejectsMissingDelimitersAndDistinctNames() {
            assertFalse(CharacterSwitchHelper.nameMatch("ABCSnow", "XYZSnow"));
            assertFalse(CharacterSwitchHelper.nameMatch("[TAG]Snowfall", "[TAG]Snow"));
            assertFalse(CharacterSwitchHelper.nameMatch("TAGExample Hero", "[TAG]Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("lTAGlExample Hero", "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("Snow-Fox", "SnowFox"));
            assertFalse(CharacterSwitchHelper.nameMatch("-", "_"));
        }


        @Test
        @DisplayName("Matches base name across clan changes (alliance hops)")
        void matchesAcrossClanChanges() {
            // Player switched from [TAG] to [NAP], but base name remains Example Hero
            assertTrue(CharacterSwitchHelper.nameMatch("[NAP]Example Hero", "[TAG]Example Hero"));
            // Player left alliance entirely (no tag in live OCR)
            assertTrue(CharacterSwitchHelper.nameMatch("Example Hero", "[TAG]Example Hero"));
        }

        @Test
        @DisplayName("Rejects distinct player names safely")
        void rejectsDifferentPlayerNames() {
            assertFalse(CharacterSwitchHelper.nameMatch("[TAG]Another Hero", "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("[ABC]Second Hero", "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("[TAG]Hero Example", "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("", "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch(null, "Example Hero"));
            assertFalse(CharacterSwitchHelper.nameMatch("[TAG]Example Hero", ""));
            assertFalse(CharacterSwitchHelper.nameMatch("[TAG]Example Hero", null));
        }
    }

    @Nested
    @DisplayName("Full 16-Row evaluateMatch Truth Table")
    class EvaluateMatchTruthTable {

        // ── 1. Neither configured ────────────────────────────────────

        @Test
        @DisplayName("Row 1: Neither configured -> Always PASS")
        void neitherConfiguredPasses() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch(null, "100000001", null, "Example Hero");
            assertTrue(res.ok());
            res = CharacterSwitchHelper.evaluateMatch("", "", "", "");
            assertTrue(res.ok());
        }

        // ── 2. Name-Only configured ──────────────────────────────────

        @Test
        @DisplayName("Row 2: Name-Only, MATCH -> PASS")
        void nameOnlyMatchPasses() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch(null, "any", "Example Hero", "[TAG]Example Hero");
            assertTrue(res.ok());
        }

        @Test
        @DisplayName("Row 3: Name-Only, MISMATCH -> FAIL (Fixes the original bug!)")
        void nameOnlyMismatchFails() {
            // Live character is Second Hero, profile wants Example Hero
            MatchResult res = CharacterSwitchHelper.evaluateMatch(null, "100000002", "Example Hero", "[ABC]Second Hero");
            assertFalse(res.ok(), "Must fail when name mismatches under name-only configuration");
            assertEquals(MatchStatus.MISMATCH, res.status());
        }

        @Test
        @DisplayName("Row 4: Name-Only, BLANK OCR -> FAIL (Strict fail-closed)")
        void nameOnlyBlankOcrFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch(null, "any", "Example Hero", "");
            assertFalse(res.ok());
            assertEquals(MatchStatus.UNKNOWN, res.status());
        }

        // ── 3. ID-Only configured ────────────────────────────────────

        @Test
        @DisplayName("Row 5: ID-Only, MATCH -> PASS")
        void idOnlyMatchPasses() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000001", null, "any");
            assertTrue(res.ok());
        }

        @Test
        @DisplayName("Row 6: ID-Only, MISMATCH -> FAIL (Fixes the original bug!)")
        void idOnlyMismatchFails() {
            // Live ID is 100000002, profile wants 100000001
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000002", null, "any");
            assertFalse(res.ok(), "Must fail when ID mismatches under ID-only configuration");
            assertEquals(MatchStatus.MISMATCH, res.status());
        }

        @Test
        @DisplayName("Row 7: ID-Only, BLANK OCR -> FAIL (Strict fail-closed)")
        void idOnlyBlankOcrFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "", null, "any");
            assertFalse(res.ok());
            assertEquals(MatchStatus.UNKNOWN, res.status());
        }

        // ── 4. Both configured ───────────────────────────────────────

        @Test
        @DisplayName("Row 8: Both configured, ID MATCH & Name MATCH -> PASS (Full Concurrence)")
        void bothMatchPasses() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000001", "Example Hero", "[TAG]Example Hero");
            assertTrue(res.ok());
        }

        @Test
        @DisplayName("Row 9: Both configured, ID MATCH & Name MISMATCH -> PASS (ID Authoritative, Name Drift)")
        void idMatchNameMismatchPassesDueToImmutableId() {
            // Player renamed in-game or changed clan tag, but immutable ID matches
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000001", "Example Hero", "[NEW]Renamed Hero");
            assertTrue(res.ok(), "Immutable ID match trumps name drift");
            assertTrue(res.detail().contains("accepted via immutable ID"));
        }

        @Test
        @DisplayName("Row 10: Both configured, ID MATCH & Name BLANK OCR -> PASS (ID Authoritative)")
        void idMatchNameBlankPassesDueToImmutableId() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000001", "Example Hero", "");
            assertTrue(res.ok(), "Immutable ID match succeeds even if name OCR is blank");
        }

        @Test
        @DisplayName("Row 11: Both configured, ID MISMATCH & Name MATCH -> FAIL (Hard Mismatch, ID trumps Name)")
        void idMismatchNameMatchFails() {
            // Duplicate name on another state, but ID contradicts
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000002", "Example Hero", "[TAG]Example Hero");
            assertFalse(res.ok(), "Contradicted immutable ID must fail verification despite name match");
            assertEquals(MatchStatus.MISMATCH, res.status());
        }

        @Test
        @DisplayName("Row 12: Both configured, ID MISMATCH & Name MISMATCH -> FAIL")
        void bothMismatchFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000002", "Example Hero", "[ABC]Second Hero");
            assertFalse(res.ok());
        }

        @Test
        @DisplayName("Row 13: Both configured, ID MISMATCH & Name BLANK OCR -> FAIL")
        void idMismatchNameBlankFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "100000002", "Example Hero", "");
            assertFalse(res.ok());
        }

        @Test
        @DisplayName("Row 14: Both configured, ID BLANK OCR & Name MATCH -> FAIL (Strict fail-closed)")
        void idBlankNameMatchFailsStrictly() {
            // ID was explicitly configured as ground truth, but could not be read
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "", "Example Hero", "[TAG]Example Hero");
            assertFalse(res.ok(), "Configured ID must be verified or fail-closed");
        }

        @Test
        @DisplayName("Row 15: Both configured, ID BLANK OCR & Name MISMATCH -> FAIL")
        void idBlankNameMismatchFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "", "Example Hero", "[ABC]Second Hero");
            assertFalse(res.ok());
        }

        @Test
        @DisplayName("Row 16: Both configured, ID BLANK OCR & Name BLANK OCR -> FAIL")
        void bothBlankFails() {
            MatchResult res = CharacterSwitchHelper.evaluateMatch("100000001", "", "Example Hero", "");
            assertFalse(res.ok());
        }
    }
}
