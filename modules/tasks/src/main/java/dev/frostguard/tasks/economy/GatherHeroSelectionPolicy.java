package dev.frostguard.tasks.economy;

final class GatherHeroSelectionPolicy {

    private GatherHeroSelectionPolicy() {
    }

    static Action select(boolean preferredHeroFound, boolean removeAdditionalHeroes,
            boolean noHeroFallback) {
        if (!preferredHeroFound && noHeroFallback) {
            return Action.REMOVE_ALL;
        }
        if (removeAdditionalHeroes) {
            return Action.REMOVE_ADDITIONAL;
        }
        return Action.KEEP_DEFAULT;
    }

    enum Action {
        KEEP_DEFAULT,
        REMOVE_ADDITIONAL,
        REMOVE_ALL
    }
}
