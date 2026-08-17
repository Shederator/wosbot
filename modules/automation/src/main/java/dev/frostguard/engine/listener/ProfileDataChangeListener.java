package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.AccountDescriptor;

// Reacts to mutations on persisted profile data — creation, modification, and deletion.
public interface ProfileDataChangeListener {

    // A profile's persisted data has been modified and committed.
    void onAccountDataModified(AccountDescriptor profile);

    // A new profile was created. Routes to onAccountDataModified by default.
    default void onAccountCreated(AccountDescriptor profile) {
        onAccountDataModified(profile);
    }

    // A profile has been permanently deleted from storage.
    default void onAccountRemoved(Long accountId) {}

    // Checks whether this listener cares about the given account.
    default boolean appliesTo(Long accountId) {
        return true;
    }
}
