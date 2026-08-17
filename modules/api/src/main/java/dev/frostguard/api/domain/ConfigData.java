package dev.frostguard.api.domain;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

import java.util.Objects;

/**
 * Single configuration entry binding a key to a raw string value,
 * optionally scoped to a specific profile.
 */
public class ConfigData {

    private Long scopedProfileId;
    private ConfigurationKeyEnum settingKey;
    private String rawValue;

    /* ── static factory ── */

    public static ConfigData of(ConfigurationKeyEnum key, String value, Long profileId) {
        ConfigData d = new ConfigData();
        d.settingKey = Objects.requireNonNull(key, "key");
        d.rawValue = value;
        d.scopedProfileId = profileId;
        return d;
    }

    public static ConfigData global(ConfigurationKeyEnum key, String value) {
        return of(key, value, null);
    }

    /** Immutable-style copy with updated value. */
    public ConfigData withValue(String newValue) {
        return of(settingKey, newValue, scopedProfileId);
    }

    /* ── no-arg for frameworks ── */
    public ConfigData() {}

    /** Compatibility constructor for UI models that still carry key names as strings. */
    @Deprecated(since = "2.1", forRemoval = false)
    public ConfigData(Long profileId, String keyName, String value) {
        this.scopedProfileId = profileId;
        this.settingKey = resolveKeyName(keyName);
        this.rawValue = value;
    }

    private static ConfigurationKeyEnum resolveKeyName(String keyName) {
        return keyName == null || keyName.isBlank()
                ? null
                : ConfigurationKeyEnum.valueOf(keyName.trim());
    }

    /* ── derived ── */

    public boolean isGlobal() {
        return scopedProfileId == null;
    }

    public boolean asBoolean() {
        return Boolean.parseBoolean(rawValue);
    }

    public int asInt() {
        try { return Integer.parseInt(rawValue); }
        catch (NumberFormatException e) { return 0; }
    }

    public long asLong() {
        try { return Long.parseLong(rawValue); }
        catch (NumberFormatException e) { return 0L; }
    }

    /* ── accessors ── */

    public Long getScopedProfileId()                    { return scopedProfileId; }
    public void setScopedProfileId(Long id)             { this.scopedProfileId = id; }

    public ConfigurationKeyEnum getSettingKey()         { return settingKey; }
    public void setSettingKey(ConfigurationKeyEnum k)   { this.settingKey = k; }

    public String getRawValue()                         { return rawValue; }
    public void setRawValue(String v)                   { this.rawValue = v; }

    /* ── legacy delegates ── */

    public Long getOwnerAccountId()         { return scopedProfileId; }
    public void setOwnerAccountId(Long id)  { this.scopedProfileId = id; }
    public Long getProfileId()              { return scopedProfileId; }
    public void setProfileId(Long id)       { this.scopedProfileId = id; }
    public ConfigurationKeyEnum getKey()    { return settingKey; }
    public void setKey(ConfigurationKeyEnum k) { this.settingKey = k; }
    public String getPayload()              { return rawValue; }
    public void setPayload(String v)        { this.rawValue = v; }
    public String getConfigurationName()    { return settingKey != null ? settingKey.name() : null; }
    public String getValue()                { return rawValue; }
    public void setValue(String v)           { this.rawValue = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfigData that)) return false;
        return settingKey == that.settingKey
            && Objects.equals(scopedProfileId, that.scopedProfileId);
    }

    @Override
    public int hashCode() { return Objects.hash(settingKey, scopedProfileId); }

    @Override
    public String toString() {
        return settingKey + "=" + rawValue + (isGlobal() ? " [global]" : " [profile#" + scopedProfileId + "]");
    }
}
