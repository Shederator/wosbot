package dev.frostguard.data.repository;

import dev.frostguard.api.domain.TaskFailureStreakData;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.TaskFailureStreak;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class TaskFailureStreakRepository {

    private static volatile TaskFailureStreakRepository instance;

    private final DataStore store;

    public TaskFailureStreakRepository(DataStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public static TaskFailureStreakRepository getRepository() {
        TaskFailureStreakRepository current = instance;
        if (current != null) {
            return current;
        }
        synchronized (TaskFailureStreakRepository.class) {
            if (instance == null) {
                instance = new TaskFailureStreakRepository(DataStore.getInstance());
            }
            return instance;
        }
    }

    public TaskFailureStreakData recordFailure(
            long profileId, String taskKey, String signature, LocalDateTime failedAt) {
        return store.withinTransaction(entityManager -> {
            List<String> matches = entityManager.createQuery(
                            "SELECT s.id FROM TaskFailureStreak s "
                                    + "WHERE s.profileId = :profileId AND s.taskKey = :taskKey",
                            String.class)
                    .setParameter("profileId", profileId)
                    .setParameter("taskKey", taskKey)
                    .getResultList();
            TaskFailureStreak streak;
            if (matches.isEmpty()) {
                streak = TaskFailureStreak.firstFailure(profileId, taskKey, signature, failedAt);
                entityManager.persist(streak);
            } else {
                streak = entityManager.find(TaskFailureStreak.class, matches.getFirst());
                streak.record(signature, failedAt);
            }
            entityManager.flush();
            return streak.toData();
        });
    }

    public boolean clear(long profileId, String taskKey) {
        return store.withinTransaction(entityManager -> {
            List<String> matches = entityManager.createQuery(
                            "SELECT s.id FROM TaskFailureStreak s "
                                    + "WHERE s.profileId = :profileId AND s.taskKey = :taskKey",
                            String.class)
                    .setParameter("profileId", profileId)
                    .setParameter("taskKey", taskKey)
                    .getResultList();
            matches.stream()
                    .map(id -> entityManager.find(TaskFailureStreak.class, id))
                    .forEach(entityManager::remove);
            return !matches.isEmpty();
        });
    }
}
