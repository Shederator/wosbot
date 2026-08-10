package dev.frostguard.data.entity;

import java.util.Objects;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "profile_building")
@Access(AccessType.FIELD)
public class ProfileBuilding {

	public record LevelStatus(int current, boolean upgradeInProgress) {
		public static LevelStatus initial() {
			return new LevelStatus(0, false);
		}

		public static LevelStatus at(int level, boolean upgrading) {
			return new LevelStatus(Math.max(0, level), upgrading);
		}

		public boolean meetsThreshold(int required) {
			return current >= required;
		}

		public LevelStatus advancedTo(int newLevel) {
			return new LevelStatus(Math.max(0, newLevel), false);
		}
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "building_type", nullable = false)
	private BuildingType kind;

	@Column(name = "current_level", nullable = false)
	private Integer tier;

	@Column(name = "has_internal_upgrade", nullable = false)
	private Boolean innerUpgradeActive;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "profile_id", nullable = false)
	private Profile profile;

	protected ProfileBuilding() {}

	public ProfileBuilding(Profile profile, BuildingType kind, Integer tier, Boolean innerUpgradeActive) {
		this.profile = profile;
		this.kind = kind;
		this.tier = tier != null ? tier : 0;
		this.innerUpgradeActive = innerUpgradeActive != null ? innerUpgradeActive : false;
	}

	public static ProfileBuilding tracked(Profile owner, BuildingType type, int level) {
		return new ProfileBuilding(owner, type, level, false);
	}

	public LevelStatus levelStatus() {
		return LevelStatus.at(tier != null ? tier : 0, Boolean.TRUE.equals(innerUpgradeActive));
	}

	public void applyLevelStatus(LevelStatus status) {
		this.tier = status.current();
		this.innerUpgradeActive = status.upgradeInProgress();
	}

	public boolean isKnown() { return kind != null; }

	public boolean isAtLeastLevel(int threshold) {
		return levelStatus().meetsThreshold(threshold);
	}

	public void upgradeTo(int newLevel) {
		applyLevelStatus(levelStatus().advancedTo(newLevel));
	}

	public boolean belongsTo(Profile candidate) {
		return profile != null && candidate != null
			&& Objects.equals(profile.getId(), candidate.getId());
	}

	public boolean isUpgrading() { return levelStatus().upgradeInProgress(); }

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public BuildingType getKind() { return kind; }
	public void setKind(BuildingType kind) { this.kind = kind; }
	public Integer getTier() { return tier; }
	public void setTier(Integer tier) { this.tier = tier; }
	public Boolean getInnerUpgradeActive() { return innerUpgradeActive; }
	public void setInnerUpgradeActive(Boolean innerUpgradeActive) { this.innerUpgradeActive = innerUpgradeActive; }

	// Compatibility delegates
	public Profile getAccount() { return profile; }
	public void setAccount(Profile profile) { this.profile = profile; }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ProfileBuilding other)) return false;
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() { return Objects.hashCode(id); }

	@Override
	public String toString() {
		return "ProfileBuilding[" + id + " " + kind + " " + levelStatus() + "]";
	}
}
