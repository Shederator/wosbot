package dev.frostguard.tasks.economy;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the re-review of {@code readCurrentStockpiles}/{@code isFieldFresh}: per-field
 * staleness (a stale field is dropped, not the whole cache), a missing/malformed/future timestamp
 * is trusted once and then re-stamped so it can't bypass staleness forever, and a legacy shared
 * timestamp is still honored as a fallback when a field's own per-field timestamp was never
 * written.
 */
class GatherRoutineStockpileFreshnessTest {

    @BeforeAll
    static void createsRuntimeWorkspaceBeforeRoutineConstruction() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    private AccountDescriptor profile() {
        return new AccountDescriptor(1L, "Test", "1", true, 1L, 30L);
    }

    private GatherRoutine routine() {
        return new GatherRoutine(profile(), TpDailyTaskEnum.GATHER_RESOURCES);
    }

    @Test
    void neverPopulatedCacheReturnsNull() {
        GatherRoutine routine = routine();
        assertNull(routine.readCurrentStockpiles());
    }

    @Test
    void freshPerFieldTimestampsAreAllIncluded() {
        AccountDescriptor profile = profile();
        String now = LocalDateTime.now().toString();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, 100L);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, now);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG, 200L);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LAST_READ_STRING, now);
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        Map<GatherRoutine.GatherType, Long> stockpiles = routine.readCurrentStockpiles();

        assertEquals(100L, stockpiles.get(GatherRoutine.GatherType.MEAT));
        assertEquals(200L, stockpiles.get(GatherRoutine.GatherType.WOOD));
    }

    @Test
    void oneStaleFieldIsDroppedWithoutDiscardingTheOthers() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, 100L);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING,
                LocalDateTime.now().toString()); // fresh
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG, 200L);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LAST_READ_STRING,
                LocalDateTime.now().minusHours(4).toString()); // stale (> 3h)
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        Map<GatherRoutine.GatherType, Long> stockpiles = routine.readCurrentStockpiles();

        assertEquals(100L, stockpiles.get(GatherRoutine.GatherType.MEAT));
        assertFalse(stockpiles.containsKey(GatherRoutine.GatherType.WOOD),
                "the stale field should be dropped, not the whole cache");
    }

    @Test
    void allFieldsStaleReturnsNullLikeNeverPopulated() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, 100L);
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING,
                LocalDateTime.now().minusHours(4).toString());
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        assertNull(routine.readCurrentStockpiles());
    }

    @Test
    void missingTimestampIsTrustedOnceThenReStampedSoItIsNotTrustedForever() {
        AccountDescriptor profile = profile();
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        boolean firstPass = routine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING);
        assertTrue(firstPass, "a legacy cache with no timestamp should be trusted once");

        String stamped = profile.getConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, String.class);
        assertFalse(stamped == null || stamped.isBlank(), "the field should be re-stamped after being trusted once");
    }

    @Test
    void futureTimestampIsNotTreatedAsPermanentlyFresh() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING,
                LocalDateTime.now().plusDays(1).toString());
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        boolean firstPass = routine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING);
        assertTrue(firstPass, "trusted once even with a future timestamp, rather than discarding real data");

        String stamped = profile.getConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, String.class);
        assertFalse(LocalDateTime.parse(stamped).isAfter(LocalDateTime.now()),
                "the future timestamp should be corrected to a real 'now' baseline");
    }

    @Test
    void malformedTimestampIsTrustedOnceThenReStamped() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, "not-a-real-timestamp");
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        boolean firstPass = routine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING);
        assertTrue(firstPass);

        String stamped = profile.getConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, String.class);
        // Should now parse cleanly -- proof it was overwritten with a real timestamp.
        LocalDateTime.parse(stamped);
    }

    @Test
    void fallsBackToTheLegacySharedTimestampWhenThePerFieldKeyWasNeverWritten() {
        AccountDescriptor profile = profile();
        // Only the old shared key exists (a cache from before per-field timestamps existed),
        // and it's fresh.
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_LAST_READ_STRING, LocalDateTime.now().toString());
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        boolean fresh = routine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING);

        assertTrue(fresh, "should fall back to the legacy shared timestamp when the per-field one is unwritten");
    }

    /**
     * Every re-stamp path must arm persistence, not just mutate the descriptor.
     *
     * <p>{@code profile.setConfig} writes only to the in-memory {@link AccountDescriptor}.
     * {@code DelayedTask.run()} reloads the profile from the database before each task and
     * persists changed settings only when {@code shouldUpdateConfig} is set, so a re-stamp that
     * does not arm it is silently discarded -- and the "trust this once" branch it was meant to
     * close then recurs on every pass, indefinitely.</p>
     *
     * <p>The sibling tests above assert the descriptor value and therefore cannot see this: the
     * value reads back correctly in-process and is lost only at the next reload. This asserts the
     * arming flag itself, which is the part that survives the round trip.</p>
     */
    @Test
    void everyReStampPathArmsConfigPersistence() {
        // 1. legacy cache -- no timestamp anywhere for this field
        AccountDescriptor legacy = profile();
        GatherRoutine legacyRoutine = new GatherRoutine(legacy, TpDailyTaskEnum.GATHER_RESOURCES);
        assertTrue(legacyRoutine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING));
        assertTrue(legacyRoutine.isShouldUpdateConfig(),
                "re-stamping a legacy cache must persist, or the same ungoverned trust repeats forever");

        // 2. timestamp in the future (clock skew or a bad write)
        AccountDescriptor future = profile();
        future.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING,
                LocalDateTime.now().plusDays(1).toString());
        GatherRoutine futureRoutine = new GatherRoutine(future, TpDailyTaskEnum.GATHER_RESOURCES);
        assertTrue(futureRoutine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING));
        assertTrue(futureRoutine.isShouldUpdateConfig(),
                "correcting a future timestamp must persist, or it stays permanently fresh");

        // 3. unparseable timestamp
        AccountDescriptor malformed = profile();
        malformed.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, "not-a-real-timestamp");
        GatherRoutine malformedRoutine = new GatherRoutine(malformed, TpDailyTaskEnum.GATHER_RESOURCES);
        assertTrue(malformedRoutine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING));
        assertTrue(malformedRoutine.isShouldUpdateConfig(),
                "correcting a malformed timestamp must persist, or it bypasses staleness forever");
    }

    /** A field that is genuinely fresh changes nothing, so it must not arm a pointless DB write. */
    @Test
    void aFreshFieldDoesNotArmConfigPersistence() {
        AccountDescriptor profile = profile();
        profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING,
                LocalDateTime.now().minusMinutes(1).toString());
        GatherRoutine routine = new GatherRoutine(profile, TpDailyTaskEnum.GATHER_RESOURCES);

        assertTrue(routine.isFieldFresh(GatherRoutine.GatherType.MEAT, 100L,
                ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING));
        assertFalse(routine.isShouldUpdateConfig(),
                "nothing was re-stamped, so no config write should be queued");
    }
}
