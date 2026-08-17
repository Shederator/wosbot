package dev.frostguard.app.panel.misc;

import java.time.LocalDateTime;
import java.util.Optional;

import dev.frostguard.app.panel.misc.GiftCodeRedeemer.RedeemResult;
import dev.frostguard.data.entity.GiftCodeClaim;
import dev.frostguard.data.repository.GiftCodeClaimRepository;

/** Bot-local source of truth for terminal gift-code results across all profiles. */
final class GiftCodeHistoryStore {

    private final Backend backend;

    GiftCodeHistoryStore() {
        this(new RepositoryBackend());
    }

    GiftCodeHistoryStore(Backend backend) {
        this.backend = backend;
    }

    boolean wasTerminallyChecked(String playerId, String giftCode) {
        return backend.find(playerId, giftCode).isPresent();
    }

    boolean remember(String playerId, String region, String giftCode, RedeemResult result) {
        if (result == null || !result.terminal()) {
            return false;
        }
        return backend.insertIfAbsent(new HistoryEntry(playerId, region, giftCode,
                result.outcome().name(), result.message(), LocalDateTime.now()));
    }

    boolean importLegacy(String playerId, String region, String giftCode, String rawResult) {
        RedeemResult classified = GiftCodeRedeemer.classify(rawResult);
        if (!classified.terminal()) {
            classified = RedeemResult.failed(rawResult == null ? "Legacy terminal result" : rawResult);
        }
        return backend.insertIfAbsent(new HistoryEntry(playerId, region, giftCode,
                classified.outcome().name(), classified.message(), LocalDateTime.now()));
    }

    interface Backend {
        Optional<HistoryEntry> find(String playerId, String giftCode);
        boolean insertIfAbsent(HistoryEntry entry);
    }

    record HistoryEntry(String playerId,
                        String region,
                        String giftCode,
                        String outcome,
                        String message,
                        LocalDateTime recordedAt) {}

    private static final class RepositoryBackend implements Backend {
        @Override
        public Optional<HistoryEntry> find(String playerId, String giftCode) {
            return GiftCodeClaimRepository.getRepository().find(playerId, giftCode)
                    .map(claim -> new HistoryEntry(claim.getPlayerId(), claim.getRegion(), claim.getGiftCode(),
                            claim.getOutcome(), claim.getMessage(), claim.getRecordedAt()));
        }

        @Override
        public boolean insertIfAbsent(HistoryEntry entry) {
            try {
                return GiftCodeClaimRepository.getRepository().insertIfAbsent(new GiftCodeClaim(
                        entry.playerId(), entry.giftCode(), entry.region(), entry.outcome(),
                        entry.message(), entry.recordedAt()));
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }
}
