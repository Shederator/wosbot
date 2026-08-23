package dev.frostguard.engine.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Evidence level: automated test reproducing a live crash.
 *
 * <p>The fallback endpoint carried a raw {@code |} between its language codes, which is not legal
 * in a URI query. {@code URI.create} threw {@link IllegalArgumentException}, and being unchecked it
 * travelled out of the translator, out of the row reader, and out of the capture task -- the queue
 * retried the task immediately, so the symptom was chat reopening every few seconds having captured
 * nothing, with no cause anywhere in the log.
 */
class ChatTranslatorTest {

    @Test
    void everyFrontendAssemblesIntoAnAddressThatParses() {
        String body = URLEncoder.encode("hola amigo", StandardCharsets.UTF_8);
        for (String base : ChatTranslator.FRONTENDS) {
            assertDoesNotThrow(() -> URI.create(base + body), "bad address: " + base);
        }
    }

}
