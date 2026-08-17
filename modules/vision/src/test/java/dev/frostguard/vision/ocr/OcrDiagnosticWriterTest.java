package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrDiagnosticWriterTest {

    @Test
    void writesReadableDiagnosticImageToWorkspaceCache(@TempDir Path workspace) throws Exception {
        String previousWorkspace = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        try {
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, workspace.toString());
            RawImageData capture = RawImageData.capture(new byte[] {
                    (byte) 255, 0, 0, (byte) 255
            }, 1, 1, 4);
            BufferedImage processed = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

            Path output = OcrDiagnosticWriter.write(capture, processed, 0, 0, 1, 1,
                    OcrSettingsData.configurator().diagnosticMode(true).build(), "test");

            assertTrue(output.startsWith(workspace.resolve("cache").resolve("ocr")));
            assertTrue(Files.isRegularFile(output));
            assertNotNull(ImageIO.read(output.toFile()));
        } finally {
            if (previousWorkspace == null) {
                System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            } else {
                System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previousWorkspace);
            }
        }
    }
}
