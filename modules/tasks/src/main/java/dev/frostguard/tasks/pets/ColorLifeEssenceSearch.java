package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;

/** Leaf search used by the task: an orange region with a green leaf above its center. */
public final class ColorLifeEssenceSearch implements LifeEssenceLeafSearch {

    @Override
    public List<PointData> find(BufferedImage frame) {
        return LifeEssenceMarkerDetector.locate(frame);
    }
}
