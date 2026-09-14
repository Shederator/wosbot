package dev.frostguard.app.panel.taskbuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.configs.SidebarNavigationMode;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarSection;

final class SidebarNavigationOptions {

    private SidebarNavigationOptions() {
    }

    static List<String> targetsFor(SidebarNavigationMode mode) {
        if (mode == SidebarNavigationMode.SECTION) {
            return Arrays.stream(SidebarSection.values())
                    .map(Enum::name)
                    .toList();
        }
        if (mode == SidebarNavigationMode.DESTINATION) {
            return Arrays.stream(SidebarDestination.values())
                    .map(Enum::name)
                    .toList();
        }
        return List.of();
    }

    static String chooseTarget(SidebarNavigationMode mode, String selectedTarget) {
        List<String> targets = targetsFor(mode);
        if (targets.contains(selectedTarget)) {
            return selectedTarget;
        }
        return targets.isEmpty() ? null : targets.get(0);
    }

    static String displayTarget(String target) {
        if (target == null || target.isEmpty()) {
            return "";
        }
        String readable = target.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }
}
