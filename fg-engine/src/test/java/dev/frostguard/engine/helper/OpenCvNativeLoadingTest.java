package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Guards the platform-aware OpenCV native loading contract.
 *
 * <p>The bundled {@code opencv_java4110.dll} only loads on Windows, so a build
 * or test run on any other operating system used to die with
 * {@link UnsatisfiedLinkError}. {@link OpenCvPatternLocator#loadNativeLibrary()}
 * now picks the native image that matches the host, which is what allows the
 * vision and OCR regression suites to run on Linux CI runners while the shipped
 * Windows bundle keeps using its own DLL.</p>
 */
class OpenCvNativeLoadingTest {

    @Test
    void loadsAnOpenCvNativeImageOnWhicheverPlatformTheBuildRunsOn() {
        assertDoesNotThrow(OpenCvPatternLocator::loadNativeLibrary,
                "OpenCV must load on the current platform, not only on Windows");

        // Allocating a Mat crosses the JNI boundary, so it only succeeds when a
        // native image really was bound rather than merely resolved on disk.
        Mat probe = new Mat(4, 6, CvType.CV_8UC3);
        try {
            assertEquals(4, probe.rows());
            assertEquals(6, probe.cols());
            assertTrue(Core.VERSION.startsWith("4."),
                    "Unexpected OpenCV version: " + Core.VERSION);
        } finally {
            probe.release();
        }
    }

    @Test
    void repeatedLoadsAreIdempotent() {
        assertDoesNotThrow(OpenCvPatternLocator::loadNativeLibrary);
        // A second call must not attempt to bind the library again; System.load
        // would throw if the same image were loaded twice in one JVM.
        assertDoesNotThrow(OpenCvPatternLocator::loadNativeLibrary);
    }

    @Test
    void theWindowsNativeImageStaysBundledForTheShippedDesktopArchive() throws Exception {
        assertEquals("/native/opencv/opencv_java4110.dll",
                OpenCvPatternLocator.WINDOWS_NATIVE_RESOURCE,
                "The Windows bundle resolves the DLL from this classpath location");

        try (InputStream dll = OpenCvPatternLocator.class
                .getResourceAsStream(OpenCvPatternLocator.WINDOWS_NATIVE_RESOURCE)) {
            assertNotNull(dll, "The Windows OpenCV DLL must remain on the fg-vision classpath");
        }
    }

    /**
     * A Git-LFS-less clone leaves a ~130 byte pointer stub where the 52 MB DLL
     * should be. The bundled resource must be the real binary, otherwise the
     * packaged Windows bundle would ship an unloadable native image.
     */
    @Test
    void theBundledWindowsImageIsARealBinaryAndNotAnLfsPointerStub() throws Exception {
        byte[] head = new byte[64];
        int read;
        try (InputStream dll = OpenCvPatternLocator.class
                .getResourceAsStream(OpenCvPatternLocator.WINDOWS_NATIVE_RESOURCE)) {
            assertNotNull(dll, "The Windows OpenCV DLL must remain on the fg-vision classpath");
            read = dll.readNBytes(head, 0, head.length);
        }

        assertEquals(head.length, read, "The bundled DLL is far too small to be real");
        String prefix = new String(head, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(prefix.contains("git-lfs.github.com/spec"),
                "The bundled DLL is still a Git LFS pointer stub; run 'git lfs pull'");
        // Every Windows PE image starts with the "MZ" DOS header magic.
        assertEquals("MZ", prefix.substring(0, 2),
                "The bundled Windows image is not a PE binary");
    }

    /**
     * Callers treat a successful return as "OpenCV is usable". If no native image
     * could be bound the method must throw rather than return quietly, otherwise
     * the failure would surface much later as a confusing {@link UnsatisfiedLinkError}
     * from an unrelated matching call.
     */
    @Test
    void aSuccessfulReturnMeansOpenCvIsActuallyUsable() throws Exception {
        OpenCvPatternLocator.loadNativeLibrary();

        // Exercise a real native call rather than just allocating a Mat.
        Mat source = new Mat(8, 8, CvType.CV_8UC1);
        Mat destination = new Mat();
        try {
            Core.bitwise_not(source, destination);
            assertEquals(8, destination.rows());
            assertEquals(8, destination.cols());
            assertFalse(destination.empty(), "A native OpenCV operation produced no output");
        } finally {
            source.release();
            destination.release();
        }
    }
}
