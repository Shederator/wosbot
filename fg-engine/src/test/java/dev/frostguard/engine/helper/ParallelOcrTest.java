
package dev.frostguard.engine.helper;
import dev.frostguard.vision.ocr.*;
import dev.frostguard.api.configs.*;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.concurrent.*;
import java.util.stream.*;
import java.util.List;

public class ParallelOcrTest {
    @Test
    public void testParallel() throws Exception {
        OcrEngine.setActiveEngine(OcrEngineType.PADDLE_ONNX);
        File testImg = new File("src/test/resources/fixtures/polar-after-equalize-20260709.png");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<CompletableFuture<String>> futures = IntStream.range(0, 4)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                try {
                    return OcrEngine.readFromFile(testImg, 0, 0, 200, 200, "eng");
                } catch (Exception e) {
                    return e.toString();
                }
            }, executor))
            .collect(Collectors.toList());
            
        for (CompletableFuture<String> f : futures) {
            System.out.println("Result: " + f.get().length() + " chars");
        }
        executor.shutdown();
    }
}

