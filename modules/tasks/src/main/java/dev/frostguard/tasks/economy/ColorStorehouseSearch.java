package dev.frostguard.tasks.economy;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;

/** Live search: white bubble with a crate-wood or stamina-copper interior. */
public final class ColorStorehouseSearch implements StorehouseIconSearch {

    @Override
    public List<PointData> find(BufferedImage frame) {
        return StorehouseBubbleDetector.locate(frame).stream()
                .map(StorehouseBubbleDetector.Candidate::center)
                .toList();
    }
}
