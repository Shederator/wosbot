package dev.frostguard.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {
    @Test
    void ordersStableAfterPrerelease() {
        assertTrue(SemanticVersion.parse("3.1.0").compareTo(
                SemanticVersion.parse("3.1.0-nightly.20260810.1")) > 0);
    }

    @Test
    void ordersNumericNightlyBuildsNumerically() {
        assertTrue(SemanticVersion.parse("3.1.0-nightly.10").compareTo(
                SemanticVersion.parse("3.1.0-nightly.2")) > 0);
    }

    @Test
    void ignoresBuildMetadataForPrecedence() {
        assertEquals(SemanticVersion.parse("3.0.1+first"), SemanticVersion.parse("3.0.1+second"));
    }

    @Test
    void rejectsLeadingZeroInNumericPrerelease() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("3.0.0-nightly.01"));
    }

    @Test
    void rejectsNonSemanticVersion() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("3.0"));
    }
}
