package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.*;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, no-input measurement including classification and recording overhead on a real device. */
@EnabledIfEnvironmentVariable(named = "FROSTGUARD_CAPTURE_PREFLIGHT_OUTPUT", matches = ".+")
class BearCaptureTimingLiveDeviceTest {
    @Test
    void captureAndClassificationLeaveFreshFramesForAuthorization() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        OpenCvPatternLocator.loadNativeLibrary();
        var classifier = new BearFrameClassifier(new BearTemplateMatcher(EmulatorController.getInstance(), "timing"), 2);
        var ages = new ArrayList<Long>();
        try (var recorder = BearCaptureRecorder.open(Path.of(System.getenv("FROSTGUARD_CAPTURE_PREFLIGHT_OUTPUT")), Instant.now());
             var source = new BearRealtimeFrameSource(System.getenv("FROSTGUARD_LIVE_ADB"),
                     System.getenv("FROSTGUARD_LIVE_ADB_SERIAL"), () -> false, recorder::nextSegment,
                     BearCaptureLiveDeviceTest::screencap)) {
            for (int sequence = 1; sequence <= 50; sequence++) {
                var captured = source.next();
                Instant started = Instant.now();
                var classified = classifier.classify(captured.frame());
                var origin = source.lastOrigin();
                String image = origin.kind() == BearRealtimeFrameSource.FrameOrigin.Kind.SCREENCAP
                        ? recorder.staticScreenshot(captured.frame()) : null;
                recorder.observed(sequence, origin, image, captured.capturedAt(),
                        Duration.between(captured.capturedAt(), started), classified.screen(),
                        classified.totalNanos(), classified.templateNanos());
                long age = Duration.between(captured.capturedAt(), Instant.now()).toMillis();
                if (sequence > 10) ages.add(age);
                assertNotEquals(BearNavigationPolicy.Screen.UNKNOWN, classified.screen(),
                        "the prepared screen must have positive identity");
            }
            System.out.println("BEAR_CAPTURE_PREFLIGHT_DIRECTORY=" + recorder.directory());
            assertNull(source.recordingFailure());
            assertEquals(0, recorder.droppedJournalLines());
        }
        Collections.sort(ages);
        long p95 = ages.get((int)Math.ceil(ages.size() * .95) - 1);
        System.out.printf("BEAR_CAPTURE_PREFLIGHT samples=%d medianMs=%d p95Ms=%d maxMs=%d%n",
                ages.size(), ages.get(ages.size()/2), p95, ages.getLast());
        assertTrue(p95 < 1000, "Recording-enabled p95 exceeds the one-second authorization budget: " + p95);
    }
}
