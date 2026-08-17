package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.WorkspacePaths;

import java.util.Objects;

record StartupOptions(boolean headless, boolean nativeSmokeTest, String workspaceName) {
    private static final String WORKSPACE_OPTION = "--workspace";

    static StartupOptions parse(String[] args) {
        Objects.requireNonNull(args, "args");
        boolean headless = false;
        boolean nativeSmokeTest = false;
        String workspaceName = null;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--headless".equalsIgnoreCase(argument)) {
                headless = true;
            } else if ("--frostguard-native-smoke-test".equals(argument)) {
                nativeSmokeTest = true;
            } else if (WORKSPACE_OPTION.equals(argument)) {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--workspace requires a workspace name");
                }
                workspaceName = acceptWorkspaceName(workspaceName, args[++index]);
            } else if (argument.startsWith(WORKSPACE_OPTION + "=")) {
                workspaceName = acceptWorkspaceName(
                        workspaceName, argument.substring((WORKSPACE_OPTION + "=").length()));
            }
        }
        return new StartupOptions(headless, nativeSmokeTest, workspaceName);
    }

    WorkspacePaths resolveWorkspace() {
        WorkspacePaths current = WorkspacePaths.current();
        if (workspaceName == null) {
            return current;
        }
        if (!current.channel().isPublicRelease()) {
            throw new IllegalArgumentException(
                    "--workspace is available for installed Stable and Nightly builds only; "
                            + "development launches already use a worktree-local workspace");
        }
        return new WorkspacePaths(
                WorkspacePaths.userWorkspace(current.channel(), workspaceName), current.channel());
    }

    private static String acceptWorkspaceName(String current, String candidate) {
        if (current != null) {
            throw new IllegalArgumentException("--workspace can be specified only once");
        }
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("--workspace requires a workspace name");
        }
        return candidate;
    }
}
