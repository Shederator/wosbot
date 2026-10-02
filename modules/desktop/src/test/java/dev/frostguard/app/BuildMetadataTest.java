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
                "version=2.1.0\npullRequestBuild=false\nauthenticodePublisher=CN=Frostguard Project, O=Frostguard" +
                        "\ncommit=0123456789ABCDEF0123456789ABCDEF01234567" +
                        "\nbuildTime=2026-10-02T12:34:56Z"));
        assertEquals("2.1.0", release.version());
        assertFalse(release.pullRequestBuild());
        assertEquals("CN=Frostguard Project, O=Frostguard", release.authenticodePublisher());
        assertEquals("0123456789abcdef0123456789abcdef01234567", release.commit());
        assertEquals("2026-10-02T12:34:56Z", release.buildTime());
        BuildMetadata development = BuildMetadata.read(stream("pullRequestBuild=true"));
        assertTrue(development.pullRequestBuild());
        assertEquals("unknown", development.commit());
        assertEquals("unknown", development.buildTime());
    }

    @Test
    void missingOrInvalidIdentityDisablesAutomaticUpdates() {
        BuildMetadata missing = BuildMetadata.read(null);
        assertEquals("unknown", missing.version());
        assertTrue(missing.pullRequestBuild());
        BuildMetadata invalid = BuildMetadata.read(stream("pullRequestBuild=maybe"));
        assertEquals("unknown", invalid.version());
        assertTrue(invalid.pullRequestBuild());
        BuildMetadata malformedBuildIdentity = BuildMetadata.read(stream(
                "version=${project.version}\npullRequestBuild=false\ncommit=not-a-sha\nbuildTime=not-a-time"));
        assertEquals("unknown", malformedBuildIdentity.version());
        assertEquals("unknown", malformedBuildIdentity.commit());
        assertEquals("unknown", malformedBuildIdentity.buildTime());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
