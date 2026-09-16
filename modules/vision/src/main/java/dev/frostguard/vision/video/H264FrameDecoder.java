package dev.frostguard.vision.video;

import java.io.InputStream;
import java.nio.ByteBuffer;
import dev.frostguard.api.domain.RawImageData;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/** Owns one decoder. Returned RGBA frames do not reference reused native buffers. */
public final class H264FrameDecoder implements AutoCloseable {
    private final FFmpegFrameGrabber grabber;

    /** Load native libraries before recording so startup cannot accumulate an encoded backlog. */
    public static void prepareRuntime() throws FFmpegFrameGrabber.Exception {
        FFmpegFrameGrabber.tryLoad();
    }

    public H264FrameDecoder(InputStream input) {
        grabber = new FFmpegFrameGrabber(input, 0);
        grabber.setFormat("h264");
        grabber.setOption("probesize", "32");
        grabber.setOption("analyzeduration", "0");
        grabber.setVideoOption("threads", "1");
    }

    public void start() throws FFmpegFrameGrabber.Exception {
        grabber.start();
    }

    public RawImageData nextFrame() throws FFmpegFrameGrabber.Exception {
        Frame frame = grabber.grabImage();
        if (frame == null) return null;
        if (frame.imageDepth != Frame.DEPTH_UBYTE || frame.imageChannels != 3) {
            throw new IllegalStateException("Unsupported decoded video format");
        }
        ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
        byte[] rgba = new byte[frame.imageWidth * frame.imageHeight * 4];
        for (int y = 0, out = 0; y < frame.imageHeight; y++) {
            int row = y * frame.imageStride;
            for (int x = 0; x < frame.imageWidth; x++, out += 4) {
                int in = row + x * 3;
                rgba[out] = source.get(in + 2);
                rgba[out + 1] = source.get(in + 1);
                rgba[out + 2] = source.get(in);
                rgba[out + 3] = (byte) 255;
            }
        }
        return RawImageData.capture(rgba, frame.imageWidth, frame.imageHeight, 32);
    }

    @Override
    public void close() throws FrameGrabber.Exception {
        grabber.close();
    }
}
