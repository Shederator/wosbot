package dev.frostguard.app.panel.launcher;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

final class LauncherTitleFormatter {

    private LauncherTitleFormatter() {
    }

    static String formatProfileSegment(List<ProfileEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }

        return entries.stream()
                .filter(entry -> entry != null && entry.profileId() != null)
                .sorted(Comparator.comparing(ProfileEntry::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(entry -> String.format("%s [Stamina: %d]", entry.displayName(), entry.stamina()))
                .collect(Collectors.joining(" | "));
    }

    record ProfileEntry(Long profileId, String profileName, int stamina) {

        String displayName() {
            return profileName != null && !profileName.isBlank()
                    ? profileName
                    : String.valueOf(profileId);
        }
    }
}
