package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;

class StorehouseChestRoutineConfigurationTest {

    private static final Path ROUTINE_SOURCE = Path.of(
            "src/main/java/dev/frostguard/tasks/economy/StorehouseChestRoutine.java");

    @Test
    void configuresOneTapInsideTheMeasuredChestRewardCloseArea() throws IOException {
        assertEquals(AreaData.of(565, 530, 715, 705), StorehouseChestRoutine.CHEST_REWARD_OVERLAY_CLOSE_AREA);

        String source = Files.readString(ROUTINE_SOURCE);
        long safeAreaTapCalls = Pattern.compile("tapInside\\(CHEST_REWARD_OVERLAY_CLOSE_AREA\\);")
                .matcher(source)
                .results()
                .count();

        assertEquals(1, safeAreaTapCalls);
        assertFalse(source.contains("STOREHOUSE_SCROLL_START"));
        assertFalse(source.contains("STOREHOUSE_SCROLL_END"));
    }
}
