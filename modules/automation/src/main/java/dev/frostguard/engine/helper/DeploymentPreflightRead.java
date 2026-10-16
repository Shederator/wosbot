package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.ImageSearchResultData;

/** Safety and numeric evidence derived from one stable pre-deploy frame. */
public record DeploymentPreflightRead(
        DeploymentScreenRead deployment,
        boolean noDeployableTroops,
        boolean deployCostRed,
        ImageSearchResultData deployButton) {
}
