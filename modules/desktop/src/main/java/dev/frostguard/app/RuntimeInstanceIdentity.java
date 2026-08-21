package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeInstanceIdentity {
    public static final String INSTANCE_LABEL_PROPERTY = "frostguard.instance.label";
    public static final String INSTANCE_LABEL_ENVIRONMENT = "FROSTGUARD_INSTANCE_LABEL";

    private RuntimeInstanceIdentity() {
    }

    public static String current() {
        return Holder.INSTANCE;
    }

    static String resolve(WorkspacePaths workspace, String explicitLabel) {
        if (explicitLabel != null && !explicitLabel.isBlank()) {
            return explicitLabel.trim();
        }
        if (workspace.channel() != RuntimeChannel.DEVELOPMENT) {
            return fileName(workspace.root(), workspace.channel().directoryName());
        }

        Path projectRoot = developmentProjectRoot(workspace.root());
        Path gitMetadata = projectRoot.resolve(".git");
        if (Files.isRegularFile(gitMetadata)) {
            return fileName(projectRoot, workspace.channel().directoryName());
        }
        if (Files.isDirectory(gitMetadata)) {
            String revision = readRevision(gitMetadata.resolve("HEAD"));
            if (revision != null) {
                return revision;
            }
        }
        return fileName(projectRoot, fileName(workspace.root(), workspace.channel().directoryName()));
    }

    static Path developmentProjectRoot(Path workspaceRoot) {
        Path fileName = workspaceRoot.getFileName();
        if (fileName != null && ".frostguard-dev".equals(fileName.toString()) && workspaceRoot.getParent() != null) {
            return workspaceRoot.getParent();
        }
        return workspaceRoot;
    }

    private static String readRevision(Path head) {
        try {
            String value = Files.readString(head).trim();
            String branchPrefix = "ref: refs/heads/";
            if (value.startsWith(branchPrefix) && value.length() > branchPrefix.length()) {
                return value.substring(branchPrefix.length());
            }
            if (value.matches("[0-9a-fA-F]{7,64}")) {
                return value.substring(0, Math.min(8, value.length()));
            }
        } catch (IOException ignored) {
            // The checkout directory remains a useful identity when Git metadata is unavailable.
        }
        return null;
    }

    private static String fileName(Path path, String fallback) {
        Path fileName = path.getFileName();
        return fileName == null || fileName.toString().isBlank() ? fallback : fileName.toString();
    }

    private static String configuredLabel() {
        String value = System.getProperty(INSTANCE_LABEL_PROPERTY);
        return value == null || value.isBlank() ? System.getenv(INSTANCE_LABEL_ENVIRONMENT) : value;
    }

    private static final class Holder {
        private static final String INSTANCE = resolve(WorkspacePaths.current(), configuredLabel());
    }
}
