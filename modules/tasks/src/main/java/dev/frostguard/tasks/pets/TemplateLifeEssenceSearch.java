package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * The original leaf search: {@code claimCurrent.png} at 90 percent, then
 * {@code claim.png} when that finds nothing. The search area starts below
 * the status bar. Match centers are the points.
 */
public final class TemplateLifeEssenceSearch implements LifeEssenceLeafSearch {

    private static final AreaData SEARCH_AREA = new AreaData(new PointData(0, 65), new PointData(720, 1280));
    private static final double THRESHOLD = 90;
    private static final int MAX_RESULTS = 5;

    private final byte[] encodedPng;

    public TemplateLifeEssenceSearch(byte[] encodedPng) {
        this.encodedPng = encodedPng;
    }

    @Override
    public List<PointData> find(BufferedImage frame) {
        loadOpenCv();
        List<ImageSearchResultData> matches = locate(TemplatesEnum.LIFE_ESSENCE_CLAIM_CURRENT);
        if (matches.isEmpty()) {
            matches = locate(TemplatesEnum.LIFE_ESSENCE_CLAIM);
        }
        List<PointData> points = new ArrayList<>();
        for (ImageSearchResultData match : matches) {
            PointData point = match.getPoint();
            if (point != null) {
                points.add(point);
            }
        }
        return List.copyOf(points);
    }

    private List<ImageSearchResultData> locate(TemplatesEnum template) {
        return OpenCvPatternLocator.locateAllPatterns(
                encodedPng,
                template,
                SEARCH_AREA.topLeft(),
                SEARCH_AREA.bottomRight(),
                THRESHOLD,
                MAX_RESULTS);
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
