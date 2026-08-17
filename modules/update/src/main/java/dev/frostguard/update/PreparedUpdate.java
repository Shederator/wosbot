package dev.frostguard.update;

import java.nio.file.Path;

public record PreparedUpdate(UpdateCandidate candidate, Path installer) {
}
