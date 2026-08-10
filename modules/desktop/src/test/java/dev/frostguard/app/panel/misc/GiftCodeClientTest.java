package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.app.panel.misc.GiftCodeClient.GiftCodeEntry;

class GiftCodeClientTest {

    private final GiftCodeClient client = new GiftCodeClient();

    @Test
    void parsesDatesAndRemovesDuplicateCodes() throws Exception {
        List<GiftCodeEntry> result = client.parseResponse(
                "{\"codes\":[\"WOS0715 15.07.2026\",\"WOS0715 15.07.2026\",\"4PWagqPw4 15.07.2026\"]}");

        assertEquals(2, result.size());
        assertEquals("WOS0715", result.get(0).code());
        assertEquals(LocalDate.of(2026, 7, 15), result.get(0).discoveredOn());
    }

    @Test
    void keepsUndatedCodesWithoutInventingAnExpiry() throws Exception {
        List<GiftCodeEntry> result = client.parseResponse("{\"codes\":[\"SURVIVAL\"]}");

        assertEquals("SURVIVAL", result.get(0).code());
        assertNull(result.get(0).discoveredOn());
    }
}
