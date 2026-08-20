package dev.frostguard.vision.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class GameTimeUtilsTest {

    @Test
    void acceptsDayQualifierSplitAcrossLines() {
        String timer = "1d\n10:14:57";

        assertTrue(GameTimeUtils.isAcceptedFormat(timer));
        assertEquals(
                Duration.ofDays(1).plusHours(10).plusMinutes(14).plusSeconds(57),
                GameTimeUtils.parseDuration(timer));
    }

    @Test
    void formatsSingularAndPluralDaysGrammatically() {
        assertTrue(GameTimeUtils.formatCountdown(LocalDateTime.now().plusDays(1).plusMinutes(1))
                .startsWith("1 day "));
        assertTrue(GameTimeUtils.formatCountdown(LocalDateTime.now().plusDays(2).plusMinutes(1))
                .startsWith("2 days "));
    }

    @Test
    void acceptsLeadingLabelColonFromIntelBanner() {
        // The real Intel banner OCR: the whitelist admits ':' so "Refreshes In: 00:02:03"
        // arrives with the label's own colon still attached.
        String timer = ":00:02:03";

        assertTrue(GameTimeUtils.isAcceptedFormat(timer));
        assertEquals(
                Duration.ofMinutes(2).plusSeconds(3),
                GameTimeUtils.parseDuration(timer));
    }

    @Test
    void acceptsLeadingLabelColonAheadOfADayQualifier() {
        // Leading-noise stripping runs before the day qualifier is split off, so a labelled
        // multi-day timer still resolves rather than losing its "1d" prefix.
        String timer = ":1d10:14:57";

        assertTrue(GameTimeUtils.isAcceptedFormat(timer));
        assertEquals(
                Duration.ofDays(1).plusHours(10).plusMinutes(14).plusSeconds(57),
                GameTimeUtils.parseDuration(timer));
    }

    @Test
    void stillRejectsATrailingColonAsAmbiguous() {
        // Deliberate: "12:" could be 12 hours or 12 minutes. Nothing strips trailing noise,
        // so an ambiguous read fails loudly instead of being silently guessed at.
        assertFalse(GameTimeUtils.isAcceptedFormat("12:"));
        assertThrows(IllegalArgumentException.class, () -> GameTimeUtils.parseDuration("12:"));
    }

    @Test
    void stillRejectsTextThatMerelyContainsATimeSpan() {
        // Only leading separator noise is stripped, not arbitrary text -- a caller that needs
        // to pull a span out of a full sentence has to extract it before calling in.
        assertFalse(GameTimeUtils.isAcceptedFormat("refreshes in 00:02:03"));
    }
}
