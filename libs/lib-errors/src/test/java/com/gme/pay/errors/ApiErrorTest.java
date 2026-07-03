package com.gme.pay.errors;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.correlation.CorrelationHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Unit tests for {@link ApiError} requestId resolution (explicit → MDC correlation id → UUID). */
class ApiErrorTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("explicit non-blank requestId is honored (back-compat, wins over MDC)")
    void explicitRequestIdWins() {
        MDC.put(CorrelationHeaders.MDC_KEY, "abc");
        ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "bad", "explicit-id");
        assertThat(error.requestId()).isEqualTo("explicit-id");
    }

    @Test
    @DisplayName("null requestId + MDC correlationId=abc → requestId == abc")
    void nullRequestIdResolvesFromMdc() {
        MDC.put(CorrelationHeaders.MDC_KEY, "abc");
        ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "bad", null);
        assertThat(error.requestId()).isEqualTo("abc");
    }

    @Test
    @DisplayName("null requestId + empty MDC → non-null UUID-shaped id")
    void nullRequestIdAndEmptyMdcFallsBackToUuid() {
        ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "bad", null);
        assertThat(error.requestId())
                .isNotNull()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("2-arg of(..) resolves from MDC correlation id")
    void twoArgFactoryResolvesFromMdc() {
        MDC.put(CorrelationHeaders.MDC_KEY, "corr-2arg");
        ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR, "bad");
        assertThat(error.requestId()).isEqualTo("corr-2arg");
    }
}
