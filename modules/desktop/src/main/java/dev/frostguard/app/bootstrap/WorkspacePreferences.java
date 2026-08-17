package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.WorkspacePaths;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class WorkspacePreferences {
    private static final String ROOT = "/dev/frostguard/workspaces";

    private WorkspacePreferences() {
    }

    public static Preferences currentNode(String scope) {
        return node(WorkspacePaths.current(), scope);
    }

    static Preferences node(WorkspacePaths workspace, String scope) {
        if (scope == null || scope.isBlank() || scope.contains("/")) {
            throw new IllegalArgumentException("Preference scope must be one non-empty node name");
        }
        return Preferences.userRoot().node(ROOT)
                .node(workspace.channel().directoryName())
                .node(workspace.identity())
                .node(scope);
    }

    static void copyAll(WorkspacePaths source, WorkspacePaths target) throws BackingStoreException {
        Preferences sourceRoot = Preferences.userRoot().node(ROOT)
                .node(source.channel().directoryName()).node(source.identity());
        removeAll(target);
        Preferences targetRoot = Preferences.userRoot().node(ROOT)
                .node(target.channel().directoryName()).node(target.identity());
        copyNode(sourceRoot, targetRoot);
        targetRoot.flush();
    }

    static void removeAll(WorkspacePaths workspace) throws BackingStoreException {
        Preferences root = Preferences.userRoot().node(ROOT)
                .node(workspace.channel().directoryName()).node(workspace.identity());
        root.removeNode();
        Preferences.userRoot().flush();
    }

    private static void copyNode(Preferences source, Preferences target) throws BackingStoreException {
        for (String key : source.keys()) {
            target.put(key, source.get(key, ""));
        }
        for (String child : source.childrenNames()) {
            copyNode(source.node(child), target.node(child));
        }
    }
}
