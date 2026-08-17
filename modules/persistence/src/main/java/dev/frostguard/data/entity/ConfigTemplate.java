package dev.frostguard.data.entity;

import dev.frostguard.api.configs.ConfigScope;
import dev.frostguard.api.configs.TpConfigEnum;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tp_config")
@Access(AccessType.FIELD)
public class ConfigTemplate {

	public record TemplateDescriptor(Integer code, String name) {
		public static TemplateDescriptor from(TpConfigEnum definition) {
			return new TemplateDescriptor(definition.getId(), definition.getName());
		}
	}

	@Id
	@Column(name = "id", nullable = false, unique = true)
	private Integer templateCode;

	@Column(name = "name", nullable = false, unique = true)
	private String templateName;

	protected ConfigTemplate() {}

	public static ConfigTemplate fromDefinition(TpConfigEnum definition) {
		return fromDescriptor(TemplateDescriptor.from(definition));
	}

	public static ConfigTemplate fromDescriptor(TemplateDescriptor descriptor) {
		ConfigTemplate tpl = new ConfigTemplate();
		tpl.templateCode = descriptor.code();
		tpl.templateName = sanitize(descriptor.name());
		return tpl;
	}

	public TemplateDescriptor describe() {
		return new TemplateDescriptor(templateCode, templateName);
	}

	public boolean appliesTo(ConfigScope scope) {
		if (scope == null || templateName == null) return false;
		return templateName.equalsIgnoreCase(scope.name());
	}

	public Config createSettingForProfile(Profile owner, String key, String value) {
		return new Config(owner, this, key, value);
	}

	public boolean matchesId(int candidateId) {
		return templateCode != null && templateCode == candidateId;
	}

	public Integer getId() { return templateCode; }
	public void setId(Integer id) { this.templateCode = id; }
	public String getLabel() { return templateName; }
	public void setLabel(String label) { this.templateName = sanitize(label); }

	// Compatibility delegates
	public String getTemplateName() { return getLabel(); }
	public void setTemplateName(String name) { setLabel(name); }

	@Deprecated
	public ConfigTemplate(TpConfigEnum key) {
		this.templateCode = key.getId();
		this.templateName = sanitize(key.getName());
	}

	@Override
	public String toString() {
		return "ConfigTemplate[" + templateCode + ":" + templateName + "]";
	}

	private static String sanitize(String label) {
		return label == null ? "" : label.trim();
	}
}
