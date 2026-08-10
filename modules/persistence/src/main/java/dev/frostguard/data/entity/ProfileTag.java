package dev.frostguard.data.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "profile_tags", uniqueConstraints = @UniqueConstraint(name = "uk_profile_tag_name", columnNames = "name"))
public class ProfileTag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 40)
	private String name;

	@Column(name = "color", nullable = false, length = 7, columnDefinition = "varchar(7) default '#388bfd'")
	private String color = "#388bfd";

	public ProfileTag() {
	}

	public ProfileTag(String name) {
		this.name = name;
	}

	public ProfileTag(String name, String color) {
		this.name = name;
		this.color = color;
	}

	public Long getId() { return id; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getColor() { return color; }
	public void setColor(String color) { this.color = color; }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ProfileTag tag)) return false;
		return id != null ? id.equals(tag.id) : Objects.equals(name, tag.name);
	}

	@Override
	public int hashCode() {
		return id != null ? id.hashCode() : Objects.hashCode(name);
	}
}
