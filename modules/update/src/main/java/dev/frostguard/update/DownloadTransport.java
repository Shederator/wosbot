package dev.frostguard.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public interface DownloadTransport {
    Response open(URI uri, long offset) throws IOException, InterruptedException;

    interface Response extends AutoCloseable {
        int statusCode();
        InputStream body();

        @Override
        void close() throws IOException;
    }
}
