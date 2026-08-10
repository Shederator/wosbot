package dev.frostguard.api.domain;

import java.util.Objects;

/**
 * Raw framebuffer capture from an emulator screen, carrying
 * pixel data and dimensional metadata.
 */
public class RawImageData {

    private byte[] frameBytes;
    private int scanlineWidth;
    private int scanlineCount;
    private int colorDepth;

    /* ── static factory ── */

    public static RawImageData capture(byte[] pixels, int width, int height, int bpp) {
        RawImageData d = new RawImageData();
        d.frameBytes = pixels;
        d.scanlineWidth = width;
        d.scanlineCount = height;
        d.colorDepth = bpp;
        return d;
    }

    /* ── no-arg for frameworks ── */
    public RawImageData() {}

    /* ── derived ── */

    public int pixelCount()  { return scanlineWidth * scanlineCount; }
    public int stride()      { return scanlineWidth * colorDepth; }

    public boolean isValid() {
        return frameBytes != null
            && scanlineWidth > 0 && scanlineCount > 0 && colorDepth > 0
            && frameBytes.length >= scanlineWidth * scanlineCount * colorDepth;
    }

    /* ── accessors ── */

    public byte[] getFrameBytes()               { return frameBytes; }
    public void setFrameBytes(byte[] b)         { this.frameBytes = b; }

    public int getScanlineWidth()               { return scanlineWidth; }
    public void setScanlineWidth(int w)         { this.scanlineWidth = w; }

    public int getScanlineCount()               { return scanlineCount; }
    public void setScanlineCount(int h)         { this.scanlineCount = h; }

    public int getColorDepth()                  { return colorDepth; }
    public void setColorDepth(int d)            { this.colorDepth = d; }

    /* ── legacy delegates ── */

    public byte[] getPixelBuffer()              { return frameBytes; }
    public void setPixelBuffer(byte[] b)        { this.frameBytes = b; }
    public int getFrameWidth()                  { return scanlineWidth; }
    public void setFrameWidth(int w)            { this.scanlineWidth = w; }
    public int getFrameHeight()                 { return scanlineCount; }
    public void setFrameHeight(int h)           { this.scanlineCount = h; }
    public int getBytesPerPixel()               { return colorDepth; }
    public void setBytesPerPixel(int d)         { this.colorDepth = d; }
    public int getWidth()                       { return scanlineWidth; }
    public int getHeight()                      { return scanlineCount; }
    public byte[] getData()                     { return frameBytes; }
    public int getBpp()                         { return colorDepth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawImageData that)) return false;
        return scanlineWidth == that.scanlineWidth
            && scanlineCount == that.scanlineCount
            && colorDepth == that.colorDepth;
    }

    @Override
    public int hashCode() { return Objects.hash(scanlineWidth, scanlineCount, colorDepth); }

    @Override
    public String toString() {
        return "RawImage{" + scanlineWidth + "x" + scanlineCount + " @" + colorDepth + "bpp}";
    }
}
