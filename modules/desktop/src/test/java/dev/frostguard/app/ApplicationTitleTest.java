package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationTitleTest {
    @Test
    void identifiesChannelInstanceAndVersion() {
        assertEquals("Bearguard · default · v2.1.0",
                ApplicationTitle.format(RuntimeChannel.STABLE, "2.1.0", "default"));
        assertEquals("Bearguard Nightly · bot-2 · v26.8.1",
                ApplicationTitle.format(RuntimeChannel.NIGHTLY, "26.8.1", "bot-2"));
        assertEquals("Bearguard Development · fix/arena-refresh-budget · v3.0.0-dev",
                ApplicationTitle.format(RuntimeChannel.DEVELOPMENT, "3.0.0-dev", "fix/arena-refresh-budget"));
        assertEquals("Bearguard Development · source-export",
                ApplicationTitle.format(RuntimeChannel.DEVELOPMENT, null, "source-export"));
    }
}
