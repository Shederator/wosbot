package dev.frostguard.app.panel.update;

import dev.frostguard.api.runtime.RuntimeChannel;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

final class ChannelSwitchService {
    private static final String LAUNCHER_PROPERTY = "frostguard.channel.launcher.";
    private static final String PAGE_PROPERTY = "frostguard.channel.page.";

    private final Function<String, String> properties;
    private final Supplier<String> localAppData;
    private final Launcher launcher;
    private final PageOpener pageOpener;

    ChannelSwitchService() {
        this(System::getProperty, () -> System.getenv("LOCALAPPDATA"),
                executable -> new ProcessBuilder(executable.toString())
                        .directory(executable.getParent().toFile()).start(),
                uri -> {
                    if (!Desktop.isDesktopSupported()) {
                        throw new IOException("Desktop links are not supported on this system");
                    }
                    Desktop.getDesktop().browse(uri);
                });
    }

    ChannelSwitchService(Function<String, String> properties, Supplier<String> localAppData,
                         Launcher launcher, PageOpener pageOpener) {
        this.properties = properties;
        this.localAppData = localAppData;
        this.launcher = launcher;
        this.pageOpener = pageOpener;
    }

    Result open(RuntimeChannel target) throws IOException {
        if (target == null || !target.isPublicRelease()) {
            throw new IllegalArgumentException("Channel switching supports only Stable and Nightly");
        }
        Optional<Path> installed = installedLauncher(target);
        if (installed.isPresent()) {
            launcher.launch(installed.orElseThrow());
            return Result.LAUNCHED_INSTALLED;
        }
        URI page = releasePage(target);
        pageOpener.open(page);
        return Result.OPENED_RELEASE_PAGE;
    }

    Optional<Path> installedLauncher(RuntimeChannel target) {
        String productName = target.productName();
        String executableName = productName + ".exe";
        List<Path> candidates = new ArrayList<>();

        String configured = properties.apply(LAUNCHER_PROPERTY + target.directoryName());
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }

        String current = properties.apply("frostguard.launcher");
        if (current == null || current.isBlank()) {
            current = properties.apply("jpackage.app-path");
        }
        if (current != null && !current.isBlank()) {
            Path currentLauncher = Path.of(current).toAbsolutePath().normalize();
            Path currentInstall = currentLauncher.getParent();
            if (currentInstall != null && currentInstall.getParent() != null) {
                candidates.add(currentInstall.getParent().resolve(productName).resolve(executableName));
            }
        }

        String appData = localAppData.get();
        if (appData != null && !appData.isBlank()) {
            candidates.add(Path.of(appData).resolve(productName).resolve(executableName));
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private URI releasePage(RuntimeChannel target) throws IOException {
        String configured = properties.apply(PAGE_PROPERTY + target.directoryName());
        if (configured == null || configured.isBlank()) {
            configured = target == RuntimeChannel.NIGHTLY
                    ? "https://github.com/Shederator/wosbot/releases/tag/nightly"
                    : "https://github.com/Shederator/wosbot/releases/latest";
        }
        URI uri;
        try {
            uri = URI.create(configured);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("The " + target.displayName() + " release page is invalid", invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("The " + target.displayName() + " release page must use HTTPS");
        }
        return uri;
    }

    enum Result {
        LAUNCHED_INSTALLED,
        OPENED_RELEASE_PAGE
    }

    @FunctionalInterface
    interface Launcher {
        void launch(Path executable) throws IOException;
    }

    @FunctionalInterface
    interface PageOpener {
        void open(URI page) throws IOException;
    }
}
