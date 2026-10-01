package dev.frostguard.tasks.economy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The live search: chest crops at {@link StorehouseChestRoutine#CHEST_SEARCH_THRESHOLD},
 * then the stamina can at 90.
 */
public final class TemplateStorehouseSearch implements StorehouseIconSearch {

    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData LIMIT = new PointData(720, 1280);
    private static final double STAMINA_THRESHOLD = 90;

    private final byte[] encodedPng;

    public TemplateStorehouseSearch(byte[] encodedPng) {
        this.encodedPng = encodedPng;
    }

    @Override
    public List<PointData> find(BufferedImage frame) {
        loadOpenCv();
        List<PointData> points = new ArrayList<>();
        for (TemplatesEnum template : new TemplatesEnum[] {
                TemplatesEnum.STOREHOUSE_CHEST,
                TemplatesEnum.STOREHOUSE_CHEST_2,
                TemplatesEnum.STOREHOUSE_CHEST_3
        }) {
            ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                    encodedPng, template, ORIGIN, LIMIT, StorehouseChestRoutine.CHEST_SEARCH_THRESHOLD);
            if (hit.isFound() && hit.getPoint() != null) {
                points.add(hit.getPoint());
                break;
            }
        }
        ImageSearchResultData stamina = OpenCvPatternLocator.locatePattern(
                encodedPng, TemplatesEnum.STOREHOUSE_STAMINA, ORIGIN, LIMIT, STAMINA_THRESHOLD);
        if (stamina.isFound() && stamina.getPoint() != null) {
            points.add(stamina.getPoint());
        }
        return List.copyOf(points);
    }

    private static void loadOpenCv() {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError alreadyLoaded) {
            // Another caller loaded OpenCV in this process.
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
