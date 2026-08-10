package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.app.panel.misc.GiftCodeHistoryStore.HistoryEntry;
import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemOutcome;
import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemResult;

class GiftCodeHistoryStoreTest {

    private final Map<String, HistoryEntry> entries = new LinkedHashMap<>();
    private final GiftCodeHistoryStore history = new GiftCodeHistoryStore(new GiftCodeHistoryStore.Backend() {
        @Override
        public Optional<HistoryEntry> find(String playerId, String giftCode) {
            return Optional.ofNullable(entries.get(key(playerId, giftCode)));
        }

        @Override
        public boolean insertIfAbsent(HistoryEntry entry) {
            entries.putIfAbsent(key(entry.playerId(), entry.giftCode()), entry);
            return true;
        }
    });

    @Test
    void onePlayerAndCodeHasOneCanonicalResultAcrossProfiles() {
        assertTrue(history.remember("123", "4508", "CODE",
                new RedeemResult("SUCCESS", RedeemOutcome.REDEEMED, true)));
        assertTrue(history.remember("123", "4508", "CODE",
                new RedeemResult("RECEIVED.", RedeemOutcome.ALREADY_REDEEMED, true)));

        assertTrue(history.wasTerminallyChecked("123", "CODE"));
        assertEquals(1, entries.size());
        assertEquals("REDEEMED", entries.values().iterator().next().outcome());
        assertFalse(history.wasTerminallyChecked("456", "CODE"));
    }

    @Test
    void importsLegacyTerminalResultIntoCanonicalHistory() {
        assertTrue(history.importLegacy("123", "4508", "OLD", "RECEIVED."));

        assertTrue(history.wasTerminallyChecked("123", "OLD"));
        assertEquals("ALREADY_REDEEMED", entries.values().iterator().next().outcome());
    }

    private String key(String playerId, String giftCode) {
        return playerId + "\u0000" + giftCode;
    }
}
