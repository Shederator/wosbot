package dev.frostguard.tasks.combat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe candidate deduplication TTL cache for Bear Trap rally join requests.
 */
public class BearRallyDedupCache {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(300);
    private static final int DEFAULT_MAX_ENTRIES = 256;
    private static final Duration CLOCK_ROLLBACK_TOLERANCE = Duration.ofSeconds(60);
    private final Map<CacheKey, Instant> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private Instant lastObservedTime;

    public record Scope(String profileId, String activityInstanceId) {
        public Scope {
            if (profileId == null || profileId.isBlank() || activityInstanceId == null || activityInstanceId.isBlank()) {
                throw new IllegalArgumentException("Profile and activity instance are required");
            }
        }
    }

    private record CacheKey(Scope scope, String candidateKey) {}

    public BearRallyDedupCache() {
        this(Clock.systemUTC(), DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    public BearRallyDedupCache(Clock clock, Duration ttl) {
        this(clock, ttl, DEFAULT_MAX_ENTRIES);
    }

    public BearRallyDedupCache(Clock clock, Duration ttl, int maxEntries) {
        if (clock == null || ttl == null || ttl.isZero() || ttl.isNegative() || maxEntries <= 0) {
            throw new IllegalArgumentException("Clock, positive TTL, and positive capacity are required");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.lastObservedTime = clock.instant();
    }

    public synchronized boolean isDuplicate(Scope scope, String key) {
        Objects.requireNonNull(scope, "scope");
        if (key == null || key.isBlank()) {
            return false;
        }

        Instant now = observeTime();
        cleanExpired(now);
        CacheKey scopedKey = new CacheKey(scope, key);
        Instant expiry = cache.get(scopedKey);
        if (expiry == null) {
            return false;
        }

        if (!now.isBefore(expiry)) {
            cache.remove(scopedKey);
            return false;
        }

        return true;
    }

    public synchronized void markJoined(Scope scope, String key) {
        Objects.requireNonNull(scope, "scope");
        if (key == null || key.isBlank()) {
            return;
        }

        Instant now = observeTime();
        cleanExpired(now);
        CacheKey cacheKey = new CacheKey(scope, key);
        if (!cache.containsKey(cacheKey) && cache.size() >= maxEntries) {
            cache.keySet().stream().findFirst().ifPresent(cache::remove);
        }
        cache.put(cacheKey, now.plus(ttl));
    }

    public synchronized void clearScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        cache.keySet().removeIf(key -> key.scope().equals(scope));
    }

    public synchronized void clear() {
        cache.clear();
    }

    synchronized int size() {
        return cache.size();
    }

    static Set<String> uniqueCandidateKeys(List<BearRallyCandidate> candidates) {
        Map<String, Integer> counts = new HashMap<>();
        if (candidates != null) {
            for (BearRallyCandidate candidate : candidates) {
                if (candidate != null) {
                    counts.merge(candidate.getCandidateKey(), 1, Integer::sum);
                }
            }
        }
        Set<String> uniqueKeys = new HashSet<>();
        counts.forEach((key, count) -> {
            if (count == 1) {
                uniqueKeys.add(key);
            }
        });
        return uniqueKeys;
    }

    static String positionalCandidateKey(BearRallyCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.joinButtonPoint() == null) {
            throw new IllegalArgumentException("Candidate position is required");
        }
        return candidate.getCandidateKey() + ":screenY=" + candidate.joinButtonPoint().getY();
    }

    private Instant observeTime() {
        Instant now = clock.instant();
        if (now.isBefore(lastObservedTime.minus(CLOCK_ROLLBACK_TOLERANCE))) {
            cache.clear();
        }
        lastObservedTime = now;
        return now;
    }

    private void cleanExpired(Instant now) {
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }
}
