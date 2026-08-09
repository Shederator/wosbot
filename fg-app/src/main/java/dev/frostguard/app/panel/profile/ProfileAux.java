package dev.frostguard.app.panel.profile;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Observable view-model that backs a single profile row in the UI
 * table.  Every field is exposed as a JavaFX property so that
 * TableView bindings and listeners react to changes immediately.
 */
public class ProfileAux {

	private static final long FALLBACK_PRIORITY = 50L;
	private static final String BLANK = "";

	/* ── Observable properties ── */

	private final LongProperty id;
	private final StringProperty name;
	private final StringProperty emulatorNumber;
	private final BooleanProperty enabled;
	private final LongProperty priority;
	private final StringProperty status;
	private final LongProperty reconnectionTime;
	private final StringProperty characterId;
	private final StringProperty characterName;
	private final StringProperty characterAllianceCode;
	private final StringProperty characterServer;

	private List<ConfigAux> configs = new ArrayList<>();
	private List<String> tags = new ArrayList<>();

	/* ────────────────────────────────────────────────
	 *  Constructors
	 * ──────────────────────────────────────────────── */

	public ProfileAux(Long id, String name, String emulatorNumber,
					  boolean enabled, Long priority, String status,
					  Long reconnectionTime) {
		this(id, name, emulatorNumber, enabled, priority, status,
				reconnectionTime, BLANK, BLANK, BLANK, BLANK);
	}

	public ProfileAux(Long id, String name, String emulatorNumber,
					  boolean enabled, Long priority, String status,
					  Long reconnectionTime, String characterId,
					  String characterName, String characterAllianceCode,
					  String characterServer) {
		this.id                     = new SimpleLongProperty(id);
		this.name                   = safeStringProp(name);
		this.emulatorNumber         = safeStringProp(emulatorNumber);
		this.enabled                = new SimpleBooleanProperty(enabled);
		this.priority               = new SimpleLongProperty(priority);
		this.status                 = safeStringProp(status);
		this.reconnectionTime       = new SimpleLongProperty(reconnectionTime);
		this.characterId            = safeStringProp(characterId);
		this.characterName          = safeStringProp(characterName);
		this.characterAllianceCode  = safeStringProp(characterAllianceCode);
		this.characterServer        = safeStringProp(characterServer);
	}

	/* ────────────────────────────────────────────────
	 *  id
	 * ──────────────────────────────────────────────── */

	public Long getId()                   { return id.get(); }
	public void setId(Long id)            { this.id.set(id); }
	public LongProperty idProperty()      { return id; }

	/* ────────────────────────────────────────────────
	 *  name
	 * ──────────────────────────────────────────────── */

	public String getName()               { return name.get(); }
	public void setName(String val)       { name.set(sanitize(val)); }
	public StringProperty nameProperty()  { return name; }

	/* ────────────────────────────────────────────────
	 *  emulatorNumber
	 * ──────────────────────────────────────────────── */

	public String getEmulatorNumber()                   { return emulatorNumber.get(); }
	public void setEmulatorNumber(String val)            { emulatorNumber.set(sanitize(val)); }
	public StringProperty emulatorNumberProperty()       { return emulatorNumber; }

	/* ────────────────────────────────────────────────
	 *  enabled
	 * ──────────────────────────────────────────────── */

	public Boolean isEnabled()                { return enabled.get(); }
	public void setEnabled(boolean val)       { enabled.set(val); }
	public BooleanProperty enabledProperty()  { return enabled; }

	/* ────────────────────────────────────────────────
	 *  status
	 * ──────────────────────────────────────────────── */

	public String getStatus()                 { return status.get(); }
	public void setStatus(String val)         { status.set(sanitize(val)); }
	public StringProperty statusProperty()    { return status; }

	/* ────────────────────────────────────────────────
	 *  priority
	 * ──────────────────────────────────────────────── */

	public Long getPriority()                 { return priority.get(); }
	public void setPriority(Long val)         { priority.set(val != null ? val : FALLBACK_PRIORITY); }
	public LongProperty priorityProperty()    { return priority; }

	/* ────────────────────────────────────────────────
	 *  reconnectionTime
	 * ──────────────────────────────────────────────── */

	public Long getReconnectionTime()                   { return reconnectionTime.get(); }
	public void setReconnectionTime(Long val)            { reconnectionTime.set(val); }
	public LongProperty reconnectionTimeProperty()       { return reconnectionTime; }

	/* ────────────────────────────────────────────────
	 *  characterName
	 * ──────────────────────────────────────────────── */

	public String getCharacterName()                      { return characterName.get(); }
	public void setCharacterName(String val)               { characterName.set(sanitize(val)); }
	public StringProperty characterNameProperty()          { return characterName; }

	/* ────────────────────────────────────────────────
	 *  characterId
	 * ──────────────────────────────────────────────── */

	public String getCharacterId()                        { return characterId.get(); }
	public void setCharacterId(String val)                 { characterId.set(sanitize(val)); }
	public StringProperty characterIdProperty()            { return characterId; }

	/* ────────────────────────────────────────────────
	 *  characterAllianceCode
	 * ──────────────────────────────────────────────── */

	public String getCharacterAllianceCode()                { return characterAllianceCode.get(); }
	public void setCharacterAllianceCode(String val)         { characterAllianceCode.set(sanitize(val)); }
	public StringProperty characterAllianceCodeProperty()    { return characterAllianceCode; }

	/* ────────────────────────────────────────────────
	 *  characterServer
	 * ──────────────────────────────────────────────── */

	public String getCharacterServer()                    { return characterServer.get(); }
	public void setCharacterServer(String val)             { characterServer.set(sanitize(val)); }
	public StringProperty characterServerProperty()        { return characterServer; }

	/* ────────────────────────────────────────────────
	 *  Per-profile configuration map
	 * ──────────────────────────────────────────────── */

	public <T> T getConfiguration(ConfigurationKeyEnum key) {
		return key.castValue(
				locateConfig(key).map(ConfigAux::getValue).orElse(key.getDefaultValue()));
	}

	public List<ConfigAux> getConfigs() {
		return configs;
	}

	public void setConfigs(List<ConfigAux> incoming) {
		this.configs = incoming == null ? new ArrayList<>() : incoming;
	}

	public List<String> getTags() {
		return List.copyOf(tags);
	}

	public void setTags(List<String> incoming) {
		tags = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
	}

	public <T> T getConfig(ConfigurationKeyEnum key, Class<T> clazz) {
		return key.castValue(guaranteeConfig(key).getValue());
	}

	public <T> void setConfig(ConfigurationKeyEnum key, T value) {
		String serialised = value.toString();
		Optional<ConfigAux> match = locateConfig(key);

		if (match.isPresent()) {
			match.get().setValue(serialised);
		} else {
			configs.add(new ConfigAux(key.name(), serialised));
		}
	}

	/* ────────────────────────────────────────────────
	 *  Internal helpers
	 * ──────────────────────────────────────────────── */

	private Optional<ConfigAux> locateConfig(ConfigurationKeyEnum key) {
		return configs.stream()
				.filter(c -> c.getName().equalsIgnoreCase(key.name()))
				.findFirst();
	}

	private ConfigAux guaranteeConfig(ConfigurationKeyEnum key) {
		return locateConfig(key).orElseGet(() -> {
			ConfigAux created = new ConfigAux(key.name(), key.getDefaultValue());
			configs.add(created);
			return created;
		});
	}

	private static SimpleStringProperty safeStringProp(String raw) {
		return new SimpleStringProperty(sanitize(raw));
	}

	private static String sanitize(String raw) {
		return raw == null ? BLANK : raw;
	}
}
