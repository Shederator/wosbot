package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.data.repository.ProfileRepository;

import java.util.function.BiFunction;

// Decoupled factory for DelayedTask creation. The actual task construction
// Logic lives in the tasks module and is registered at startup via registerFactory.
public class DelayedTaskRegistry {

    private static volatile BiFunction<TpDailyTaskEnum, AccountDescriptor, DelayedTask> taskFactory;

    // Installs the application-wide task factory. Must be called once at bootstrap.
    public static void registerFactory(
            BiFunction<TpDailyTaskEnum, AccountDescriptor, DelayedTask> factory) {
        taskFactory = factory;
    }

    // Produces a fresh DelayedTask for the requested type and profile.
    // Refreshes the profile from the database first.
    public static DelayedTask create(TpDailyTaskEnum type, AccountDescriptor profile) {
        if (taskFactory == null) {
            throw new IllegalStateException(
                    "DelayedTaskRegistry uninitialised - call registerFactory() at startup.");
        }

        AccountDescriptor active = profile;
        if (active != null && active.getId() != null) {
            AccountDescriptor refreshed = ProfileRepository.getRepository()
                    .getAccountWithSettingsById(active.getId());
            if (refreshed != null) active = refreshed;
        }

        DelayedTask result = taskFactory.apply(type, active);
        if (result == null) {
            throw new IllegalArgumentException("No task mapping for type: " + type);
        }
        return result;
    }
}
