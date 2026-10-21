package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;

/** Visual evidence from one stable formation-screen frame. */
public record DeploymentFormationRead(
        boolean marchQueueFull,
        ImageSearchResultData deployButton,
        ImageSearchResultData equalizeButton) {
}
