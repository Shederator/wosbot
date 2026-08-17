package dev.frostguard.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkDownloadTransport implements DownloadTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Response open(URI uri, long offset) throws IOException, InterruptedException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("Artifact URL must use HTTPS");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (offset > 0) {
            request.header("Range", "bytes=" + offset + "-");
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Response() {
            @Override
            public int statusCode() {
                return response.statusCode();
            }

            @Override
            public InputStream body() {
                return response.body();
            }

            @Override
            public void close() throws IOException {
                response.body().close();
            }
        };
    }
}
