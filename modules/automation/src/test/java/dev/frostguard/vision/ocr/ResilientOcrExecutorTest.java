package dev.frostguard.vision.ocr;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.PointData;

class ResilientOcrExecutorTest {

    @Test
    void rejectsRecognitionWhenCallingThreadIsAlreadyInterrupted() {
        ResilientOcrExecutor<String> executor = new ResilientOcrExecutor<>((config, topLeft, bottomRight) -> "42");
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> executor.attemptRecognition(
                    new PointData(0, 0),
                    new PointData(10, 10),
                    1,
                    0,
                    null,
                    text -> true,
                    text -> text));
        } finally {
            Thread.interrupted();
        }
    }
}
