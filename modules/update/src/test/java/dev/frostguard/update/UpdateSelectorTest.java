package dev.frostguard.update;

import dev.frostguard.api.runtime.RuntimeChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateSelectorTest {
    private static final String TRUSTED_PUBLISHER = "CN=Frostguard Project, O=Frostguard";
    private final ManifestCodec codec = new ManifestCodec();
    private final UpdateSelector selector = new UpdateSelector();

    @Test
    void selectsNewerExactPlatformRelease() throws Exception {
        UpdateCandidate candidate = selector.select(manifest(), running(RuntimeChannel.STABLE, "3.0.0", false)).orElseThrow();

        assertEquals(SemanticVersion.parse("3.0.1"), candidate.version());
        assertEquals("windows-x64", candidate.platform().key());
    }

    @Test
    void ignoresCurrentOrOlderRelease() throws Exception {
        assertFalse(selector.select(manifest(), running(RuntimeChannel.STABLE, "3.0.1", false)).isPresent());
    }

    @Test
    void developmentAndPullRequestBuildsCannotUpdate() throws Exception {
        assertFalse(selector.select(manifest(), running(RuntimeChannel.DEVELOPMENT, "2.1.0", false)).isPresent());
        assertFalse(selector.select(manifest(), running(RuntimeChannel.STABLE, "2.1.0", true)).isPresent());
    }

    @Test
    void buildWithoutPinnedProjectKeyCannotUpdate() throws Exception {
        RunningBuild build = new RunningBuild(SemanticVersion.parse("3.0.0"), SemanticVersion.parse("3.0.0"),
                RuntimeChannel.STABLE, windowsX64(), false, new ManifestVerificationKey("", ""), "");
        assertFalse(selector.select(manifest(), build).isPresent());
    }

    @Test
    void selectsProjectAuthenticatedReleaseWithoutAuthenticode() throws Exception {
        UpdateManifest manifest = codec.read(
                ManifestCodecTest.validUnsignedManifest().getBytes(StandardCharsets.UTF_8));
        RunningBuild build = new RunningBuild(SemanticVersion.parse("3.0.0"), SemanticVersion.parse("3.0.0"),
                RuntimeChannel.STABLE, windowsX64(), false, TestManifestKeys.trustedKey(), "");

        assertEquals(SemanticVersion.parse("3.0.1"), selector.select(manifest, build).orElseThrow().version());
    }

    @Test
    void rejectsManifestSignerThatDiffersFromPinnedPublisher() throws Exception {
        String json = ManifestCodecTest.validManifest().replace(TRUSTED_PUBLISHER, "CN=Unexpected Publisher");
        UpdateManifest manifest = codec.read(json.getBytes(StandardCharsets.UTF_8));
        assertThrows(UpdateException.class,
                () -> selector.select(manifest, running(RuntimeChannel.STABLE, "3.0.0", false)));
    }

    @Test
    void rejectsCrossChannelManifest() throws Exception {
        assertThrows(UpdateException.class,
                () -> selector.select(manifest(), running(RuntimeChannel.NIGHTLY, "3.0.0", false)));
    }

    @Test
    void rejectsUpdaterBelowManifestMinimum() throws Exception {
        RunningBuild build = new RunningBuild(SemanticVersion.parse("2.9.0"), SemanticVersion.parse("2.9.0"),
                RuntimeChannel.STABLE, windowsX64(), false, TestManifestKeys.trustedKey(), TRUSTED_PUBLISHER);
        assertThrows(UpdateException.class, () -> selector.select(manifest(), build));
    }

    @Test
    void nightlyPrereleaseCanUpdateFromNightlyMinimum() throws Exception {
        String json = ManifestCodecTest.validManifest()
                .replace("\"channel\": \"stable\"", "\"channel\": \"nightly\"")
                .replace("\"version\": \"3.0.1\"", "\"version\": \"3.0.0-nightly.20260811.2\"")
                .replace("\"minimumUpdaterVersion\": \"3.0.0\"",
                        "\"minimumUpdaterVersion\": \"3.0.0-nightly.0\"")
                .replace("Frostguard-3.0.1", "Frostguard-3.0.0-nightly.20260811.2");
        UpdateManifest nightly = codec.read(json.getBytes(StandardCharsets.UTF_8));

        UpdateCandidate candidate = selector.select(nightly,
                running(RuntimeChannel.NIGHTLY, "3.0.0-nightly.20260811.1", false)).orElseThrow();

        assertEquals(SemanticVersion.parse("3.0.0-nightly.20260811.2"), candidate.version());
    }

    private UpdateManifest manifest() throws Exception {
        return codec.read(ManifestCodecTest.validManifest().getBytes(StandardCharsets.UTF_8));
    }

    private static RunningBuild running(RuntimeChannel channel, String version, boolean pullRequest) {
        return new RunningBuild(SemanticVersion.parse(version), SemanticVersion.parse(version),
                channel, windowsX64(), pullRequest, TestManifestKeys.trustedKey(), TRUSTED_PUBLISHER);
    }

    static UpdatePlatform windowsX64() {
        return new UpdatePlatform(UpdatePlatform.OperatingSystem.WINDOWS, UpdatePlatform.Architecture.X64);
    }
}
