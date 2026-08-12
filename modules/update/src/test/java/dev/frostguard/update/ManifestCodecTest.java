package dev.frostguard.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestCodecTest {
    private final ManifestCodec codec = new ManifestCodec();

    @Test
    void acceptsStrictSchemaOneManifest() throws Exception {
        UpdateManifest manifest = codec.read(validManifest().getBytes(StandardCharsets.UTF_8));

        assertEquals(1, manifest.schemaVersion());
        assertEquals("stable", manifest.channel());
        assertEquals("Frostguard-3.0.1-windows-x64.msi",
                manifest.artifacts().get("windows-x64").fileName());
    }

    @Test
    void rejectsUnknownFields() {
        String json = validManifest().replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"extra\": true,");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnsupportedSchema() {
        String json = validManifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsDevelopmentManifest() {
        String json = validManifest().replace("\"channel\": \"stable\"", "\"channel\": \"development\"");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMutableOrMismatchedArtifactName() {
        String json = validManifest().replace("Frostguard-3.0.1-windows-x64.msi", "Frostguard-latest.msi");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWindowsExeWrapper() {
        String json = validManifest().replace("windows-x64.msi", "windows-x64.exe");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsProjectAuthenticatedWindowsArtifactWithoutAuthenticode() throws Exception {
        UpdateManifest manifest = codec.read(validUnsignedManifest().getBytes(StandardCharsets.UTF_8));
        assertEquals(null, manifest.artifacts().get("windows-x64").signature());
    }

    @Test
    void rejectsInvalidOptionalAuthenticodeRequirement() {
        String json = validManifest().replace("\"type\": \"authenticode\"", "\"type\": \"none\"");
        assertThrows(UpdateException.class, () -> codec.read(json.getBytes(StandardCharsets.UTF_8)));
    }

    static String validUnsignedManifest() {
        return validManifest().replaceAll(",\\s*\"signature\"\\s*:\\s*\\{[^}]+}", "");
    }

    static String validManifest() {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "stable",
                  "version": "3.0.1",
                  "publishedAt": "2026-08-10T04:00:00Z",
                  "minimumUpdaterVersion": "3.0.0",
                  "releaseNotesUrl": "https://example.com/releases/3.0.1",
                  "artifacts": {
                    "windows-x64": {
                      "operatingSystem": "windows",
                      "architecture": "x64",
                      "fileName": "Frostguard-3.0.1-windows-x64.msi",
                      "url": "https://example.com/releases/3.0.1/Frostguard-3.0.1-windows-x64.msi",
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "size": 123,
                      "signature": {"type": "authenticode", "publisher": "CN=Frostguard Project, O=Frostguard"}
                    }
                  }
                }
                """;
    }
}
