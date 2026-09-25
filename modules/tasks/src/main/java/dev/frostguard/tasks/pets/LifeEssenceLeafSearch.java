package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;

/**
 * One way to find Life Essence leaves on a single frame.
 * The task uses {@link LifeEssenceSearchKind#COLOR}. Other implementations
 * stay available for comparison.
 */
public interface LifeEssenceLeafSearch {

    List<PointData> find(BufferedImage frame);
}
