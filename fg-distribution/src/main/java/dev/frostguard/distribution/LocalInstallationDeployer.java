package dev.frostguard.distribution;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Replaces only Maven-managed local-installation paths and rolls back failed replacements. */
public final class LocalInstallationDeployer {
    static final List<String> MANAGED_PATHS = List.of(
            "Frostguard.bat", "app", "resources", "docs", "build-info.json");

    private LocalInstallationDeployer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected <staging-root> <local-installation>");
        }
        deploy(Path.of(args[0]), Path.of(args[1]));
    }

    public static void deploy(Path stagingRoot, Path installationRoot) throws IOException {
        deploy(stagingRoot, installationRoot, LocalInstallationDeployer::move);
    }

    static void deploy(Path stagingRoot, Path installationRoot, MoveOperation mover) throws IOException {
        Path staging = stagingRoot.toAbsolutePath().normalize();
        Path installation = installationRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(staging) || !Files.isRegularFile(staging.resolve("build-info.json"))) {
            throw new IOException("Refusing to deploy an unverified staging tree: " + staging);
        }

        Path parent = installation.getParent();
        if (parent == null) throw new IOException("Local installation has no parent: " + installation);
        Files.createDirectories(parent);
        Files.createDirectories(installation.resolve("data"));
        String transaction = UUID.randomUUID().toString();
        Path next = parent.resolve(installation.getFileName() + ".next-" + transaction);
        Path previous = parent.resolve(installation.getFileName() + ".previous-" + transaction);
        List<String> backedUp = new ArrayList<>();
        List<String> installed = new ArrayList<>();

        try {
            Files.createDirectories(next);
            for (String managed : MANAGED_PATHS) {
                Path source = staging.resolve(managed);
                if (Files.exists(source)) copyRecursively(source, next.resolve(managed));
            }

            Files.createDirectories(previous);
            for (String managed : MANAGED_PATHS) {
                Path current = installation.resolve(managed);
                if (!Files.exists(current)) continue;
                mover.move(current, previous.resolve(managed));
                backedUp.add(managed);
            }
            for (String managed : MANAGED_PATHS) {
                Path replacement = next.resolve(managed);
                if (!Files.exists(replacement)) continue;
                mover.move(replacement, installation.resolve(managed));
                installed.add(managed);
            }
        } catch (Exception failure) {
            IOException rollbackFailure = rollback(installation, previous, installed, backedUp, mover);
            IOException deploymentFailure = failure instanceof IOException io
                    ? io : new IOException("Local installation deployment failed", failure);
            if (rollbackFailure != null) deploymentFailure.addSuppressed(rollbackFailure);
            throw deploymentFailure;
        } finally {
            deleteRecursively(next);
            deleteRecursively(previous);
        }
    }

    private static IOException rollback(Path installation, Path previous, List<String> installed,
            List<String> backedUp, MoveOperation mover) {
        IOException failure = null;
        for (int index = installed.size() - 1; index >= 0; index--) {
            try {
                deleteRecursively(installation.resolve(installed.get(index)));
            } catch (IOException cause) {
                failure = append(failure, cause);
            }
        }
        for (int index = backedUp.size() - 1; index >= 0; index--) {
            String managed = backedUp.get(index);
            try {
                Path backup = previous.resolve(managed);
                if (Files.exists(backup)) mover.move(backup, installation.resolve(managed));
            } catch (IOException cause) {
                failure = append(failure, cause);
            }
        }
        return failure;
    }

    private static IOException append(IOException aggregate, IOException cause) {
        if (aggregate == null) return cause;
        aggregate.addSuppressed(cause);
        return aggregate;
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }
}
