package dev.frostguard.app.panel.profile;

import java.util.Locale;
import java.util.regex.Pattern;

final class ProfileSearchMatcher {
	private static final Pattern TOKEN = Pattern.compile("(?:[^\\s\\\"]|\\\"[^\\\"]*\\\")+");

	private ProfileSearchMatcher() {
	}

	static boolean matches(ProfileAux profile, String query) {
		if (profile == null || query == null || query.isBlank()) return true;
		return TOKEN.matcher(query.trim()).results().map(result -> result.group().replace("\"", ""))
				.allMatch(token -> matchesToken(profile, token.toLowerCase(Locale.ROOT)));
	}

	private static boolean matchesToken(ProfileAux profile, String token) {
		int separator = token.indexOf(':');
		if (separator > 0) {
			String field = token.substring(0, separator);
			String value = token.substring(separator + 1);
			return switch (field) {
				case "name" -> contains(profile.getName(), value);
				case "profile", "id" -> contains(String.valueOf(profile.getId()), value);
				case "character" -> contains(profile.getCharacterId(), value)
						|| contains(profile.getCharacterName(), value);
				case "server" -> contains(profile.getCharacterServer(), value);
				case "alliance" -> contains(profile.getCharacterAllianceCode(), value);
				case "emulator" -> contains(profile.getEmulatorNumber(), value);
				case "tag" -> profile.getTags().stream().anyMatch(tag -> contains(tag, value));
				case "enabled" -> Boolean.toString(profile.isEnabled()).equals(value);
				case "status" -> contains(profile.getStatus(), value);
				default -> matchesAnyField(profile, token);
			};
		}
		return matchesAnyField(profile, token);
	}

	private static boolean matchesAnyField(ProfileAux profile, String value) {
		return contains(profile.getName(), value)
				|| contains(String.valueOf(profile.getId()), value)
				|| contains(profile.getCharacterId(), value)
				|| contains(profile.getCharacterName(), value)
				|| contains(profile.getCharacterServer(), value)
				|| contains(profile.getCharacterAllianceCode(), value)
				|| contains(profile.getEmulatorNumber(), value)
				|| contains(profile.getStatus(), value)
				|| profile.getTags().stream().anyMatch(tag -> contains(tag, value));
	}

	private static boolean contains(String candidate, String value) {
		return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(value);
	}
}
