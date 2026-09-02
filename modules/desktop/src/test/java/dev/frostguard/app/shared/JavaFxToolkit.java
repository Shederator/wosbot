package dev.frostguard.app.shared;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One JavaFX toolkit per surefire JVM, shared by every UX test.
 *
 * <p>The toolkit is process-wide and cannot be restarted: a second
 * {@code Platform.startup} throws "Toolkit already initialized", and a
 * {@code Platform.exit} in one test class leaves every class scheduled after it
 * failing with "Platform.exit has been called". A per-class {@code @BeforeAll}
 * latch is therefore only correct while exactly one class needs a toolkit, which
 * stopped being true when the arena target filters grew a UX test of their
 * own.</p>
 *
 * <p>Start through here and never exit — the forked JVM is torn down by surefire
 * when the run finishes.</p>
 */
public final class JavaFxToolkit {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private JavaFxToolkit() {
    }

    public static void start() throws InterruptedException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }
}
