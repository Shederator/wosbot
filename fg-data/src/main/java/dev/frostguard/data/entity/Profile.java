package dev.frostguard.data.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;

/**
 * Root aggregate for a managed game account within the Frostguard platform.
 *
 * <p>Each profile binds to an emulator slot, holds character metadata, and
 * owns a collection of key-value {@link Config} entries that drive
 * per-account task behaviour.
 */
@Entity
@Table(name = "profiles")
@Access(AccessType.FIELD)
public class Profile {

	// ── value objects ────────────────────────────────────────────────

	/**
	 * Describes the emulator slot assignment and readiness of this profile.
	 */
	public record DeviceBinding(String slotId, boolean ready) {
		public static DeviceBinding unassigned() {
			return new DeviceBinding("0", false);
		}

		public static DeviceBinding ofSlot(String slot, boolean enabled) {
			return new DeviceBinding(slot != null ? slot : "0", enabled);
		}

		public boolean matchesSlot(int number) {
			return slotId != null && slotId.equals(String.valueOf(number));
		}
	}

	/**
	 * Snapshot of the in-game character bound to this account.
	 */
	public record CharacterIdentity(String heroId, String heroName, String alliance, String server) {
		public static final CharacterIdentity EMPTY = new CharacterIdentity(null, null, null, null);

		public boolean isPopulated() {
			return heroId != null || heroName != null;
		}
	}

	// ── persistent fields ────────────────────────────────────────────

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

	@Column(name = "profile_name", nullable = false)
	private String label;

	@Column(name = "emulator_number", nullable = false)
	private String deviceIndex;

	@Column(name = "enabled", nullable = false)
	private Boolean active;

	@Column(name = "priority", nullable = false, columnDefinition = "BIGINT DEFAULT 50")
	private Long weight;

	@Column(name = "reconnection_time", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
	private Long retryInterval;

	@Column(name = "character_id", nullable = true)
	private String avatarId;

	@Column(name = "character_name", nullable = true)
	private String avatarName;

	@Column(name = "character_alliance_code", nullable = true, length = 3)
	private String guildTag;

	@Column(name = "character_server", nullable = true)
	private String realm;

	@OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Config> settings = new ArrayList<>();

	@ManyToMany
	@JoinTable(name = "profile_tag_assignments",
			joinColumns = @JoinColumn(name = "profile_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id"))
	private Set<ProfileTag> tags = new LinkedHashSet<>();

	// ── construction ─────────────────────────────────────────────────

	public Profile() {}

	/**
	 * Creates a fresh profile with sensible defaults.
	 *
	 * @param displayName   human-readable label
	 * @param emulatorSlot  device slot identifier (null → "0")
	 * @return initialised profile instance
	 */
	public static Profile create(String displayName, String emulatorSlot) {
		Profile p = new Profile();
		p.label = normalise(displayName);
		p.deviceIndex = emulatorSlot != null ? emulatorSlot : "0";
		p.active = true;
		p.weight = 50L;
		p.retryInterval = 0L;
		return p;
	}

	/** Convenience factory that creates a profile with label "Default" on slot 0. */
	public static Profile createDefault() {
		return create("Default", "0");
	}

	// ── domain queries ───────────────────────────────────────────────

	/** Snapshot of the current emulator binding for this account. */
	public DeviceBinding deviceBinding() {
		return DeviceBinding.ofSlot(deviceIndex, Boolean.TRUE.equals(active));
	}

	/** Snapshot of the in-game character metadata. */
	public CharacterIdentity characterIdentity() {
		return new CharacterIdentity(avatarId, avatarName, guildTag, realm);
	}

	/** {@code true} when the profile is enabled and assigned to a device. */
	public boolean isLaunchable() {
		return deviceBinding().ready();
	}

	/** Tests whether this profile uses the given emulator slot number. */
	public boolean usesEmulatorNumber(int slot) {
		return deviceBinding().matchesSlot(slot);
	}

	/** Identity-based match on the database id. */
	public boolean matchesId(Long candidateId) {
		return id != null && id.equals(candidateId);
	}

	// ── mutations ────────────────────────────────────────────────────

	/** Overwrites the in-game character identity from a snapshot. */
	public void applyCharacterIdentity(CharacterIdentity identity) {
		if (identity == null) return;
		this.avatarId = identity.heroId();
		this.avatarName = identity.heroName();
		this.guildTag = identity.alliance();
		this.realm = identity.server();
	}

	public void renameTo(String newLabel) {
		this.label = normalise(newLabel);
	}

	public void assignEmulator(int slot) {
		this.deviceIndex = String.valueOf(slot);
	}

	public void enable()  { this.active = true;  }
	public void disable() { this.active = false; }

	// ── accessors (JPA + downstream) ─────────────────────────────────

	public Long getId()                 { return id; }
	public void setId(Long id)          { this.id = id; }
	public String getLabel()            { return label; }
	public void setLabel(String label)  { this.label = label; }
	public String getDeviceIndex()      { return deviceIndex; }
	public void setDeviceIndex(String v){ this.deviceIndex = v; }
	public Boolean getActive()          { return active; }
	public void setActive(Boolean v)    { this.active = v; }
	public Long getWeight()             { return weight; }
	public void setWeight(Long v)       { this.weight = v; }
	public Long getRetryInterval()      { return retryInterval; }
	public void setRetryInterval(Long v){ this.retryInterval = v; }
	public String getAvatarId()         { return avatarId; }
	public void setAvatarId(String v)   { this.avatarId = v; }
	public String getAvatarName()       { return avatarName; }
	public void setAvatarName(String v) { this.avatarName = v; }
	public String getGuildTag()         { return guildTag; }
	public void setGuildTag(String v)   { this.guildTag = v; }
	public String getRealm()            { return realm; }
	public void setRealm(String v)      { this.realm = v; }
	public List<Config> getSettings()   { return settings; }
	public void setSettings(List<Config> v) { this.settings = v; }
	public Set<ProfileTag> getTags() { return tags; }
	public void setTags(Set<ProfileTag> tags) { this.tags = tags; }

	// ── object identity ──────────────────────────────────────────────

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Profile other)) return false;
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() { return Objects.hashCode(id); }

	@Override
	public String toString() {
		return "Profile[" + id + ":" + label + " slot=" + deviceIndex + " active=" + active + "]";
	}

	// ── internals ────────────────────────────────────────────────────

	private static String normalise(String text) {
		return text == null ? "" : text.trim();
	}
}
