package dev.frostguard.api.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

public record WorkspacePaths(Path root, RuntimeChannel channel) {
    public static final String WORKSPACE_PROPERTY = "frostguard.workspace";
    public static final String CHANNEL_PROPERTY = "frostguard.channel";
    public static final String APPLICATION_ID_PROPERTY = "frostguard.application.id";

    public WorkspacePaths {
        root = root.toAbsolutePath().normalize();
    }

    public static WorkspacePaths current() {
        String configuredChannel = System.getProperty(CHANNEL_PROPERTY);
        if (configuredChannel == null || configuredChannel.isBlank()) {
            configuredChannel = System.getenv("FROSTGUARD_CHANNEL");
        }
        String override = System.getProperty(WORKSPACE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("FROSTGUARD_WORKSPACE");
        }
        Path developmentRoot = findDevelopmentRoot();
        if ((override == null || override.isBlank()) && configuredChannel == null && developmentRoot != null) {
            configuredChannel = RuntimeChannel.DEVELOPMENT.directoryName();
            override = developmentRoot.resolve(".frostguard-dev").toString();
        }
        RuntimeChannel channel = RuntimeChannel.from(configuredChannel);
        String applicationId = System.getProperty(APPLICATION_ID_PROPERTY, "").trim();
        if (!applicationId.isEmpty() && !applicationId.equals(channel.applicationId())) {
            throw new IllegalStateException("Application ID " + applicationId
                    + " does not match channel " + channel.directoryName());
        }
        Path root = override == null || override.isBlank()
                ? userWorkspace(channel, "default")
                : Path.of(override);
        return new WorkspacePaths(root, channel);
    }

    public static Path userWorkspace(RuntimeChannel channel, String name) {
        if (channel == null || !channel.isPublicRelease()) {
            throw new IllegalArgumentException("Only Stable and Nightly use installed user workspaces");
        }
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Workspace name must be one path segment");
        }
        return Path.of(System.getProperty("user.home"), ".frostguard", "workspaces",
                channel.directoryName(), name).toAbsolutePath().normalize();
    }

    public Optional<WorkspacePaths> releasePeer(RuntimeChannel targetChannel) {
        if (!channel.isPublicRelease() || targetChannel == null || !targetChannel.isPublicRelease()
                || targetChannel == channel) {
            return Optional.empty();
        }
        Path name = root.getFileName();
        if (name == null || !root.equals(userWorkspace(channel, name.toString()))) {
            return Optional.empty();
        }
        return Optional.of(new WorkspacePaths(userWorkspace(targetChannel, name.toString()), targetChannel));
    }

    private static Path findDevelopmentRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; candidate != null && depth < 6; depth++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("modules").resolve("desktop"))) {
                return candidate;
            }
        }
        return null;
    }

    public Path marker() { return root.resolve("frostguard-workspace.json"); }
    public Path database() { return root.resolve("frostguard.db"); }
    public Path config() { return root.resolve("config"); }
    public Path logs() { return root.resolve("logs"); }
    public Path customTasks() { return root.resolve("custom-tasks"); }
    public Path cache() { return root.resolve("cache"); }
    public Path watcher() { return root.resolve("watcher"); }
    public Path watcherConfig() { return watcher().resolve("telegram-watcher.properties"); }
    public Path watcherLock() { return watcher().resolve("watcher.lock"); }
    public Path applicationLock() { return root.resolve("frostguard.lock"); }

    public String identity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((channel.directoryName() + "\n" + root)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public int defaultLocalPort() {
        long prefix = Long.parseUnsignedLong(identity().substring(0, 8), 16);
        return 20_000 + (int) (prefix % 40_000);
    }
}
