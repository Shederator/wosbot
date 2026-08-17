package dev.frostguard.api.domain;

import java.util.Objects;

/**
 * State descriptor for a single profile entry within the execution queue.
 */
public class QueueProfileStateData {

    private Long profileRef;
    private String displayTag;
    private boolean suspended;

    /* ── static factory ── */

    public static QueueProfileStateData active(Long id, String name) {
        QueueProfileStateData d = new QueueProfileStateData();
        d.profileRef = id;
        d.displayTag = name;
        d.suspended = false;
        return d;
    }

    /* ── no-arg for frameworks ── */
    public QueueProfileStateData() {}

    /* ── legacy 3-arg constructor ── */
    public QueueProfileStateData(Long id, String name, boolean halted) {
        this.profileRef = id;
        this.displayTag = name;
        this.suspended = halted;
    }

    /* ── derived ── */
    public boolean isActive() { return !suspended; }

    /* ── accessors ── */

    public Long getProfileRef()             { return profileRef; }
    public void setProfileRef(Long id)      { this.profileRef = id; }

    public String getDisplayTag()           { return displayTag; }
    public void setDisplayTag(String tag)   { this.displayTag = tag; }

    public boolean isSuspended()            { return suspended; }
    public void setSuspended(boolean s)     { this.suspended = s; }

    /* ── legacy delegates ── */

    public Long getAccountId()          { return profileRef; }
    public void setAccountId(Long id)   { this.profileRef = id; }
    public String getAccountName()      { return displayTag; }
    public void setAccountName(String n){ this.displayTag = n; }
    public String getProfileName()      { return displayTag; }
    public void setProfileName(String n){ this.displayTag = n; }
    public boolean isHalted()           { return suspended; }
    public void setHalted(boolean h)    { this.suspended = h; }
    public Long getProfileId()          { return profileRef; }
    public void setProfileId(Long id)   { this.profileRef = id; }
    public boolean isPaused()           { return suspended; }
    public void setPaused(boolean p)    { this.suspended = p; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueueProfileStateData that)) return false;
        return Objects.equals(profileRef, that.profileRef);
    }

    @Override
    public int hashCode() { return Objects.hash(profileRef); }

    @Override
    public String toString() {
        return displayTag + "#" + profileRef + (suspended ? " [suspended]" : " [active]");
    }
}
