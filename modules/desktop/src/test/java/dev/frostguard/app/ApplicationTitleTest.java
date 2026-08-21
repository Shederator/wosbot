package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationTitleTest {
    @Test
    void identifiesChannelInstanceAndVersion() {
        assertEquals("Frostguard · default · v2.1.0",
                ApplicationTitle.format(RuntimeChannel.STABLE, "2.1.0", "default"));
        assertEquals("Frostguard Nightly · bot-2 · v26.8.1",
                ApplicationTitle.format(RuntimeChannel.NIGHTLY, "26.8.1", "bot-2"));
        assertEquals("Frostguard Development · fix/arena-refresh-budget · v3.0.0-dev",
                ApplicationTitle.format(RuntimeChannel.DEVELOPMENT, "3.0.0-dev", "fix/arena-refresh-budget"));
        assertEquals("Frostguard Development · source-export",
                ApplicationTitle.format(RuntimeChannel.DEVELOPMENT, null, "source-export"));
    }
}
