package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;

/** Candidate leaf search: a crystal-sized green blob, whatever colour its bubble has. */
public final class BlobLifeEssenceSearch implements LifeEssenceLeafSearch {

    @Override
    public List<PointData> find(BufferedImage frame) {
        return IslandClaimBadges.locate(frame);
    }
}
