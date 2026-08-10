package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemOutcome;

class GiftCodeRedeemerTest {

    private final GiftCodeRedeemer redeemer = new GiftCodeRedeemer();

    @Test
    void matchesTheCurrentOfficialRegionAwareRequestSignature() {
        Map<String, String> fields = redeemer.requestFields(
                "797458026", "1", "TESTONLY", 1784683335L);
        Map<String, String> signed = redeemer.signed(fields);

        assertEquals("797458026", signed.get("fid"));
        assertEquals("1", signed.get("kid"));
        assertEquals("TESTONLY", signed.get("cdk"));
        assertEquals("1784683335", signed.get("time"));
        assertEquals("b2cb433cb3972f812f6b323c9336b669", signed.get("sign"));
        assertFalse(signed.containsKey("captcha_code"));
    }

    @Test
    void distinguishesRedeemedAlreadyRedeemedFailedAndRetryableResults() {
        assertEquals(RedeemOutcome.REDEEMED, GiftCodeRedeemer.classify("SUCCESS").outcome());
        assertEquals(RedeemOutcome.ALREADY_REDEEMED, GiftCodeRedeemer.classify("RECEIVED.").outcome());
        assertEquals(RedeemOutcome.FAILED, GiftCodeRedeemer.classify("Gift code expired").outcome());
        assertEquals(RedeemOutcome.RETRYABLE_ERROR, GiftCodeRedeemer.classify("Server busy").outcome());
        assertTrue(GiftCodeRedeemer.classify("SUCCESS").terminal());
        assertFalse(GiftCodeRedeemer.classify("Server busy").terminal());
    }
}
