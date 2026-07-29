package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.BalanceDeductionEntry;
import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for GET /v1/balance (backlog 5.2-T27): OVERSEAS below/not-below the
 * threshold, the LOCAL-partner 403, money serialized as a decimal string (not a JSON number), and —
 * added under T0-2 — the tenancy rules that closed the header-swap IDOR.
 *
 * <p><b>What changed (T0-2).</b> This endpoint used to take the partner from {@code X-Partner-Id}
 * (defaulting to {@code 1}) and the partner type from {@code X-Partner-Type} (defaulting to
 * {@code OVERSEAS}), falling back to that header whenever config-registry could not resolve
 * {@code X-Partner-Code}. The tests below now pin the opposite: {@code X-Partner-Code} is mandatory,
 * the type comes only from config-registry, and an unavailable registry refuses rather than guesses.
 * The transport-level half of the fix (the endpoint requires the internal-auth token) is proved over
 * real HTTP in {@link SandboxE2eSurfaceTest}.
 */
class BalanceControllerTest {

    private static final String CODE = "PTNR-OS";

    private PrefundingClient prefundingClient;
    private PartnerConfigClient partnerConfigClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        prefundingClient = mock(PrefundingClient.class);
        partnerConfigClient = mock(PartnerConfigClient.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BalanceController controller = new BalanceController(prefundingClient, partnerConfigClient);
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    /** config-registry's authoritative answer for {@link #CODE}. */
    private void registryReturns(String type) {
        when(partnerConfigClient.loadPartner(CODE)).thenReturn(
                new PartnerConfigClient.PartnerConfigView("101", type, "USD", RoundingMode.DOWN));
    }

    @Test
    void overseas_aboveThreshold_returns200NotBelow_moneyAsString() throws Exception {
        registryReturns("OVERSEAS");
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("48234.5600"), new BigDecimal("10000.00"), "USD"));

        mvc.perform(get("/v1/balance").header("X-Partner-Code", CODE))
                .andExpect(status().isOk())
                // partner_id is echoed from the REGISTRY view, never from a request header
                .andExpect(jsonPath("$.partner_id").value(101))
                .andExpect(jsonPath("$.is_below_threshold").value(false))
                // money must be a JSON STRING, not a number
                .andExpect(jsonPath("$.balance_usd").value("48234.5600"));
    }

    @Test
    void overseas_belowThreshold_returns200Below() throws Exception {
        registryReturns("OVERSEAS");
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("9500.00"), new BigDecimal("10000.00"), "USD"));

        mvc.perform(get("/v1/balance").header("X-Partner-Code", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_below_threshold").value(true));
    }

    @Test
    void overseas_includeHistory_appendsRecentDeductions() throws Exception {
        registryReturns("OVERSEAS");
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("48234.5600"), new BigDecimal("10000.00"), "USD"));
        when(prefundingClient.deductionHistory(eq(CODE), eq(20)))
                .thenReturn(new PrefundingDeductionHistoryView(CODE,
                        List.of(new BalanceDeductionEntry(new BigDecimal("12.50"),
                                Instant.parse("2026-06-29T01:00:00Z"), "txn_9")), 20));

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Code", CODE)
                        .param("include_history", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recent_deductions[0].amountUsd").value("12.50"))
                .andExpect(jsonPath("$.recent_deductions[0].txnRef").value("txn_9"));
    }

    @Test
    void overseas_withoutHistory_omitsRecentDeductionsAndDoesNotCallHistory() throws Exception {
        registryReturns("OVERSEAS");
        when(prefundingClient.balance(anyString())).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("48234.5600"), new BigDecimal("10000.00"), "USD"));

        mvc.perform(get("/v1/balance").header("X-Partner-Code", CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recent_deductions").doesNotExist());

        verify(prefundingClient, never()).deductionHistory(anyString(), anyInt());
    }

    @Test
    void local_returns403Forbidden() throws Exception {
        registryReturns("LOCAL");

        mvc.perform(get("/v1/balance").header("X-Partner-Code", CODE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------------
    // T0-2 tenancy: no default partner, no caller-asserted partner type
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("no X-Partner-Code → 400, and prefunding is never queried (was: partner 1's float)")
    void missingPartnerCodeIsRejectedRatherThanDefaultedToPartnerOne() throws Exception {
        mvc.perform(get("/v1/balance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // The heart of the IDOR: a header-less request used to return partner 1's balance.
        verifyNoInteractions(prefundingClient);
    }

    @Test
    @DisplayName("blank X-Partner-Code → 400 (whitespace is not a partner)")
    void blankPartnerCodeIsRejected() throws Exception {
        mvc.perform(get("/v1/balance").header("X-Partner-Code", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(prefundingClient);
    }

    @Test
    @DisplayName("a caller-asserted X-Partner-Type cannot override the registry — LOCAL stays 403")
    void callerCannotAssertPartnerTypeToBypassTheLocalRefusal() throws Exception {
        registryReturns("LOCAL");

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Code", CODE)
                        // the retired escalation vector: claim OVERSEAS for a LOCAL partner
                        .header("X-Partner-Type", "OVERSEAS"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(prefundingClient);
    }

    @Test
    @DisplayName("a caller-asserted X-Partner-Id is ignored — the registry's id is echoed")
    void callerSuppliedPartnerIdIsIgnored() throws Exception {
        registryReturns("OVERSEAS");
        when(prefundingClient.balance(CODE)).thenReturn(new PrefundingClient.BalanceSnapshot(
                new BigDecimal("1.00"), null, "USD"));

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Code", CODE)
                        .header("X-Partner-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner_id").value(101));

        // And the prefunding lookup is keyed on the CODE, never on a caller-supplied id.
        verify(prefundingClient).balance(CODE);
    }

    @Test
    @DisplayName("config-registry unreachable → 500, never a fallback to the caller's claim")
    void unavailableRegistryRefusesRatherThanTrustingTheHeader() throws Exception {
        when(partnerConfigClient.loadPartner(CODE))
                .thenThrow(new PaymentException("config-registry unreachable"));

        mvc.perform(get("/v1/balance")
                        .header("X-Partner-Code", CODE)
                        .header("X-Partner-Type", "OVERSEAS"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        verifyNoInteractions(prefundingClient);
    }

    @Test
    @DisplayName("unknown partner code → 400, no prefunding call")
    void unknownPartnerCodeIsRejected() throws Exception {
        when(partnerConfigClient.loadPartner(CODE)).thenReturn(null);

        mvc.perform(get("/v1/balance").header("X-Partner-Code", CODE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(prefundingClient);
    }
}
