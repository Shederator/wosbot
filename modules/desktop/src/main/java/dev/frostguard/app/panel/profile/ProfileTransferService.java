package dev.frostguard.app.panel.profile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.api.configs.ConfigurationKeyEnum;

final class ProfileTransferService {

	private static final int FORMAT_VERSION = 1;
	private static final int MAX_IMPORTED_PROFILES = 1_000;
	private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
	private static final int MAX_TEXT_LENGTH = 200;
	private final ObjectMapper mapper;

	ProfileTransferService() {
		mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	}

	void write(Path destination, List<ProfileAux> profiles) throws IOException {
		List<TransferredProfile> exported = profiles.stream().map(this::fromProfile).toList();
		mapper.writeValue(destination.toFile(), new TransferFile(FORMAT_VERSION, exported));
	}

	List<AccountDescriptor> read(Path source) throws IOException {
		if (!Files.isRegularFile(source) || Files.size(source) > MAX_FILE_SIZE_BYTES) {
			throw new IOException("Profile file must be a regular JSON file no larger than 5 MB");
		}
		TransferFile transfer = mapper.readValue(source.toFile(), TransferFile.class);
		if (transfer.formatVersion() != FORMAT_VERSION) {
			throw new IOException("Unsupported profile export version: " + transfer.formatVersion());
		}
		if (transfer.profiles() == null || transfer.profiles().size() > MAX_IMPORTED_PROFILES) {
			throw new IOException("Profile export contains an invalid number of profiles");
		}
		List<AccountDescriptor> profiles = new ArrayList<>();
		for (TransferredProfile imported : transfer.profiles()) {
			profiles.add(toDescriptor(imported));
		}
		return profiles;
	}

	AccountDescriptor duplicate(ProfileAux source, String newName) throws IOException {
		TransferredProfile copy = fromProfile(source);
		return toDescriptor(new TransferredProfile(newName, copy.emulatorNumber(), copy.enabled(), copy.priority(),
				copy.reconnectionTime(), copy.characterId(), copy.characterName(), copy.alliance(), copy.server(),
				copy.configs(), copy.tags()));
	}

	private TransferredProfile fromProfile(ProfileAux profile) {
		List<TransferredConfig> configs = profile.getConfigs().stream()
				.map(config -> new TransferredConfig(config.getName(), config.getValue()))
				.toList();
		return new TransferredProfile(profile.getName(), profile.getEmulatorNumber(), profile.isEnabled(),
				profile.getPriority(), profile.getReconnectionTime(), profile.getCharacterId(),
				profile.getCharacterName(), profile.getCharacterAllianceCode(), profile.getCharacterServer(),
				configs, profile.getTags());
	}

	private AccountDescriptor toDescriptor(TransferredProfile imported) throws IOException {
		if (imported == null || imported.name() == null || imported.name().isBlank()) {
			throw new IOException("Every imported profile requires a name");
		}
		validateText("Profile name", imported.name(), 80);
		validateText("Emulator", imported.emulatorNumber(), 40);
		validateText("Character ID", imported.characterId(), MAX_TEXT_LENGTH);
		validateText("Character name", imported.characterName(), MAX_TEXT_LENGTH);
		validateText("Alliance", imported.alliance(), 20);
		validateText("Server", imported.server(), 40);
		if (imported.priority() != null && (imported.priority() < 0 || imported.priority() > 10_000)) {
			throw new IOException("Priority must be between 0 and 10000");
		}
		if (imported.reconnectionTime() != null && imported.reconnectionTime() < 0) {
			throw new IOException("Reconnection time cannot be negative");
		}
		AccountDescriptor descriptor = new AccountDescriptor(null, imported.name().trim(),
				blankToDefault(imported.emulatorNumber(), "0"), Boolean.TRUE.equals(imported.enabled()),
				imported.priority() == null ? 50L : imported.priority(),
				imported.reconnectionTime() == null ? 0L : imported.reconnectionTime(),
				blankToNull(imported.characterId()), blankToNull(imported.characterName()),
				blankToNull(imported.alliance()), blankToNull(imported.server()));
		if (imported.configs() != null) {
			for (TransferredConfig config : imported.configs()) {
				if (config == null || config.name() == null || config.value() == null) {
					throw new IOException("Imported configurations require a name and value");
				}
				ConfigurationKeyEnum key = ConfigurationKeyEnum.fromName(config.name());
				if (key == null) throw new IOException("Unknown configuration: " + config.name());
				if (key.getType() == Boolean.class && !config.value().equalsIgnoreCase("true")
						&& !config.value().equalsIgnoreCase("false")) {
					throw new IOException("Invalid boolean value for " + config.name());
				}
				try { key.castValue(config.value()); }
				catch (Exception ex) { throw new IOException("Invalid value for " + config.name(), ex); }
				descriptor.getConfigs().add(new ConfigData(null, key.name(), config.value()));
			}
		}
		if (imported.tags() != null) {
			for (String tag : imported.tags()) validateText("Tag", tag, 40);
		}
		descriptor.setTags(imported.tags());
		return descriptor;
	}

	private void validateText(String field, String value, int maxLength) throws IOException {
		if (value != null && value.trim().length() > maxLength) {
			throw new IOException(field + " exceeds " + maxLength + " characters");
		}
	}

	private String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	record TransferFile(int formatVersion, List<TransferredProfile> profiles) {
	}

	record TransferredProfile(String name, String emulatorNumber, Boolean enabled, Long priority,
			Long reconnectionTime, String characterId, String characterName, String alliance, String server,
			List<TransferredConfig> configs, List<String> tags) {
	}

	record TransferredConfig(String name, String value) {
	}
}
