package dev.frostguard.engine.listener;

// Receives energy-level mutation events for tracked accounts.
// Callbacks fire on OCR reads, regen ticks, and explicit API adjustments.
public interface StaminaChangeListener {

    // The energy level for accountId was updated.
    void onEnergyLevelChanged(Long accountId, int currentStamina);

    // An authoritative absolute read was applied, for example during startup OCR.
    default void onStaminaSynchronized(Long accountId, int currentStamina) {}

    // An explicit positive addition was applied, unlike an OCR correction,
    // deduction, or passive regeneration tick.
    default void onStaminaAdded(Long accountId, int amount, int currentStamina) {}

    // Called before the tracker sweeps all accounts for regeneration.
    default void onRegenerationSweepStarting() {}

    // Called after the regeneration sweep completes.
    default void onRegenerationSweepFinished(int accountsAffected) {}
}
