package dev.frostguard.tasks.events;

import java.util.regex.Pattern;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.TesseractOcrSession;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.PointData;

/** Reads depth away from the steering loop; absence of depth evidence never proves line exhaustion. */
final class FishingDepthMonitor implements AutoCloseable {
    record Depth(int current, int maximum) { }
    record Reading(Depth depth, long sequence, long receivedNanos) { }
    private static final Pattern DEPTH = Pattern.compile("(\\d+)\\s*[mM]\\s*(\\d+)\\s*[mM]");
    private static final Pattern DEPTH_FIELD = Pattern.compile("(\\d+)\\s*[mM]?");
    private final Thread worker;
    private volatile boolean stopping;
    private volatile boolean exhausted;
    private volatile boolean sufficient;
    private volatile int peak;
    private volatile Reading latest;
    private volatile String failure;
    private volatile boolean ready;
    private volatile FishingHaulTracker.Reading haul;
    private volatile int verifiedMaximum;

    FishingDepthMonitor(AndroidFrameStream stream) {
        this(stream, 0);
    }

    FishingDepthMonitor(AndroidFrameStream stream, int independentlyVerifiedMaximum) {
        if (independentlyVerifiedMaximum < 0 || independentlyVerifiedMaximum > 10000)
            throw new IllegalArgumentException("Invalid verified line maximum");
        verifiedMaximum = independentlyVerifiedMaximum;
        worker = new Thread(() -> {
            long sequence = 0;
            int setupCandidate = 0, setupConfirmations = 0;
            var filter = new FishingDepthFilter();
            var haulFilter = new FishingHaulTracker();
            try (var ocr = new TesseractOcrSession(CommonOCRSettings.FISHING_DEPTH_SETTINGS);
                    var capacityOcr = new TesseractOcrSession(CommonOCRSettings.STAMINA_FRACTION_SETTINGS)) {
                ocr.prepare();
                capacityOcr.prepare();
                ready = true;
                while (!stopping) {
                    var frame = stream.latestAfter(sequence);
                    if (frame != null) {
                        sequence = frame.sequence();
                        try {
                            boolean active = OpenCvPatternLocator.locatePattern(frame.image(),
                                    TemplatesEnum.FISHING_PAUSE.getTemplate(), new PointData(645, 0),
                                    new PointData(720, 85), 90).isFound();
                            if (!active && verifiedMaximum == 0 && OpenCvPatternLocator.locatePattern(frame.image(),
                                    TemplatesEnum.FISHING_TITLE.getTemplate(), new PointData(85, 0), new PointData(500, 80), 90).isFound()) {
                                int observedMaximum = readSetupMaximum(ocr, frame.image());
                                if (observedMaximum > 0) {
                                    setupConfirmations = observedMaximum == setupCandidate ? setupConfirmations + 1 : 1;
                                    setupCandidate = observedMaximum;
                                    if (setupConfirmations >= 2) verifiedMaximum = setupCandidate;
                                } else setupConfirmations = 0;
                            }
                            Depth observed = active ? read(ocr, frame.image()) : null;
                            Depth depth = filter.accept(matchesMaximum(observed, verifiedMaximum)
                                    ? observed : null, frame.sequence(), frame.receivedNanos());
                            if (active) {
                                try {
                                    var observedHaul = haulFilter.accept(capacityOcr.recognize(frame.image(),
                                            CommonGameAreas.FISHING_CAPACITY_HUD), frame.sequence(), frame.receivedNanos());
                                    if (observedHaul != null && !stopping) haul = observedHaul;
                                } catch (Exception ignored) {
                                    // An unreadable haul must not discard independently valid depth evidence.
                                }
                            }
                            if (depth != null && !stopping) {
                                peak = filter.peak();
                                exhausted = filter.lineExhausted();
                                sufficient = filter.sufficientDescent();
                                latest = new Reading(depth, frame.sequence(), frame.receivedNanos());
                            }
                        } catch (Exception ignored) {
                            // A failed read cannot confirm exhaustion or change steering phase.
                        }
                    }
                    try { Thread.sleep(200); } catch (InterruptedException error) { break; }
                }
            } catch (RuntimeException | LinkageError error) {
                if (!stopping) failure = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
        }, "FishingDepthMonitor");
        worker.setDaemon(true);
        worker.start();
    }

    boolean ready() { return ready; }

    static boolean matchesMaximum(Depth depth, int independentlyVerifiedMaximum) {
        return depth != null && (independentlyVerifiedMaximum == 0 || depth.maximum() == independentlyVerifiedMaximum);
    }

    static int readSetupMaximum(TesseractOcrSession session, dev.frostguard.api.domain.RawImageData frame)
            throws dev.frostguard.vision.ocr.OcrException {
        var maximum = field(session.recognize(frame, CommonGameAreas.FISHING_LINE_LENGTH));
        return maximum != null && maximum > 0 && maximum <= 10000 ? maximum : 0;
    }

    static Depth parse(String text) {
        if (text == null) return null;
        var matcher = DEPTH.matcher(text.trim());
        if (!matcher.matches()) return null;
        try {
            int current = Integer.parseInt(matcher.group(1)), maximum = Integer.parseInt(matcher.group(2));
            return current >= 0 && current <= maximum && maximum > 0 && maximum <= 10000
                    ? new Depth(current, maximum) : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    static Depth read(TesseractOcrSession session, dev.frostguard.api.domain.RawImageData frame)
            throws dev.frostguard.vision.ocr.OcrException {
        // Current depth is white; the static maximum is cyan and must retain its own background.
        String current = session.recognizeForeground(frame, CommonGameAreas.FISHING_CURRENT_DEPTH,
                CommonOCRSettings.FISHING_CURRENT_DEPTH_SETTINGS.targetColor(),
                CommonOCRSettings.FISHING_CURRENT_DEPTH_SETTINGS.textLayout());
        var depth = parseFields(current, session.recognize(frame, CommonGameAreas.FISHING_MAXIMUM_DEPTH));
        if (depth == null) return null;
        // Missing units can also accompany lost leading digits; require independent preprocessing agreement.
        if (!current.trim().matches(".*[mM]$")) {
            var raw = field(session.recognize(frame, CommonGameAreas.FISHING_CURRENT_DEPTH));
            if (raw == null || raw != depth.current()) return null;
        }
        return depth;
    }

    static Depth parseFields(String currentText, String maximumText) {
        Integer current = field(currentText), maximum = field(maximumText);
        return current != null && maximum != null && current >= 0 && current <= maximum
                && maximum > 0 && maximum <= 10000 ? new Depth(current, maximum) : null;
    }

    private static Integer field(String text) {
        if (text == null) return null;
        var matcher = DEPTH_FIELD.matcher(text.trim());
        if (!matcher.matches()) return null;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException invalid) { return null; }
    }

    boolean lineExhausted() { return exhausted; }
    boolean sufficientDescent() { return sufficient; }
    int peak() { return peak; }
    Reading latest() { return latest; }
    FishingHaulTracker.Reading haul() { return haul; }
    int verifiedMaximum() { return verifiedMaximum; }
    String failure() { return failure; }

    @Override
    public void close() {
        stopping = true;
        worker.interrupt();
        try { worker.join(1000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
