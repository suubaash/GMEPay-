package com.gme.pay.scheme.sendmn.api;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.scheme.sendmn.adapter.SendmnSchemeAdapter;
import com.gme.pay.scheme.sendmn.dto.StatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the restart-proof ADR-016 probe endpoint
 * {@code GET /internal/scheme/sendmn/status/by-reference/{reference}}: 200 with the same
 * {@link StatusResponse} shape as the token-keyed status endpoint, and — load-bearing for
 * payment-executor's NOT_FOUND-vs-PENDING policy — an unknown reference renders 404 with
 * the structured {@code ApiError} envelope via {@link ApiExceptionHandler}.
 */
@WebMvcTest(SendmnSchemeController.class)
@Import(ApiExceptionHandler.class)
class SendmnSchemeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SendmnSchemeAdapter adapter;

    @Test
    @DisplayName("status by-reference: known reference → 200 with the canonical StatusResponse")
    void statusByReference_ok() throws Exception {
        when(adapter.statusByReference("ref-hub-1")).thenReturn(
                new StatusResponse("SMN20260727041530ABC234", "APPROVED", "PN-9", "GME-R-9"));

        mvc.perform(get("/internal/scheme/sendmn/status/by-reference/ref-hub-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txTokenNo").value("SMN20260727041530ABC234"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paymentNo").value("PN-9"));
    }

    @Test
    @DisplayName("status by-reference: unknown reference → 404 with structured ApiError body")
    void statusByReference_unknown404() throws Exception {
        when(adapter.statusByReference("ref-nope")).thenThrow(
                new ApiException(ErrorCode.PAYMENT_NOT_FOUND, "status: unknown reference ref-nope"));

        mvc.perform(get("/internal/scheme/sendmn/status/by-reference/ref-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("status: unknown reference ref-nope"))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
