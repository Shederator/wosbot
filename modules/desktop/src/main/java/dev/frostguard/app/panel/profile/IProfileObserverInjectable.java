package dev.frostguard.app.panel.profile;

@FunctionalInterface
public interface IProfileObserverInjectable {

    void attachProfileListener(IProfileChangeObserver listener);

    static boolean tryInjection(Object targetController, IProfileChangeObserver listener) {
        if (targetController instanceof IProfileObserverInjectable inj) {
            inj.attachProfileListener(listener);
            return true;
        }
        return false;
    }

    static IProfileObserverInjectable emptyOp() {
        return l -> {};
    }

    default void removeProfileListener() {
        attachProfileListener(null);
    }
}
