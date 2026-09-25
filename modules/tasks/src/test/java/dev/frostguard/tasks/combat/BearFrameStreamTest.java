package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class BearFrameStreamTest {

    @Test
    void everyObservationHasASequenceAndPostconditionsUseALaterFrame() {
        Deque<BearNavigationPolicy.Screen> frames = new ArrayDeque<>();
        frames.add(BearNavigationPolicy.Screen.TRANSITIONING);
        frames.add(BearNavigationPolicy.Screen.ALLIANCE_MENU);
        BearFrameStream<BearNavigationPolicy.Screen> stream = new BearFrameStream<>(
                frames::removeFirst,
                screen -> screen,
                () -> false);

        BearFrameStream.Snapshot<BearNavigationPolicy.Screen> first = stream.next();
        BearFrameStream.Snapshot<BearNavigationPolicy.Screen> destination = stream.awaitAfter(
                first.sequence(),
                Duration.ofMillis(100),
                frame -> frame.screen() == BearNavigationPolicy.Screen.ALLIANCE_MENU).orElseThrow();

        assertTrue(destination.sequence() > first.sequence());
        assertEquals(BearNavigationPolicy.Screen.ALLIANCE_MENU, destination.screen());
    }

    @Test
    void interruptedPostconditionPollCannotCauseASecondTap() {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger taps = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> stream = new BearFrameStream<>(
                () -> {
                    interrupted.set(true);
                    return BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY;
                },
                screen -> screen,
                interrupted::get);
        BearFrameStream.Snapshot<BearNavigationPolicy.Screen> source = new BearFrameStream.Snapshot<>(
                1, BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        BearVerifiedActionExecutor<BearNavigationPolicy.Screen> executor =
                new BearVerifiedActionExecutor<>(stream, 2);

        BearVerifiedActionExecutor.Outcome outcome = executor.tapWithOneVerifiedRetry(
                source,
                taps::incrementAndGet,
                frame -> frame.screen() == BearNavigationPolicy.Screen.WORLD_AT_BEAR,
                frame -> frame.screen() == BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);

        assertEquals(BearVerifiedActionExecutor.Outcome.INTERRUPTED, outcome);
        assertEquals(1, taps.get());
    }

    @Test
    void secondTapRequiresLaterFrameProofThatTheSameTargetIsStillPresent() {
        Deque<BearNavigationPolicy.Screen> frames = new ArrayDeque<>();
        frames.add(BearNavigationPolicy.Screen.TRANSITIONING);
        frames.add(BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        frames.add(BearNavigationPolicy.Screen.WORLD_AT_BEAR);
        AtomicInteger taps = new AtomicInteger();
        BearFrameStream<BearNavigationPolicy.Screen> stream = new BearFrameStream<>(
                frames::removeFirst,
                screen -> screen,
                () -> false);
        BearFrameStream.Snapshot<BearNavigationPolicy.Screen> source = new BearFrameStream.Snapshot<>(
                1, BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY,
                BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);
        BearVerifiedActionExecutor<BearNavigationPolicy.Screen> executor =
                new BearVerifiedActionExecutor<>(stream, 2);

        BearVerifiedActionExecutor.Outcome outcome = executor.tapWithOneVerifiedRetry(
                source,
                taps::incrementAndGet,
                frame -> frame.screen() == BearNavigationPolicy.Screen.WORLD_AT_BEAR,
                frame -> frame.screen() == BearNavigationPolicy.Screen.WORLD_ACTIVE_BEAR_ICON_READY);

        assertEquals(BearVerifiedActionExecutor.Outcome.CONFIRMED, outcome);
        assertEquals(2, taps.get());
    }
}
