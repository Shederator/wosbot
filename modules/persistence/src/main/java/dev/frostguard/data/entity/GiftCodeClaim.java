package dev.frostguard.data.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Canonical terminal gift-code result for one player in this bot instance. */
@Entity
@Table(name = "gift_code_claim",
        uniqueConstraints = @UniqueConstraint(name = "uk_gift_code_claim_player_code",
                columnNames = {"player_id", "gift_code"}))
@Access(AccessType.FIELD)
public class GiftCodeClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "gift_code", nullable = false)
    private String giftCode;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    public GiftCodeClaim() {}

    public GiftCodeClaim(String playerId, String giftCode, String region,
                         String outcome, String message, LocalDateTime recordedAt) {
        this.playerId = playerId;
        this.giftCode = giftCode;
        this.region = region == null ? "" : region;
        this.outcome = outcome;
        this.message = message;
        this.recordedAt = recordedAt == null ? LocalDateTime.now() : recordedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public String getGiftCode() { return giftCode; }
    public void setGiftCode(String giftCode) { this.giftCode = giftCode; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
}
