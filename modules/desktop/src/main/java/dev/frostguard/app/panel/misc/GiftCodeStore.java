package dev.frostguard.app.panel.misc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.ConfigService;

/** Persists gift-code state inside each owning profile's local database config. */
final class GiftCodeStore {

    private static final String AUTO_ENABLED = "autoEnabled";
    private static final String LAST_CHECK_UTC = "lastCheckUtc";
    private static final String RECIPIENTS = "recipients";
    private static final String CLAIMS = "claims";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Function<AccountDescriptor, String> reader;
    private final BiFunction<AccountDescriptor, String, Boolean> writer;

    GiftCodeStore() {
        this(profile -> profile.getConfig(ConfigurationKeyEnum.GIFT_CODE_STATE_JSON, String.class),
                (profile, json) -> ConfigService.obtain().writeAccountSetting(
                        profile, ConfigurationKeyEnum.GIFT_CODE_STATE_JSON, json));
    }

    GiftCodeStore(Function<AccountDescriptor, String> reader,
                  BiFunction<AccountDescriptor, String, Boolean> writer) {
        this.reader = reader;
        this.writer = writer;
    }

    boolean isAutoEnabled(AccountDescriptor profile) {
        return read(profile).path(AUTO_ENABLED).asBoolean(false);
    }

    boolean setAutoEnabled(AccountDescriptor profile, boolean enabled) {
        ObjectNode root = read(profile);
        root.put(AUTO_ENABLED, enabled);
        return write(profile, root);
    }

    LocalDate lastCheckUtc(AccountDescriptor profile) {
        String stored = read(profile).path(LAST_CHECK_UTC).asText("");
        try {
            return stored.isBlank() ? null : LocalDate.parse(stored);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    boolean setLastCheckUtc(AccountDescriptor profile, LocalDate date) {
        ObjectNode root = read(profile);
        if (date == null) {
            root.remove(LAST_CHECK_UTC);
        } else {
            root.put(LAST_CHECK_UTC, date.toString());
        }
        return write(profile, root);
    }

    boolean saveExtraRecipient(AccountDescriptor owner, String playerId, String alias, String region) {
        ObjectNode root = read(owner);
        ObjectNode recipients = root.withObject(RECIPIENTS);
        ObjectNode recipient = recipients.putObject(playerId);
        recipient.put("alias", alias == null || alias.isBlank() ? "Player " + playerId : alias.trim());
        recipient.put("region", region == null ? "" : region.trim());
        return write(owner, root);
    }

    boolean removeExtraRecipient(AccountDescriptor owner, String playerId) {
        ObjectNode root = read(owner);
        root.withObject(RECIPIENTS).remove(playerId);
        return write(owner, root);
    }

    List<GiftCodeRecipient> extraRecipients(AccountDescriptor owner) {
        List<GiftCodeRecipient> result = new ArrayList<>();
        JsonNode recipients = read(owner).path(RECIPIENTS);
        recipients.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String alias = value.isObject()
                    ? value.path("alias").asText(entry.getKey())
                    : value.asText(entry.getKey());
            String region = value.isObject()
                    ? value.path("region").asText("")
                    : normalizedRegion(owner);
            result.add(new GiftCodeRecipient(owner.getId(), owner.getName(), entry.getKey(),
                    alias, region, false));
        });
        result.sort(Comparator.comparing(GiftCodeRecipient::alias, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    boolean migrateLegacyRecipients(AccountDescriptor owner) {
        ObjectNode root = read(owner);
        ObjectNode recipients = root.withObject(RECIPIENTS);
        boolean changed = false;
        Iterator<Map.Entry<String, JsonNode>> fields = recipients.fields();
        List<Map.Entry<String, String>> legacy = new ArrayList<>();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isObject()) {
                legacy.add(Map.entry(entry.getKey(), entry.getValue().asText(entry.getKey())));
            }
        }
        for (Map.Entry<String, String> entry : legacy) {
            ObjectNode migrated = recipients.putObject(entry.getKey());
            migrated.put("alias", entry.getValue());
            migrated.put("region", normalizedRegion(owner));
            changed = true;
        }
        return !changed || write(owner, root);
    }

    List<LegacyClaim> legacyClaims(AccountDescriptor owner) {
        List<LegacyClaim> result = new ArrayList<>();
        JsonNode claims = read(owner).path(CLAIMS);
        claims.fields().forEachRemaining(player -> player.getValue().fields().forEachRemaining(code -> {
            try {
                String decodedCode = new String(Base64.getUrlDecoder().decode(code.getKey()), StandardCharsets.UTF_8);
                String region = regionForPlayer(owner, player.getKey());
                result.add(new LegacyClaim(player.getKey(), region, decodedCode, code.getValue().asText("")));
            } catch (IllegalArgumentException ignored) {
                // Preserve valid legacy entries while ignoring an unreadable key.
            }
        }));
        return result;
    }

    private ObjectNode read(AccountDescriptor profile) {
        try {
            JsonNode parsed = objectMapper.readTree(reader.apply(profile));
            return parsed instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean write(AccountDescriptor profile, ObjectNode root) {
        try {
            return Boolean.TRUE.equals(writer.apply(profile, objectMapper.writeValueAsString(root)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String regionForPlayer(AccountDescriptor owner, String playerId) {
        if (playerId != null && playerId.equals(normalizedPlayerId(owner))) {
            return normalizedRegion(owner);
        }
        return extraRecipients(owner).stream()
                .filter(recipient -> recipient.playerId().equals(playerId))
                .map(GiftCodeRecipient::region)
                .findFirst()
                .orElse(normalizedRegion(owner));
    }

    private String normalizedPlayerId(AccountDescriptor owner) {
        return owner.getCharacterId() == null ? "" : owner.getCharacterId().trim();
    }

    private String normalizedRegion(AccountDescriptor owner) {
        return owner.getCharacterServer() == null ? "" : owner.getCharacterServer().trim();
    }

    record GiftCodeRecipient(Long ownerProfileId,
                             String ownerProfileName,
                             String playerId,
                             String alias,
                             String region,
                             boolean managedProfile) {
        String label() {
            return alias + " (" + playerId + ", region "
                    + (region == null || region.isBlank() ? "missing" : region) + ")";
        }
    }

    record LegacyClaim(String playerId, String region, String giftCode, String result) {}
}
