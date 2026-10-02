package dev.frostguard.vision.video;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Copies every byte the live consumer reads to a recording sink. The recording is evidence only:
 * a sink failure is reported once and recording stops, but the live stream is never interrupted.
 */
public final class RecordingInputStream extends FilterInputStream {

    private final OutputStream recording;
    private final Consumer<IOException> recordingFailed;
    private boolean recordingActive = true;

    public RecordingInputStream(InputStream live, OutputStream recording, Consumer<IOException> recordingFailed) {
        super(Objects.requireNonNull(live, "live"));
        this.recording = Objects.requireNonNull(recording, "recording");
        this.recordingFailed = Objects.requireNonNull(recordingFailed, "recordingFailed");
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            record(new byte[] {(byte) value}, 0, 1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            record(buffer, offset, count);
        }
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        // Skipped bytes would leave a gap in the recording; read them through instead.
        byte[] discard = new byte[(int) Math.min(8192, Math.max(0, count))];
        long skipped = 0;
        while (skipped < count) {
            int read = read(discard, 0, (int) Math.min(discard.length, count - skipped));
            if (read < 0) break;
            skipped += read;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            if (recordingActive) {
                try {
                    recording.close();
                } catch (IOException failure) {
                    stopRecording(failure);
                }
            }
        }
    }

    private void record(byte[] buffer, int offset, int count) {
        if (!recordingActive) return;
        try {
            recording.write(buffer, offset, count);
        } catch (IOException failure) {
            stopRecording(failure);
        }
    }

    private void stopRecording(IOException failure) {
        recordingActive = false;
        recordingFailed.accept(failure);
    }
}
