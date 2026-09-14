package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarSection;

class SidebarNavigatorOpenPolicyTest {

    @Test
    void allowsSidebarAnimationToFinishBeforeClassifyingIt() {
        assertEquals(2_000, SidebarNavigator.SIDEBAR_OPEN_SETTLE_MS);
    }

    @Test
    void downwardScanUsesShortOverlappingGesturesAndWaitsForTheList() {
        assertEquals(SidebarNavigator.SCROLL_DISTANCE_PX,
                Math.abs(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM.getY()
                        - CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_TO.getY()));
        assertEquals(2_000, SidebarNavigator.SCROLL_SETTLE_MS);
    }

    @Test
    void collapsedTriggerStaysInsideTheThinWorldHandle() {
        assertEquals(6, CommonGameAreas.LEFT_MENU_TRIGGER.topLeft().getX());
        assertEquals(546, CommonGameAreas.LEFT_MENU_TRIGGER.topLeft().getY());
        assertEquals(16, CommonGameAreas.LEFT_MENU_TRIGGER.bottomRight().getX());
        assertEquals(554, CommonGameAreas.LEFT_MENU_TRIGGER.bottomRight().getY());
    }

    @Test
    void everyDestinationUsesARealLeftIconRatherThanTheSharedGoArrow() {
        for (SidebarDestination destination : SidebarDestination.values()) {
            assertNotEquals(TemplatesEnum.GAME_HOME_SHORTCUTS_GO, destination.rowIcon(), destination.name());
            assertTrue(destination.actions().length > 0, destination.name());
        }
    }

    @Test
    void acceptsASectionThatAppearsAfterTransientUnknownFrames() {
        Queue<Optional<SidebarSection>> frames = new ArrayDeque<>(List.of(
                Optional.empty(), Optional.empty(), Optional.of(SidebarSection.WILDERNESS)));
        AtomicInteger waits = new AtomicInteger();

        SidebarNavigator.TransitionObservation observation = SidebarNavigator.awaitSection(
                Optional::isPresent, frames::remove, milliseconds -> {
                    assertEquals(SidebarNavigator.TRANSITION_POLL_MS, milliseconds);
                    waits.incrementAndGet();
                    return true;
                });

        assertEquals(Optional.of(SidebarSection.WILDERNESS), observation.section());
        assertEquals(3, observation.checks());
        assertEquals(2, waits.get());
    }

    @Test
    void waitsForTheRequestedSectionInsteadOfAcceptingThePreviousTab() {
        Queue<Optional<SidebarSection>> frames = new ArrayDeque<>(List.of(
                Optional.of(SidebarSection.DAILY),
                Optional.empty(),
                Optional.of(SidebarSection.CITY)));

        SidebarNavigator.TransitionObservation observation = SidebarNavigator.awaitSection(
                section -> section.orElse(null) == SidebarSection.CITY,
                frames::remove, milliseconds -> true);

        assertEquals(Optional.of(SidebarSection.CITY), observation.section());
        assertEquals(3, observation.checks());
    }

    @Test
    void stopsAfterTheBoundedPollingWindowWhenThePanelStaysUnknown() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();

        SidebarNavigator.TransitionObservation observation = SidebarNavigator.awaitSection(
                Optional::isPresent, () -> {
                    reads.incrementAndGet();
                    return Optional.empty();
                }, milliseconds -> {
                    waits.incrementAndGet();
                    return true;
                });

        assertTrue(observation.section().isEmpty());
        assertEquals(SidebarNavigator.TRANSITION_POLL_CHECKS, observation.checks());
        assertEquals(SidebarNavigator.TRANSITION_POLL_CHECKS, reads.get());
        assertEquals(SidebarNavigator.TRANSITION_POLL_CHECKS - 1, waits.get());
        assertEquals(2, SidebarNavigator.MAX_TRIGGER_TAPS);
    }

    @Test
    void interruptionStopsPollingWithoutAnotherWait() {
        AtomicInteger reads = new AtomicInteger();

        SidebarNavigator.TransitionObservation observation = SidebarNavigator.awaitSection(
                Optional::isPresent, () -> {
                    reads.incrementAndGet();
                    return Optional.empty();
                }, milliseconds -> false);

        assertTrue(observation.section().isEmpty());
        assertEquals(1, observation.checks());
        assertEquals(1, reads.get());
        assertTrue(observation.interrupted());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void retriesOnlyTheFirstUnconfirmedTriggerOnAFreshRootScreen() {
        assertTrue(SidebarNavigator.shouldRetryTrigger(1,
                new SidebarNavigator.TransitionObservation(Optional.empty(), 1, false), Optional.empty(), true));
        assertFalse(SidebarNavigator.shouldRetryTrigger(2,
                new SidebarNavigator.TransitionObservation(Optional.empty(), 2, false),
                Optional.empty(), true));
        assertFalse(SidebarNavigator.shouldRetryTrigger(1,
                new SidebarNavigator.TransitionObservation(Optional.empty(), 1, false),
                Optional.of(SidebarSection.DAILY), true));
        assertFalse(SidebarNavigator.shouldRetryTrigger(1,
                new SidebarNavigator.TransitionObservation(Optional.empty(), 1, false), Optional.empty(), false));
        assertFalse(SidebarNavigator.shouldRetryTrigger(1,
                new SidebarNavigator.TransitionObservation(Optional.empty(), 1, true),
                Optional.empty(), true));
    }
}
