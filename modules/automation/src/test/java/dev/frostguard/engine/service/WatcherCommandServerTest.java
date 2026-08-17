package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.runtime.WorkspacePaths;

class WatcherCommandServerTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsCommandsFromAnotherWorkspaceAndIdentifiesItsPid() throws Exception {
        String previous = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        WatcherCommandServer server = null;
        try {
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
            int port;
            try (ServerSocket availablePort = new ServerSocket(0)) {
                port = availablePort.getLocalPort();
            }

            server = new WatcherCommandServer(port, null);
            server.start();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> mismatch = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/command"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"type\":\"ping\",\"workspaceId\":\"another-workspace\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, mismatch.statusCode());

            String workspaceId = WorkspacePaths.current().identity();
            HttpResponse<String> matching = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/command"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"type\":\"ping\",\"workspaceId\":\"" + workspaceId + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, matching.statusCode());

            HttpResponse<String> pid = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/pid"))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, pid.statusCode());
            assertTrue(pid.body().contains("\"workspaceId\":\"" + workspaceId + "\""));
        } finally {
            if (server != null) {
                server.stop();
            }
            if (previous == null) {
                System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            } else {
                System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previous);
            }
        }
    }
}
