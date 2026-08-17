package dev.frostguard.engine.listener;

import dev.frostguard.api.domain.ProfileStatusData;

import java.util.Objects;
import java.util.function.Consumer;

// Receives display-oriented status updates for automation profiles.
// Carries formatted strings rather than raw state-machine data.
public interface ProfileStatusChangeListener {

    // Delivers a refreshed status descriptor for one profile.
    void onAccountStatusUpdated(ProfileStatusData state);

    // Clears the status display for the given account.
    default void onAccountStatusCleared(Long accountId) {
        onAccountStatusUpdated(null);
    }

    // Creates a listener that routes every non-null update through the given consumer.
    static ProfileStatusChangeListener of(Consumer<ProfileStatusData> sink) {
        Objects.requireNonNull(sink, "sink");
        return new ProfileStatusChangeListener() {
            @Override
            public void onAccountStatusUpdated(ProfileStatusData state) {
                if (state != null) sink.accept(state);
            }

            @Override
            public void onAccountStatusCleared(Long accountId) {
                // sink only gets positive updates — skip clears
            }
        };
    }

    // Returns a filtered view that only forwards events for a single account.
    default ProfileStatusChangeListener filterByAccount(Long targetAccountId) {
        ProfileStatusChangeListener outer = this;
        return new ProfileStatusChangeListener() {
            @Override
            public void onAccountStatusUpdated(ProfileStatusData state) {
                if (state == null) {
                    outer.onAccountStatusUpdated(null);
                    return;
                }
                if (Objects.equals(state.getProfileRef(), targetAccountId)) {
                    outer.onAccountStatusUpdated(state);
                }
            }

            @Override
            public void onAccountStatusCleared(Long accountId) {
                if (Objects.equals(accountId, targetAccountId)) {
                    outer.onAccountStatusCleared(accountId);
                }
            }
        };
    }
}
