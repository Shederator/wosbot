package dev.frostguard.engine.chat;

import dev.frostguard.api.chat.ChatLineCleaner;
import dev.frostguard.api.chat.ChatMessage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders foreign chat into English over the network, holding nothing locally.
 *
 * <p>Frostguard ships to people who did not ask for a translation stack, so the constraint is that
 * this must add no install size, no service to run and no key to obtain. That rules out shipping
 * language models -- the offline engines are hundreds of megabytes -- and rules out the hosted
 * APIs that need an account. What is left is a keyless public endpoint, which is what this uses.
 *
 * <p>World chat is multilingual and unpredictable, so no language list is maintained: the endpoint
 * detects the source itself. Three things keep the request volume low enough for that to be
 * reasonable:
 *
 * <ul>
 *   <li>English is recognised locally by {@link ChatLineCleaner#looksEnglish} and never sent;</li>
 *   <li>results are cached by normalised text, and game chat repeats heavily;</li>
 *   <li>a failure returns empty rather than retrying, so the transcript degrades to the original
 *       text instead of stalling behind the network.</li>
 * </ul>
 *
 * <p>The primary endpoint is unofficial and may rate-limit or change without notice. That is the
 * accepted risk of the no-key constraint, and it is why there is a documented fallback and why
 * every caller must treat an empty result as normal.
 */
public final class ChatTranslator {

    /**
     * Instances of a keyless translation frontend, tried in order.
     *
     * <p>These replaced the previous primary because both earlier endpoints failed at once and
     * translation stopped dead: the Google endpoint now answers 429 from this address, and the
     * documented fallback exhausted its anonymous daily allowance and started returning a quota
     * notice in place of a translation. Probed live, these instances answered Spanish, Turkish,
     * Korean and Arabic correctly and did not rate-limit across rapid calls.
     *
     * <p>More than one is listed because this class of service is volunteer-run and does go down --
     * two mirrors of a comparable frontend were returning 500 during the same probe. A list costs
     * nothing and turns an outage into a slower pass rather than a silent loss of translation.
     */
    static final String[] FRONTENDS = {
        "https://simplytranslate.org/api/translate/?engine=google&from=auto&to=en&text=",
        "https://st.adast.dk/api/translate/?engine=google&from=auto&to=en&text=",
        "https://simplytranslate.ducks.party/api/translate/?engine=google&from=auto&to=en&text=",
        "https://simplytranslate.aketawi.space/api/translate/?engine=google&from=auto&to=en&text=",
    };

    private static final int MAX_CHARS = 1200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final Map<String, String> cache;
    private final boolean enabled;

    public ChatTranslator(boolean enabled, int cacheSize) {
        this.enabled = enabled;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cacheSize;
            }
        });
    }

    /**
     * @return the English rendering, or empty when the body is already English, translation is
     *         switched off, or the lookup did not succeed
     */
    public Optional<String> toEnglish(String body) {
        if (!enabled || body == null || body.isBlank() || ChatLineCleaner.looksEnglish(body)) {
            return Optional.empty();
        }
        String key = ChatLineCleaner.cacheKey(body);
        String hit = cache.get(key);
        if (hit != null) {
            return hit.isEmpty() ? Optional.empty() : Optional.of(hit);
        }

        // Mentions are names, and a name is not a word to be translated. Sent whole, "@AfinaValkyrie
        // quando finalizar" came back with the name itself rendered into English, which turns a
        // player nobody can now identify into gibberish in the middle of a sentence. The mentions
        // are taken off, the sentence is translated, and they go back exactly as the game wrote
        // them.
        String head = leadingMentions(body);
        String rest = body.substring(head.length());
        if (rest.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rest.length() > MAX_CHARS ? rest.substring(0, MAX_CHARS) : rest;
        // One provider, deliberately. The endpoints that used to sit behind this were a Google
        // address that now answers 429 from here and a service with a daily allowance that ran out
        // mid-evening and started returning its quota notice where the translation belonged. A
        // capped provider is not a fallback -- it is a thing that works until it silently does not,
        // and it stopped translation dead once already. Better to translate through instances that
        // do not meter, and render nothing at all when none of them answer.
        String result = viaFrontends(trimmed).orElse("");

        // Cache the failure too. A body that cannot be translated will arrive again on the next
        // overlapping scroll-back, and re-requesting it every pass is how a keyless endpoint
        // starts refusing service.
        cache.put(key, result);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(head.isEmpty() ? result : head + result);
    }

    /**
     * The run of mentions a message opens with, exactly as the game wrote them.
     *
     * <p>Only the leading run. A name inside a sentence is rare and cannot be lifted out without
     * changing the word order the translator needs, whereas the "@Name" a reply opens with carries
     * no meaning for it at all.
     */
    static String leadingMentions(String body) {
        int i = 0;
        while (i < body.length()) {
            int start = i;
            while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
                start++;
            }
            if (start >= body.length() || body.charAt(start) != '@') {
                break;
            }
            int end = start;
            // A name can carry a space -- "@Mini TyTy" -- so take a following capitalised word too.
            for (int word = 0; word < 2; word++) {
                while (end < body.length() && !Character.isWhitespace(body.charAt(end))) {
                    end++;
                }
                int peek = end;
                while (peek < body.length() && Character.isWhitespace(body.charAt(peek))) {
                    peek++;
                }
                if (word == 0 && peek < body.length() && Character.isUpperCase(body.charAt(peek))
                        && body.charAt(peek) != '@') {
                    end = peek;
                    continue;
                }
                break;
            }
            i = end;
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
        }
        return body.substring(0, i);
    }

    /**
     * A provider that answers with a notice instead of a translation has not translated anything.
     *
     * <p>The capped fallback returns its quota message as an ordinary 200 with the text sitting in
     * the same field a translation would occupy, so without this check the transcript quietly fills
     * with "YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY" attributed to players.
     */
    private static boolean isProviderNotice(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("MYMEMORY WARNING")
                || upper.contains("ALL AVAILABLE FREE TRANSLATIONS")
                || upper.contains("USAGE LIMIT")
                || upper.contains("QUOTA");
    }

    /**
     * Whether what came back is a translation at all.
     *
     * <p>A frontend is a proxy, and when the service behind it fails the frontend does not
     * necessarily fail with it: it answers 200, with well-formed JSON, and the upstream's HTML
     * error page sitting in the field where the translation belongs. Probed live, a one-word
     * Indonesian message came back as two kilobytes of Google's 500 page -- stylesheet, inline
     * script and all -- which would have been stored as that player's message and shown to a
     * reader as something they said.
     *
     * <p>Two things give it away without knowing any language. Markup is not prose, and a
     * translation is roughly the length of its source: rendering between these languages moves the
     * length by a third or so, never by a factor of ten. Both are checked because either alone lets
     * something through -- a short error string carries no markup, and a legitimately long
     * translation of a long message carries no tags.
     */
    private static boolean isNotATranslation(String out, String source) {
        if (MARKUP.matcher(out).find()) {
            return true;
        }
        return out.length() > source.length() * LENGTH_BLOWOUT + LENGTH_SLACK;
    }

    /** Tags and script the upstream's error page is made of; no player types these. */
    private static final java.util.regex.Pattern MARKUP = java.util.regex.Pattern.compile(
            "(?i)<\\s*(/?)(html|head|body|style|script|div|span|meta|title|p|a|pre|ins|img)\\b"
                    + "|<!doctype|function\\s*\\(\\)|document\\.(get|open|close|add)");

    /** How much longer than its source a real translation can plausibly be. */
    private static final int LENGTH_BLOWOUT = 6;

    /** Room for a very short source, where the ratio alone is too sharp to be fair. */
    private static final int LENGTH_SLACK = 80;

    private Optional<String> viaFrontends(String body) {
        String encoded = URLEncoder.encode(body, StandardCharsets.UTF_8);
        for (String base : FRONTENDS) {
            Optional<String> raw = fetch(base + encoded);
            if (raw.isEmpty()) {
                continue;
            }
            try {
                String out = MAPPER.readTree(raw.get()).path("translated_text").asText("").trim();
                if (!out.isEmpty() && !isProviderNotice(out) && !isNotATranslation(out, body)) {
                    return Optional.of(out);
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                // Try the next instance rather than giving up on translation altogether.
            }
        }
        return Optional.empty();
    }



    private Optional<String> fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Frostguard")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? Optional.of(res.body()) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException is URI.create rejecting the assembled address. It used to
            // escape this method, and because it is unchecked it travelled all the way out of the
            // capture task -- the queue retried the task at once, so a bad address showed up as
            // chat restarting every few seconds with nothing captured and no cause logged. A
            // translation is a nicety; failing to build its URL must not end the pass.
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** How many distinct phrases have been resolved, for the capture log. */
    public int cachedPhrases() {
        return cache.size();
    }
}
