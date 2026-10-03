package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;

class StorehouseStaminaClaimFlowTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final ImageSearchResultData CLAIM_BUTTON = ImageSearchResultData.hit(350, 955, 100);

    @Test
    void dismissesConfirmedTooltipOnceThenReacquiresRewardAndClaimBeforeReturningButton() {
        ScriptedActions actions = new ScriptedActions()
                .rewardDialog(false, true)
                .claimButtons(CLAIM_BUTTON)
                .tooltips(true);

        StorehouseStaminaClaimFlow.Result result = StorehouseStaminaClaimFlow.awaitClaimButton(
                actions, ONE_SECOND, actions.clock::get);

        assertNotNull(result.claimButton());
        assertSame(CLAIM_BUTTON, result.claimButton());
        assertEquals(1, actions.backPresses);
        assertEquals(2, actions.rewardDialogChecks);
        assertEquals(1, actions.claimButtonChecks);
        assertEquals(1, actions.tooltipChecks);
        assertEquals(1, actions.waitsAfterBack);
        assertEquals(0, actions.regularWaits);
    }

    @Test
    void doesNotPressBackWithoutConfirmedTooltipAndReturnsUnknownAfterTimeout() {
        ScriptedActions actions = new ScriptedActions()
                .rewardDialog(true, true, true)
                .claimButtons(missed(), missed(), missed())
                .tooltips(false, false, false);

        StorehouseStaminaClaimFlow.Result result = StorehouseStaminaClaimFlow.awaitClaimButton(
                actions, ONE_SECOND, actions.clock::get);

        assertNull(result.claimButton());
        assertTrue(result.rewardDialogSeen());
        assertEquals(0, actions.backPresses);
        assertEquals(3, actions.rewardDialogChecks);
        assertEquals(3, actions.claimButtonChecks);
        assertEquals(3, actions.tooltipChecks);
        assertEquals(0, actions.waitsAfterBack);
        assertEquals(3, actions.regularWaits);
    }

    @Test
    void doesNotRepeatBackWhenTooltipRemainsAfterTheSingleDismissAttempt() {
        ScriptedActions actions = new ScriptedActions()
                .rewardDialog(false, false, false)
                .tooltips(true);

        StorehouseStaminaClaimFlow.Result result = StorehouseStaminaClaimFlow.awaitClaimButton(
                actions, ONE_SECOND, actions.clock::get);

        assertNull(result.claimButton());
        assertFalse(result.rewardDialogSeen());
        assertEquals(1, actions.backPresses);
        assertEquals(4, actions.rewardDialogChecks);
        assertEquals(1, actions.tooltipChecks);
        assertEquals(1, actions.waitsAfterBack);
        assertEquals(3, actions.regularWaits);
    }

    private static ImageSearchResultData missed() {
        return new ImageSearchResultData(false, null, 0);
    }

    private static final class ScriptedActions implements StorehouseStaminaClaimFlow.Actions {
        private final AtomicLong clock = new AtomicLong();
        private final Deque<Boolean> rewardDialogVisible = new ArrayDeque<>();
        private final Deque<ImageSearchResultData> claimButtons = new ArrayDeque<>();
        private final Deque<Boolean> tooltipVisible = new ArrayDeque<>();
        private int rewardDialogChecks;
        private int claimButtonChecks;
        private int tooltipChecks;
        private int backPresses;
        private int waitsAfterBack;
        private int regularWaits;

        ScriptedActions rewardDialog(boolean... visible) {
            for (boolean value : visible) {
                rewardDialogVisible.add(value);
            }
            return this;
        }

        ScriptedActions claimButtons(ImageSearchResultData... buttons) {
            for (ImageSearchResultData button : buttons) {
                claimButtons.add(button);
            }
            return this;
        }

        ScriptedActions tooltips(boolean... visible) {
            for (boolean value : visible) {
                tooltipVisible.add(value);
            }
            return this;
        }

        @Override
        public boolean isRewardDialogVisible() {
            rewardDialogChecks++;
            return rewardDialogVisible.isEmpty() ? false : rewardDialogVisible.removeFirst();
        }

        @Override
        public ImageSearchResultData findClaimButton() {
            claimButtonChecks++;
            return claimButtons.isEmpty() ? null : claimButtons.removeFirst();
        }

        @Override
        public boolean isTooltipVisible() {
            tooltipChecks++;
            return tooltipVisible.isEmpty() ? false : tooltipVisible.removeFirst();
        }

        @Override
        public void pressBack() {
            backPresses++;
        }

        @Override
        public void waitAfterBack() {
            waitsAfterBack++;
            clock.addAndGet(Duration.ofMillis(100).toNanos());
        }

        @Override
        public void waitForNextPoll() {
            regularWaits++;
            clock.addAndGet(Duration.ofMillis(400).toNanos());
        }
    }
}
