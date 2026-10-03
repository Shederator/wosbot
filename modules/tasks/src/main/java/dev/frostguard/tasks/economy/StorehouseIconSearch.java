package dev.frostguard.tasks.economy;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;

/**
 * One way to find Storehouse reward icons on a single frame.
 * The live task uses colour search. Template search stays for comparison.
 */
public interface StorehouseIconSearch {

    List<PointData> find(BufferedImage frame);
}
