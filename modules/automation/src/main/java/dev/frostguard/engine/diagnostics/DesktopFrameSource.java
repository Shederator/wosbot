package dev.frostguard.engine.diagnostics;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * One attempt to read the operator's desktop. Implementations must not throw.
 */
public interface DesktopFrameSource {

    Optional<BufferedImage> capture();
}
