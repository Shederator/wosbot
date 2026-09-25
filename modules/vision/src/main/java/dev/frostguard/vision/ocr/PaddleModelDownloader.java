package dev.frostguard.vision.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads PaddleOCR ONNX models to {@code tools/paddle/} on first use.
 *
 * <p>Models are sourced from Hugging Face to guarantee reproducibility.
 * Each file is verified by SHA-256 after download. Partially downloaded files
 * are deleted on failure to prevent a corrupt cache.
 *
 * Expected model files:
 * <ul>
 *   <li>{@code ch_PP-OCRv4_det_infer.onnx}  — text detection (PP-OCRv4)
 *   <li>{@code en_PP-OCRv3_rec_infer.onnx}  — English-only text recognition (PP-OCRv3);
 *       English-only model prevents hallucination of CJK characters on textured backgrounds.
 *   <li>{@code ch_ppocr_mobile_v2.0_cls_train.onnx} — text direction classification
 * </ul>
 */
public final class PaddleModelDownloader {

    private static final Logger log = LoggerFactory.getLogger(PaddleModelDownloader.class);

    private static final String HF_BASE = "https://huggingface.co/SWHL/RapidOCR/resolve/main/";

    record ModelFile(String path, String name, String sha256) {}

    private static final List<ModelFile> REQUIRED_MODELS = List.of(
            new ModelFile("PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
                          "ch_PP-OCRv4_det_infer.onnx",
                          "D2A7720D45A54257208B1E13E36A8479894CB74155A5EFE29462512D42F49DA9"),
            new ModelFile("PP-OCRv3/en_PP-OCRv3_rec_infer.onnx",
                          "en_PP-OCRv3_rec_infer.onnx",
                          "EF7ABD8BD3629AE57EA2C28B425C1BD258A871B93FD2FE7C433946ADE9B5D9EA"),
            new ModelFile("PP-OCRv3/ch_ppocr_mobile_v2.0_cls_train.onnx",
                          "ch_ppocr_mobile_v2.0_cls_train.onnx",
                          "70581B300B83BABD9E0DD1D7D74C5B006869E8796DA277A70C2E405BF9D77C82")
    );

    private PaddleModelDownloader() {}

    /**
     * Ensures all required model files are present and valid in {@code modelsDir}.
     * Downloads any that are absent or corrupt.
     *
     * @throws OcrException if any model cannot be downloaded or verified
     */
    public static void ensureModels(Path modelsDir) throws OcrException {
        try {
            Files.createDirectories(modelsDir);
        } catch (IOException e) {
            throw new OcrException("Cannot create Paddle models directory: " + modelsDir, e);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (ModelFile model : REQUIRED_MODELS) {
            Path target = modelsDir.resolve(model.name());
            if (Files.exists(target) && verifySha256(target, model.sha256())) {
                log.debug("Paddle model verified: {}", model.name());
                continue;
            }
            log.info("Downloading Paddle model: {}", model.name());
            downloadWithVerify(client, HF_BASE + model.path(), target, model.sha256());
        }
    }


    private static void downloadWithVerify(HttpClient client, String url,
                                           Path dest, String expectedSha256)
            throws OcrException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .GET().build();
            HttpResponse<Path> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofFile(dest));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(dest);
                throw new OcrException("Failed to download " + url
                        + " — HTTP " + resp.statusCode());
            }
            if (!verifySha256(dest, expectedSha256)) {
                Files.deleteIfExists(dest);
                throw new OcrException("SHA-256 mismatch for " + dest.getFileName()
                        + " — file may be corrupt, retry or check the model URL");
            }
            log.info("Downloaded and verified: {}", dest.getFileName());
        } catch (IOException | InterruptedException e) {
            try { Files.deleteIfExists(dest); } catch (IOException ignored) {}
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OcrException("Download failed: " + dest.getFileName(), e);
        }
    }

    private static boolean verifySha256(Path file, String expected) throws OcrException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            String actual = HexFormat.of().formatHex(md.digest());
            return actual.equalsIgnoreCase(expected);
        } catch (Exception e) {
            throw new OcrException("SHA-256 verification failed for " + file, e);
        }
    }
}
