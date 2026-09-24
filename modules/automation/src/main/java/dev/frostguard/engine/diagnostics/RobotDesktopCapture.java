package dev.frostguard.engine.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Captures every attached screen with {@link Robot}.
 * Used on Windows and X11. macOS follows this path too and is not tested;
 * Screen Recording permission is not requested or checked here.
 */
final class RobotDesktopCapture {

    private static final Logger logger = LoggerFactory.getLogger(RobotDesktopCapture.class);

    private RobotDesktopCapture() {
    }

    static Optional<BufferedImage> capture() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                logger.warn("Desktop snapshot skipped because this session has no display.");
                return Optional.empty();
            }
            Rectangle bounds = new Rectangle();
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                bounds = bounds.union(device.getDefaultConfiguration().getBounds());
            }
            if (bounds.width <= 0 || bounds.height <= 0) {
                logger.warn("Desktop snapshot skipped because no screen bounds were reported.");
                return Optional.empty();
            }
            return Optional.of(new Robot().createScreenCapture(bounds));
        } catch (AWTException | RuntimeException failure) {
            logger.warn("Desktop snapshot was not captured: {}", failure.toString());
            return Optional.empty();
        }
    }
}
