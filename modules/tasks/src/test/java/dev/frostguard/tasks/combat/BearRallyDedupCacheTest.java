package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BearRallyDedupCacheTest {

    @Test
    void entryExpiresAtExactTtlBoundary() {
        Instant start = Instant.parse("2026-08-18T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5));
        BearRallyDedupCache.Scope scope = new BearRallyDedupCache.Scope("profile-1", "trap-1");

        cache.markJoined(scope, "host-1");
        assertTrue(cache.isDuplicate(scope, "host-1"));

        clock.instant = start.plus(Duration.ofMinutes(5));
        assertFalse(cache.isDuplicate(scope, "host-1"));
    }

    @Test
    void scopesEntriesByProfileAndActivity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T10:00:00Z"));
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5));
        BearRallyDedupCache.Scope first = new BearRallyDedupCache.Scope("profile-1", "trap-1");
        BearRallyDedupCache.Scope second = new BearRallyDedupCache.Scope("profile-1", "trap-2");

        cache.markJoined(first, "candidate");

        assertTrue(cache.isDuplicate(first, "candidate"));
        assertFalse(cache.isDuplicate(second, "candidate"));
    }

    @Test
    void toleratesSmallClockCorrection() {
        Instant start = Instant.parse("2026-08-18T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5));
        BearRallyDedupCache.Scope scope = new BearRallyDedupCache.Scope("profile-1", "trap-1");
        cache.markJoined(scope, "candidate");

        clock.instant = start.minusSeconds(1);

        assertTrue(cache.isDuplicate(scope, "candidate"));
    }

    @Test
    void clearsConservativelyWhenClockMovesBackwardBeyondTolerance() {
        Instant start = Instant.parse("2026-08-18T10:00:00Z");
        MutableClock clock = new MutableClock(start);
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5));
        BearRallyDedupCache.Scope scope = new BearRallyDedupCache.Scope("profile-1", "trap-1");
        cache.markJoined(scope, "candidate");

        clock.instant = start.minusSeconds(61);

        assertFalse(cache.isDuplicate(scope, "candidate"));
    }

    @Test
    void enforcesMaximumEntryCount() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T10:00:00Z"));
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5), 2);
        BearRallyDedupCache.Scope scope = new BearRallyDedupCache.Scope("profile-1", "trap-1");

        cache.markJoined(scope, "one");
        clock.instant = clock.instant.plusSeconds(1);
        cache.markJoined(scope, "two");
        clock.instant = clock.instant.plusSeconds(1);
        cache.markJoined(scope, "three");

        assertEquals(2, cache.size());
        assertFalse(cache.isDuplicate(scope, "one"));
    }

    @Test
    void evictsLeastRecentlyUsedEntry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-18T10:00:00Z"));
        BearRallyDedupCache cache = new BearRallyDedupCache(clock, Duration.ofMinutes(5), 2);
        BearRallyDedupCache.Scope scope = new BearRallyDedupCache.Scope("profile-1", "trap-1");
        cache.markJoined(scope, "one");
        cache.markJoined(scope, "two");
        assertTrue(cache.isDuplicate(scope, "one"));

        cache.markJoined(scope, "three");

        assertTrue(cache.isDuplicate(scope, "one"));
        assertFalse(cache.isDuplicate(scope, "two"));
    }

    @Test
    void excludesCollidingCardsFromTtlDeduplication() {
        Instant observedAt = Instant.parse("2026-08-18T10:00:00Z");
        BearRallyCandidate first = candidate("SameHost", observedAt, Duration.ofMinutes(4));
        BearRallyCandidate second = candidate("SameHost", observedAt, Duration.ofMinutes(4));

        Set<String> uniqueKeys = BearRallyDedupCache.uniqueCandidateKeys(List.of(first, second));

        assertTrue(uniqueKeys.isEmpty());
    }

    @Test
    void keepsDifferentHostsOrCompletionTimesIndependentlyDeduplicated() {
        Instant observedAt = Instant.parse("2026-08-18T10:00:00Z");
        BearRallyCandidate first = candidate("FirstHost", observedAt, Duration.ofMinutes(4));
        BearRallyCandidate second = candidate("SecondHost", observedAt, Duration.ofMinutes(4));
        BearRallyCandidate third = candidate("FirstHost", observedAt, Duration.ofSeconds(260));

        Set<String> uniqueKeys = BearRallyDedupCache.uniqueCandidateKeys(List.of(first, second, third));

        assertEquals(3, uniqueKeys.size());
    }

    @Test
    void positionalKeysSeparateCollidingCardsAtDifferentScreenRows() {
        Instant observedAt = Instant.parse("2026-08-18T10:00:00Z");
        BearRallyCandidate upper = candidate(
                "SameHost", observedAt, Duration.ofMinutes(4), 300);
        BearRallyCandidate lower = candidate(
                "SameHost", observedAt, Duration.ofMinutes(4), 500);

        assertFalse(BearRallyDedupCache.positionalCandidateKey(upper)
                .equals(BearRallyDedupCache.positionalCandidateKey(lower)));
    }

    private static BearRallyCandidate candidate(String host, Instant observedAt, Duration countdown) {
        return candidate(host, observedAt, countdown, 300);
    }

    private static BearRallyCandidate candidate(
            String host, Instant observedAt, Duration countdown, int buttonY) {
        return new BearRallyCandidate(
                new dev.frostguard.api.domain.PointData(600, buttonY),
                new dev.frostguard.api.domain.AreaData(
                        new dev.frostguard.api.domain.PointData(0, 200),
                        new dev.frostguard.api.domain.PointData(719, 360)),
                host, 2, 6, 50_000L, 100_000L, 50_000L,
                countdown, observedAt, true);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
