package dev.frostguard.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildMetadataTest {
    @Test
    void readsFilteredPrBuildIdentity() {
        BuildMetadata release = BuildMetadata.read(stream(
                "version=2.1.0\npullRequestBuild=false\nauthenticodePublisher=CN=Frostguard Project, O=Frostguard"));
        assertEquals("2.1.0", release.version());
        assertFalse(release.pullRequestBuild());
        assertEquals("CN=Frostguard Project, O=Frostguard", release.authenticodePublisher());
        assertTrue(BuildMetadata.read(stream("pullRequestBuild=true")).pullRequestBuild());
    }

    @Test
    void missingOrInvalidIdentityDisablesAutomaticUpdates() {
        BuildMetadata missing = BuildMetadata.read(null);
        assertEquals("unknown", missing.version());
        assertTrue(missing.pullRequestBuild());
        BuildMetadata invalid = BuildMetadata.read(stream("pullRequestBuild=maybe"));
        assertEquals("unknown", invalid.version());
        assertTrue(invalid.pullRequestBuild());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
