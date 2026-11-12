package dev.frostguard.tasks.city;

/** In-memory daily progress for {@link BakeryFarmCollectRoutine}. */
enum BakeryFarmCollectProgress {
    INACTIVE,
    READY,
    FAILED_RETRY,
    COMPLETED_SUCCESS,
    COMPLETED_UNVERIFIED,
    PARTIAL_RESCHEDULED,
    FAILED_RESCHEDULED
}
