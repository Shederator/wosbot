package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.video.H264FrameDecoder;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a recorded Bear capture segment through the production classifier and the production
 * template matcher, frame by frame, in recorded order.
 */
final class BearRecordingReplay {

    private BearRecordingReplay() {
    }

    static List<BearFrameClassifier.Classification> classify(InputStream segment, int trapNumber)
            throws Exception {
        H264FrameDecoder.prepareRuntime();
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError alreadyLoaded) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
        BearFrameClassifier classifier = new BearFrameClassifier(
                new BearTemplateMatcher(EmulatorController.getInstance(), "bear-replay"),
                trapNumber);
        List<BearFrameClassifier.Classification> classifications = new ArrayList<>();
        try (H264FrameDecoder decoder = new H264FrameDecoder(segment)) {
            decoder.start();
            RawImageData frame;
            while ((frame = decoder.nextFrame()) != null) {
                classifications.add(classifier.classify(frame));
            }
        }
        return classifications;
    }
}
