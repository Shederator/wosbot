package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;
import dev.frostguard.engine.schedule.LaunchPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildingNavigationTest {

    private static final AccountDescriptor PROFILE =
            new AccountDescriptor(999L, "Synthetic building navigation", "review", true, 0L, 0L);

    @Test
    void opensBakeryAndLeavesItUnfocused() {
        RecordingNavigation navigation = new RecordingNavigation(true);

        assertTrue(navigation.navigateToBuilding(BuildingDestination.BAKERY));
        assertEquals(List.of("home", "survivor", "wait", "reset-bakery", "wait", "entry:BAKERY", "wait",
                "go:BAKERY", "wait", "unfocus:BAKERY", "wait"), navigation.actions);
    }

    @Test
    void resetsTheSurvivorListBeforeBakeryAfterFarmNavigation() {
        RecordingNavigation navigation = new RecordingNavigation(true);

        assertTrue(navigation.navigateToBuilding(BuildingDestination.FARM));
        assertTrue(navigation.navigateToBuilding(BuildingDestination.BAKERY));
        assertEquals(List.of("home", "survivor", "wait", "scroll-farm", "wait", "farm-ocr",
                "entry:FARM", "wait", "go:FARM", "wait", "unfocus:FARM", "wait",
                "home", "survivor", "wait", "reset-bakery", "wait", "entry:BAKERY", "wait",
                "go:BAKERY", "wait", "unfocus:BAKERY", "wait"), navigation.actions);
    }

    @Test
    void scrollsAndConfirmsFarmBeforeOpeningIt() {
        RecordingNavigation navigation = new RecordingNavigation(true);

        assertTrue(navigation.navigateToBuilding(BuildingDestination.FARM));
        assertEquals(List.of("home", "survivor", "wait", "scroll-farm", "wait", "farm-ocr",
                "entry:FARM", "wait", "go:FARM", "wait", "unfocus:FARM", "wait"),
                navigation.actions);
    }

    @Test
    void closesSurvivorStatusAndStopsWhenFarmCannotBeConfirmed() {
        RecordingNavigation navigation = new RecordingNavigation(false);

        assertFalse(navigation.navigateToBuilding(BuildingDestination.FARM));
        assertEquals(List.of("home", "survivor", "wait", "scroll-farm", "wait", "farm-ocr", "back"),
                navigation.actions);
    }

    private static final class RecordingNavigation extends NavigationHelper {
        private final boolean farmVisible;
        private final List<String> actions = new ArrayList<>();

        private RecordingNavigation(boolean farmVisible) {
            super(null, "review", PROFILE);
            this.farmVisible = farmVisible;
        }

        @Override
        public void ensureCorrectScreenLocation(LaunchPoint target) {
            assertEquals(LaunchPoint.HOME, target);
            actions.add("home");
        }

        @Override
        void tapSurvivorStatusEntry() {
            actions.add("survivor");
        }

        @Override
        void swipeToFarmEntry() {
            actions.add("scroll-farm");
        }

        @Override
        void swipeToBakeryEntry() {
            actions.add("reset-bakery");
        }

        @Override
        boolean isFarmEntryVisible() {
            actions.add("farm-ocr");
            return farmVisible;
        }

        @Override
        void tapBuildingEntry(BuildingDestination destination) {
            actions.add("entry:" + destination.name());
        }

        @Override
        void tapBuildingGo(BuildingDestination destination) {
            actions.add("go:" + destination.name());
        }

        @Override
        void tapBuildingUnfocusArea(BuildingDestination destination) {
            actions.add("unfocus:" + destination.name());
        }

        @Override
        void pressBackFromSurvivorStatus() {
            actions.add("back");
        }

        @Override
        void waitForBuildingTransition() {
            actions.add("wait");
        }
    }
}
