package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;

class DelayedTaskSchedulingPrecisionTest {

    @Test
    void positiveSubUnitDelayRoundsUpWithoutChangingZeroOrNegativeDelays() {
        assertEquals(1, DelayedTask.delayInUnits(Duration.ofNanos(1), TimeUnit.MILLISECONDS));
        assertEquals(1, DelayedTask.delayInUnits(Duration.ofNanos(1), TimeUnit.SECONDS));
        assertEquals(0, DelayedTask.delayInUnits(Duration.ZERO, TimeUnit.MILLISECONDS));
        assertTrue(DelayedTask.delayInUnits(Duration.ofNanos(-1), TimeUnit.MILLISECONDS) <= 0);
    }

    @Test
    void rescheduleRetainsExactTargetAndDelaySubsecondPrecision() {
        TestTask task = new TestTask();
        LocalDateTime target = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);

        task.reschedule(target);

        assertEquals(target, task.getScheduled());

        LocalDateTime beforeMillis = LocalDateTime.now();
        long delayMillis = task.getDelay(TimeUnit.MILLISECONDS);
        LocalDateTime afterMillis = LocalDateTime.now();
        LocalDateTime beforeNanos = LocalDateTime.now();
        long delayNanos = task.getDelay(TimeUnit.NANOSECONDS);
        LocalDateTime afterNanos = LocalDateTime.now();

        assertDelayFallsWithinCallWindow(delayMillis, beforeMillis, afterMillis, target, TimeUnit.MILLISECONDS);
        assertDelayFallsWithinCallWindow(delayNanos, beforeNanos, afterNanos, target, TimeUnit.NANOSECONDS);
        assertTrue(delayNanos % TimeUnit.SECONDS.toNanos(1) != 0,
                "nanosecond delay should retain the fractional second before an exact-second target");
    }

    private static void assertDelayFallsWithinCallWindow(long delay, LocalDateTime before, LocalDateTime after,
            LocalDateTime target, TimeUnit unit) {
        long earliestPossible = unit.convert(Duration.between(after, target));
        long latestPossible = unit.convert(Duration.between(before, target));
        assertTrue(delay >= earliestPossible && delay <= latestPossible,
                () -> unit + " delay should match the target within the time spent making the call");
    }

    @Test
    void delayedQueueDoesNotReleaseTaskBeforeScheduledTarget() throws InterruptedException {
        TestTask task = new TestTask();
        task.reschedule(LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(300)));
        DelayQueue<DelayedTask> queue = new DelayQueue<>();
        queue.add(task);

        assertNull(queue.poll());
        assertSame(task, queue.poll(1, TimeUnit.SECONDS));
    }

    private static final class TestTask extends DelayedTask {

        private TestTask() {
            super(profile(), TpDailyTaskEnum.INITIALIZE);
        }

        private static AccountDescriptor profile() {
            AccountDescriptor profile = new AccountDescriptor(1L);
            profile.setEmulatorNumber("1");
            return profile;
        }

        @Override
        protected void execute() {
        }
    }
}
