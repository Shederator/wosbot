package dev.frostguard.api.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import dev.frostguard.api.configs.ConfigurationKeyEnum;

/**
 * Full descriptor for a managed game profile, including identity fields,
 * device assignment, scheduling metadata, and the profile-specific
 * configuration entries. This is the primary data carrier between
 * the persistence, engine, and UI layers.
 */
public class AccountDescriptor {

    private Long accountId;
    private String displayName;
    private String deviceSlot;
    private Boolean active;
    private Long sortWeight;
    private String statusText;
    private Long cooldownSeconds;
    private String heroId;
    private String heroName;
    private String guildTag;
    private String serverName;
    private int queueSlot = Integer.MAX_VALUE;
    private List<ConfigData> entries = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private HashMap<String, String> globalOverrides = new HashMap<>();

    public AccountDescriptor(Long accountId) {
        this.accountId = accountId;
    }

    public AccountDescriptor(Long accountId, String displayName, String deviceSlot,
                             Boolean active, Long sortWeight, Long cooldownSeconds) {
        this.accountId = accountId;
        this.displayName = displayName;
        this.deviceSlot = deviceSlot;
        this.active = active;
        this.sortWeight = sortWeight;
        this.cooldownSeconds = cooldownSeconds;
    }

    public AccountDescriptor(Long accountId, String displayName, String deviceSlot,
                             Boolean active, Long sortWeight, Long cooldownSeconds,
                             String heroId, String heroName, String guildTag, String serverName) {
        this(accountId, displayName, deviceSlot, active, sortWeight, cooldownSeconds);
        this.heroId = heroId;
        this.heroName = heroName;
        this.guildTag = guildTag;
        this.serverName = serverName;
    }

    // ---- Profile identity ----

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDeviceSlot() { return deviceSlot; }
    public void setDeviceSlot(String deviceSlot) { this.deviceSlot = deviceSlot; }

    public Boolean isActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public Long getSortWeight() { return sortWeight; }
    public void setSortWeight(Long sortWeight) { this.sortWeight = sortWeight; }

    public String getStatusText() { return statusText; }
    public void setStatusText(String statusText) { this.statusText = statusText; }

    public Long getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(Long cooldownSeconds) {
        this.cooldownSeconds = (cooldownSeconds != null && cooldownSeconds >= 0) ? cooldownSeconds : 30L;
    }

    // ---- Hero/Character fields ----

    public String getHeroId() { return heroId; }
    public void setHeroId(String heroId) { this.heroId = heroId; }

    public String getHeroName() { return heroName; }
    public void setHeroName(String heroName) { this.heroName = heroName; }

    public String getGuildTag() { return guildTag; }
    public void setGuildTag(String guildTag) { this.guildTag = guildTag; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    // ---- Queue position ----

    public int getQueueSlot() { return queueSlot; }
    public void setQueueSlot(int queueSlot) { this.queueSlot = queueSlot; }

    // ---- Configuration entries ----

    public List<ConfigData> getEntries() { return entries; }
    public void setEntries(List<ConfigData> entries) { this.entries = entries; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags); }

    public HashMap<String, String> getGlobalOverrides() { return globalOverrides; }
    public void setGlobalOverrides(HashMap<String, String> overrides) { this.globalOverrides = overrides; }

    /**
     * Reads the value of a specific config key from this profile's entries,
     * falling back to the key's default value when not found. Inserts a
     * default entry on first access to ensure subsequent lookups succeed.
     */
    public <T> T readSetting(ConfigurationKeyEnum key, Class<T> clazz) {
        Optional<ConfigData> match = entries.stream()
                .filter(e -> key.equals(e.getSettingKey()) || key.name().equalsIgnoreCase(String.valueOf(e.getSettingKey())))
                .findFirst();

        if (match.isEmpty() && !key.isLegacyOnly()) {
            entries.add(ConfigData.of(key, key.getDefaultValue(), -1L));
        }

        String raw = match.map(ConfigData::getRawValue).orElse(key.getDefaultValue());
        return key.castValue(raw);
    }

    /**
     * Writes or updates a config key on this profile.
     */
    public <T> void writeSetting(ConfigurationKeyEnum key, T value) {
        String serialized = value.toString();
        Optional<ConfigData> match = entries.stream()
                .filter(e -> key.equals(e.getSettingKey()) || key.name().equalsIgnoreCase(String.valueOf(e.getSettingKey())))
                .findFirst();

        if (match.isPresent()) {
            match.get().setRawValue(serialized);
        } else {
            entries.add(ConfigData.of(key, serialized, getAccountId()));
        }
    }

    // Legacy compatibility
    public Long getId() { return accountId; }
    public void setId(Long id) { this.accountId = id; }
    public String getName() { return displayName; }
    public void setName(String n) { this.displayName = n; }
    public String getEmulatorNumber() { return deviceSlot; }
    public void setEmulatorNumber(String n) { this.deviceSlot = n; }
    public Boolean getEnabled() { return active; }
    public void setEnabled(Boolean e) { this.active = e; }
    public Long getPriority() { return sortWeight; }
    public void setPriority(Long p) { this.sortWeight = p; }
    public String getStatus() { return statusText; }
    public void setStatus(String s) { this.statusText = s; }
    public Long getReconnectionTime() { return cooldownSeconds; }
    public void setReconnectionTime(Long t) { setCooldownSeconds(t); }
    public String getCharacterId() { return heroId; }
    public void setCharacterId(String id) { this.heroId = id; }
    public String getCharacterName() { return heroName; }
    public void setCharacterName(String n) { this.heroName = n; }
    public String getCharacterAllianceCode() { return guildTag; }
    public void setCharacterAllianceCode(String c) { this.guildTag = c; }
    public String getCharacterServer() { return serverName; }
    public void setCharacterServer(String s) { this.serverName = s; }
    public int getQueuePosition() { return queueSlot; }
    public void setQueuePosition(int p) { this.queueSlot = p; }
    public List<ConfigData> getConfigs() { return entries; }
    public void setConfigs(List<ConfigData> configs) { this.entries = configs; }
    public HashMap<String, String> getGlobalsettings() { return globalOverrides; }
    public void setGlobalsettings(HashMap<String, String> gs) { this.globalOverrides = gs; }
    public void setGlobalSettings(HashMap<String, String> gs) { this.globalOverrides = gs; }
    public <T> T getConfig(ConfigurationKeyEnum key, Class<T> clazz) { return readSetting(key, clazz); }
    public <T> void setConfig(ConfigurationKeyEnum key, T value) { writeSetting(key, value); }
}
