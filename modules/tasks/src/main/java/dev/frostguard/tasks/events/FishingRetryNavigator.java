package dev.frostguard.tasks.events;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.engine.emulator.AndroidFrameStream;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import java.io.IOException;

/** Shared live/test navigation: suspension never selects a replacement Normal Cast. */
final class FishingRetryNavigator {
    interface Port {
        AndroidFrameStream.Frame frame();
        void fresh();
        void release() throws IOException;
        void tap(AreaData area);
        void checkCancellation();
    }
    private static final AreaData PAUSE = AreaData.of(645, 0, 720, 85);
    private static final AreaData CONTINUE = AreaData.of(390, 570, 535, 710);
    private static final AreaData EXIT = AreaData.of(185, 570, 330, 710);
    private static final AreaData GO_FISH = AreaData.of(270, 1040, 465, 1130);
    private static final AreaData TITLE = AreaData.of(85, 0, 500, 80);

    private FishingRetryNavigator() { }

    static void suspend(Port port) throws IOException {
        port.checkCancellation();
        port.release();
        port.fresh();
        tapVerified(port, TemplatesEnum.FISHING_PAUSE, PAUSE);
        await(port, TemplatesEnum.FISHING_CONTINUE, CONTINUE);
        tapVerified(port, TemplatesEnum.FISHING_PAUSE_EXIT, EXIT);
        await(port, TemplatesEnum.FISHING_GO_FISH, GO_FISH);
        if (!hit(port, TemplatesEnum.FISHING_TITLE, TITLE))
            throw new IllegalStateException("Suspended Fishing page unverified; no replacement bait authorized");
    }

    static void resume(Port port) {
        port.checkCancellation();
        port.fresh();
        if (!hit(port, TemplatesEnum.FISHING_TITLE, TITLE))
            throw new IllegalStateException("Suspended Fishing title unverified");
        tapVerified(port, TemplatesEnum.FISHING_GO_FISH, GO_FISH);
        await(port, TemplatesEnum.FISHING_PAUSE, PAUSE);
    }

    private static boolean hit(Port port, TemplatesEnum template, AreaData area) {
        return OpenCvPatternLocator.locatePattern(port.frame().image(), template.getTemplate(),
                area.topLeft(), area.bottomRight(), 90).isFound();
    }

    private static void tapVerified(Port port, TemplatesEnum template, AreaData area) {
        var match = OpenCvPatternLocator.locatePattern(port.frame().image(), template.getTemplate(),
                area.topLeft(), area.bottomRight(), 90);
        if (!match.isFound() || !match.hasMatchedArea())
            throw new IllegalStateException("Fishing retry control unverified: " + template);
        port.checkCancellation();
        long age = System.nanoTime() - port.frame().receivedNanos();
        if (age < 0 || age > 250_000_000L) throw new IllegalStateException("Fishing retry frame stale; no input sent");
        port.tap(match.getMatchedArea());
    }

    private static void await(Port port, TemplatesEnum template, AreaData area) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            port.checkCancellation();
            port.fresh();
            if (hit(port, template, area)) return;
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fishing retry control timed out: " + template);
    }
}
