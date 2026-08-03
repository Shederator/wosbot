package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class GiftCodeStoreTest {

    private final Map<Long, String> persisted = new HashMap<>();
    private final GiftCodeStore store = new GiftCodeStore(
            profile -> persisted.getOrDefault(profile.getId(), "{}"),
            (profile, json) -> {
                persisted.put(profile.getId(), json);
                profile.setConfig(dev.frostguard.api.configs.ConfigurationKeyEnum.GIFT_CODE_STATE_JSON, json);
                return true;
            });
    private final AccountDescriptor profileOne = profile(1L, "One", "4508");
    private final AccountDescriptor profileTwo = profile(2L, "Two", "4509");

    @Test
    void storesAutoCheckAndRegionAwareRecipientsPerProfile() {
        store.setAutoEnabled(profileOne, true);
        store.setLastCheckUtc(profileOne, LocalDate.of(2026, 7, 16));
        store.saveExtraRecipient(profileOne, "123456", "Alex", "4508");

        assertTrue(store.isAutoEnabled(profileOne));
        assertFalse(store.isAutoEnabled(profileTwo));
        assertEquals(LocalDate.of(2026, 7, 16), store.lastCheckUtc(profileOne));
        assertEquals("4508", store.extraRecipients(profileOne).get(0).region());
        assertTrue(store.extraRecipients(profileTwo).isEmpty());
    }

    @Test
    void migratesLegacyRecipientAndExposesLegacyClaimsWithoutDataLoss() {
        persisted.put(profileOne.getId(), """
                {"autoEnabled":true,"lastCheckUtc":"2026-07-21",
                 "recipients":{"797458026":"Zorome"},
                 "claims":{"797458026":{"Z29nb1dPUw":"RECEIVED."}}}
                """);

        assertTrue(store.migrateLegacyRecipients(profileOne));

        var recipient = store.extraRecipients(profileOne).get(0);
        assertEquals("Zorome", recipient.alias());
        assertEquals("4508", recipient.region());
        var claim = store.legacyClaims(profileOne).get(0);
        assertEquals("797458026", claim.playerId());
        assertEquals("4508", claim.region());
        assertEquals("gogoWOS", claim.giftCode());
        assertEquals("RECEIVED.", claim.result());
        assertTrue(persisted.get(profileOne.getId()).contains("\"claims\""));
    }

    private AccountDescriptor profile(Long id, String name, String region) {
        AccountDescriptor profile = new AccountDescriptor(id, name, "1", true, 50L, 0L);
        profile.setCharacterServer(region);
        return profile;
    }
}
