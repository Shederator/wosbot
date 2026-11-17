package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;

/** Possible outcomes derived from one frame after tapping Deploy. */
public record DeploymentPostTapRead(
        boolean marchQueueFull,
        ImageSearchResultData confirmationDialog,
        boolean sameTargetDialog,
        ImageSearchResultData deployButton) {
}
