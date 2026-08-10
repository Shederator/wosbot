package dev.frostguard.api.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes the current state of the automation execution queue,
 * including its owner profile and enrolled participants.
 */
public class QueueStateData {

    private Long ownerProfileId;
    private boolean frozen;
    private List<QueueProfileStateData> participantList;

    /* ── static factory ── */

    public static QueueStateData of(Long ownerId, boolean frozen,
                                    List<QueueProfileStateData> participants) {
        QueueStateData q = new QueueStateData();
        q.ownerProfileId = ownerId;
        q.frozen = frozen;
        q.participantList = participants != null
                ? new ArrayList<>(participants)
                : new ArrayList<>();
        return q;
    }

    /* ── no-arg for frameworks ── */
    public QueueStateData() {
        this.participantList = new ArrayList<>();
    }

    /* ── legacy 3-arg constructor ── */
    public QueueStateData(Long ownerId, boolean frozen, List<QueueProfileStateData> participants) {
        this.ownerProfileId = ownerId;
        this.frozen = frozen;
        this.participantList = participants != null ? new ArrayList<>(participants) : new ArrayList<>();
    }

    /* ── derived ── */

    public int participantCount() {
        return participantList != null ? participantList.size() : 0;
    }

    public boolean hasParticipants() {
        return participantCount() > 0;
    }

    public List<QueueProfileStateData> participantsView() {
        return Collections.unmodifiableList(participantList);
    }

    /* ── accessors ── */

    public Long getOwnerProfileId()                     { return ownerProfileId; }
    public void setOwnerProfileId(Long id)              { this.ownerProfileId = id; }

    public boolean isFrozen()                           { return frozen; }
    public void setFrozen(boolean f)                    { this.frozen = f; }

    public List<QueueProfileStateData> getParticipantList()             { return participantList; }
    public void setParticipantList(List<QueueProfileStateData> list)    { this.participantList = list != null ? list : new ArrayList<>(); }

    /* ── legacy delegates ── */

    public Long getAccountId()          { return ownerProfileId; }
    public void setAccountId(Long id)   { this.ownerProfileId = id; }
    public boolean isHalted()           { return frozen; }
    public void setHalted(boolean h)    { this.frozen = h; }
    public List<QueueProfileStateData> getActiveEntries()           { return participantList; }
    public void setActiveEntries(List<QueueProfileStateData> e)     { setParticipantList(e); }
    public Long getProfileId()          { return ownerProfileId; }
    public void setProfileId(Long id)   { this.ownerProfileId = id; }
    public boolean isPaused()           { return frozen; }
    public void setPaused(boolean p)    { this.frozen = p; }
    public List<QueueProfileStateData> getActiveQueues()             { return participantList; }
    public void setActiveQueues(List<QueueProfileStateData> q)       { setParticipantList(q); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueueStateData that)) return false;
        return frozen == that.frozen && Objects.equals(ownerProfileId, that.ownerProfileId);
    }

    @Override
    public int hashCode() { return Objects.hash(ownerProfileId, frozen); }

    @Override
    public String toString() {
        return "Queue{owner=" + ownerProfileId + ", frozen=" + frozen
             + ", participants=" + participantCount() + "}";
    }
}
