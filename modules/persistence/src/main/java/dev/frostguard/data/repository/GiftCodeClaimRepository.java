package dev.frostguard.data.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.entity.GiftCodeClaim;

/** Persistence gateway for the bot-local canonical gift-code history. */
public final class GiftCodeClaimRepository {

    private static GiftCodeClaimRepository instance;
    private final DataStore store = DataStore.getInstance();

    private GiftCodeClaimRepository() {}

    public static synchronized GiftCodeClaimRepository getRepository() {
        if (instance == null) {
            instance = new GiftCodeClaimRepository();
        }
        return instance;
    }

    public Optional<GiftCodeClaim> find(String playerId, String giftCode) {
        List<GiftCodeClaim> results = store.executeQuery(
                "SELECT g FROM GiftCodeClaim g WHERE g.playerId = :playerId AND g.giftCode = :giftCode",
                GiftCodeClaim.class, Map.of("playerId", playerId, "giftCode", giftCode));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean insertIfAbsent(GiftCodeClaim claim) {
        return store.withinTransaction(entityManager -> {
            List<GiftCodeClaim> existing = entityManager.createQuery(
                            "SELECT g FROM GiftCodeClaim g WHERE g.playerId = :playerId AND g.giftCode = :giftCode",
                            GiftCodeClaim.class)
                    .setParameter("playerId", claim.getPlayerId())
                    .setParameter("giftCode", claim.getGiftCode())
                    .setMaxResults(1)
                    .getResultList();
            if (!existing.isEmpty()) {
                return true;
            }
            entityManager.persist(claim);
            return true;
        });
    }
}
