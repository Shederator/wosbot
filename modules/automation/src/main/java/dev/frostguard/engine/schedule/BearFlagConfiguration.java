package dev.frostguard.engine.schedule;

import java.util.List;
import java.util.Optional;

/**
 * Validates the own-rally and join formations. A conflicting or empty configuration is refused
 * visibly; it used to disable joining silently, and the shipped defaults (both formation 1) did
 * exactly that.
 */
public final class BearFlagConfiguration {

    public record Validation(List<Integer> joinFlags, Optional<String> refusal) {
    }

    private BearFlagConfiguration() {
    }

    public static Validation validate(boolean callOwnRally, int ownFlag, boolean joinRally, List<Integer> joinFlags) {
        List<Integer> flags = List.copyOf(joinFlags);
        if (!joinRally) {
            return new Validation(flags, Optional.empty());
        }
        if (flags.isEmpty()) {
            return new Validation(flags, Optional.of(
                    "Bear joining is enabled but no valid join formation is configured"));
        }
        if (callOwnRally && flags.contains(ownFlag)) {
            return new Validation(flags, Optional.of("Bear own rally and joining both use formation "
                    + ownFlag + "; choose different join formations"));
        }
        return new Validation(flags, Optional.empty());
    }
}
