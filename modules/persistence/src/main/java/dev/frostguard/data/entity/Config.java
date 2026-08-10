package dev.frostguard.data.entity;

import java.util.Objects;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "config")
@Access(AccessType.FIELD)
public class Config {

	public enum SettingScope {
		GLOBAL, PROFILE_BOUND;

		public static SettingScope of(Profile owner) {
			return owner == null ? GLOBAL : PROFILE_BOUND;
		}
	}

	public record SettingEntry(String key, String value, SettingScope scope) {
		public static SettingEntry global(String key, String value) {
			return new SettingEntry(key, value, SettingScope.GLOBAL);
		}

		public static SettingEntry scoped(String key, String value) {
			return new SettingEntry(key, value, SettingScope.PROFILE_BOUND);
		}

		public boolean matchesKey(ConfigurationKeyEnum candidate) {
			return candidate != null && candidate.name().equalsIgnoreCase(key);
		}

		public boolean toBool() { return Boolean.parseBoolean(value); }

		public int toInt() {
			try { return Integer.parseInt(value); }
			catch (NumberFormatException e) { return 0; }
		}

		public long toLong() {
			try { return Long.parseLong(value); }
			catch (NumberFormatException e) { return 0L; }
		}
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Integer id;

	@Column(name = "config_key", nullable = false)
	private String identifier;

	@Column(name = "value", nullable = false)
	private String content;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "profile_id", nullable = true, foreignKey = @ForeignKey(name = "fk_config_profile"))
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Profile owner;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "tp_config_id", nullable = false)
	private ConfigTemplate configTemplate;

	public Config() {}

	public Config(Profile owner, ConfigTemplate template, String identifier, String content) {
		this.owner = owner;
		this.configTemplate = template;
		this.identifier = sanitize(identifier);
		this.content = content != null ? content : "";
	}

	public static Config globalSetting(ConfigTemplate template, String key, String value) {
		return new Config(null, template, key, value);
	}

	public static Config profileSetting(Profile owner, ConfigTemplate template, String key, String value) {
		Objects.requireNonNull(owner, "profile-scoped setting requires an owner");
		return new Config(owner, template, key, value);
	}

	public SettingEntry toEntry() {
		return new SettingEntry(identifier, content, SettingScope.of(owner));
	}

	public SettingScope scope() {
		return SettingScope.of(owner);
	}

	public boolean isGlobal() { return scope() == SettingScope.GLOBAL; }

	public boolean isForProfile(Long profileId) {
		return owner != null && profileId != null && profileId.equals(owner.getId());
	}

	public boolean keyMatches(ConfigurationKeyEnum candidate) {
		return toEntry().matchesKey(candidate);
	}

	public boolean asBoolean() { return toEntry().toBool(); }
	public int asInt() { return toEntry().toInt(); }
	public long asLong() { return toEntry().toLong(); }

	public Config withRawValue(String newValue) {
		this.content = newValue;
		return this;
	}

	public Integer getId() { return id; }
	public void setId(Integer id) { this.id = id; }
	public String getIdentifier() { return identifier; }
	public void setIdentifier(String identifier) { this.identifier = identifier; }
	public String getContent() { return content; }
	public void setContent(String content) { this.content = content; }

	// Compatibility delegates
	public Profile getAccount() { return owner; }
	public void setAccount(Profile profile) { this.owner = profile; }
	public ConfigTemplate getWatcherSetting() { return configTemplate; }
	public void setWatcherSetting(ConfigTemplate configTemplate) { this.configTemplate = configTemplate; }

	@Override
	public String toString() {
		return "Config[" + id + " key=" + identifier + " val=" + content
			+ " " + scope() + "]";
	}

	private static String sanitize(String text) {
		return text == null ? "" : text.trim();
	}
}
