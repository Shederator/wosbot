package dev.frostguard.data.repository;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.ActionRequiredIncident;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ActionRequiredIncidentRepository {

    private static volatile ActionRequiredIncidentRepository instance;

    private final DataStore store;

    public ActionRequiredIncidentRepository(DataStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public static ActionRequiredIncidentRepository getRepository() {
        ActionRequiredIncidentRepository current = instance;
        if (current != null) {
            return current;
        }
        synchronized (ActionRequiredIncidentRepository.class) {
            if (instance == null) {
                instance = new ActionRequiredIncidentRepository(DataStore.getInstance());
            }
            return instance;
        }
    }

    public ActionRequiredIncidentData recordOccurrence(
            ActionRequiredIncidentReport report, String logExcerpt, LocalDateTime seenAt) {
        Objects.requireNonNull(report);
        Objects.requireNonNull(seenAt);
        return store.withinTransaction(entityManager -> {
            List<String> matches = entityManager.createQuery(
                            "SELECT i.id FROM ActionRequiredIncident i "
                                    + "WHERE i.profileId = :profileId AND i.taskKey = :taskKey "
                                    + "AND i.signature = :signature",
                            String.class)
                    .setParameter("profileId", report.profileId())
                    .setParameter("taskKey", report.taskKey())
                    .setParameter("signature", report.signature())
                    .getResultList();
            ActionRequiredIncident incident;
            if (matches.isEmpty()) {
                incident = ActionRequiredIncident.firstOccurrence(report, logExcerpt, seenAt);
                entityManager.persist(incident);
            } else {
                incident = entityManager.find(ActionRequiredIncident.class, matches.getFirst());
                incident.recordOccurrence(report, logExcerpt, seenAt);
            }
            entityManager.flush();
            return incident.toData();
        });
    }

    public List<ActionRequiredIncidentData> findAll() {
        return store.executeQuery(
                        "SELECT i.id FROM ActionRequiredIncident i ORDER BY i.lastSeenAt DESC",
                        String.class, null).stream()
                .map(id -> store.lookup(ActionRequiredIncident.class, id))
                .map(ActionRequiredIncident::toData)
                .sorted(Comparator
                        .comparing(ActionRequiredIncidentData::state)
                        .thenComparing(ActionRequiredIncidentData::lastSeenAt, Comparator.reverseOrder()))
                .toList();
    }

    public boolean acknowledge(String incidentId, LocalDateTime acknowledgedAt) {
        return store.withinTransaction(entityManager -> {
            ActionRequiredIncident incident = entityManager.find(ActionRequiredIncident.class, incidentId);
            if (incident == null) {
                return false;
            }
            incident.acknowledge(acknowledgedAt);
            return true;
        });
    }

    public int recoverTask(long profileId, String taskKey, LocalDateTime recoveredAt) {
        return store.withinTransaction(entityManager -> {
            List<String> incidentIds = entityManager.createQuery(
                            "SELECT i.id FROM ActionRequiredIncident i "
                                    + "WHERE i.profileId = :profileId AND i.taskKey = :taskKey "
                                    + "AND i.recoveredAt IS NULL",
                            String.class)
                    .setParameter("profileId", profileId)
                    .setParameter("taskKey", taskKey)
                    .getResultList();
            incidentIds.stream()
                    .map(id -> entityManager.find(ActionRequiredIncident.class, id))
                    .forEach(incident -> incident.recover(recoveredAt));
            return incidentIds.size();
        });
    }
}
