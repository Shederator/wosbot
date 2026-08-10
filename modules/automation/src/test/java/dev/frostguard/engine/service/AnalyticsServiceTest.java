package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    @Test
    void describesDeliveryFailureWithoutStackTrace() {
        Exception failure = new SocketTimeoutException("Connect timed out");

        assertEquals("Connect timed out", AnalyticsService.describeDeliveryFailure(failure));
    }

    @Test
    void fallsBackToExceptionTypeWhenDeliveryFailureHasNoMessage() {
        Exception failure = new SocketTimeoutException();

        assertEquals("SocketTimeoutException", AnalyticsService.describeDeliveryFailure(failure));
    }
}
