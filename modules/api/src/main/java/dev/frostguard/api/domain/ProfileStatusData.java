package dev.frostguard.api.domain;

import java.util.Objects;

/**
 * Lightweight status snapshot for a single automation profile.
 */
public class ProfileStatusData {

    private Long profileRef;
    private String statusText;

    /* ── static factories ── */

    public static ProfileStatusData of(Long id, String status) {
        ProfileStatusData d = new ProfileStatusData();
        d.profileRef = id;
        d.statusText = status;
        return d;
    }

    public static ProfileStatusData ofIdle(Long id) {
        return of(id, null);
    }

    /* ── no-arg for frameworks ── */
    public ProfileStatusData() {}

    /* ── legacy 2-arg constructor ── */
    public ProfileStatusData(Long id, String status) {
        this.profileRef = id;
        this.statusText = status;
    }

    /* ── derived ── */

    public boolean isIdle() {
        return statusText == null || statusText.isBlank();
    }

    /* ── accessors ── */

    public Long getProfileRef()                 { return profileRef; }
    public void setProfileRef(Long id)          { this.profileRef = id; }

    public String getStatusText()               { return statusText; }
    public void setStatusText(String text)       { this.statusText = text; }

    /* ── legacy delegates ── */

    public Long getAccountId()          { return profileRef; }
    public void setAccountId(Long id)   { this.profileRef = id; }
    public String getLabel()            { return statusText; }
    public void setLabel(String l)      { this.statusText = l; }
    public Long getId()                 { return profileRef; }
    public void setId(Long id)          { this.profileRef = id; }
    public String getStatus()           { return statusText; }
    public void setStatus(String s)     { this.statusText = s; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProfileStatusData that)) return false;
        return Objects.equals(profileRef, that.profileRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileRef);
    }

    @Override
    public String toString() {
        return "Profile#" + profileRef + (isIdle() ? " [idle]" : " → " + statusText);
    }
}
