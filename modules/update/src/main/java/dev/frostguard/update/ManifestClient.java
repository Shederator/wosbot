package dev.frostguard.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ManifestClient {
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private final HttpClient client;
    private final SignedManifestCodec codec;

    public ManifestClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new SignedManifestCodec());
    }

    ManifestClient(HttpClient client, SignedManifestCodec codec) {
        this.client = client;
        this.codec = codec;
    }

    public UpdateManifest fetch(URI uri, ManifestVerificationKey trustedKey) throws UpdateException {
        requireHttps(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<java.io.InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new UpdateException("Manifest request returned HTTP " + response.statusCode());
            }
            byte[] body;
            try (java.io.InputStream input = response.body()) {
                body = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            }
            if (body.length > MAX_MANIFEST_BYTES) {
                throw new UpdateException("Manifest exceeds the 1 MiB safety limit");
            }
            return codec.read(body, trustedKey);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Manifest request was interrupted", exception);
        } catch (IOException exception) {
            throw new UpdateException("Manifest request failed: " + exception.getMessage(), exception);
        }
    }

    private static void requireHttps(URI uri) throws UpdateException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new UpdateException("Manifest URL must use HTTPS");
        }
    }
}
