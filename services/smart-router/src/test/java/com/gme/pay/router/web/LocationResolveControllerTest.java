package com.gme.pay.router.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.domain.routing.PartnerSchemeResolver;
import com.gme.pay.router.resolve.LocationSchemeResolver;
import com.gme.pay.router.resolve.SchemeResolution;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /v1/route/resolve} HTTP contract — verifies the Phase 2 canonical
 * error surface: each {@link ErrorCode} branch renders the unified
 * {@code ApiError} envelope ({@code code}/{@code message}/{@code retryable})
 * with the canonical status — 409 for PAYMENT_MODE_NOT_SUPPORTED /
 * DIRECTION_NOT_ENABLED, 404 for NO_SCHEME_FOR_LOCATION, 400 for
 * VALIDATION_ERROR — through {@link RouterApiExceptionHandler}.
 */
@WebMvcTest(LocationResolveController.class)
@Import(RouterApiExceptionHandler.class)
class LocationResolveControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private LocationSchemeResolver resolver;

    /**
     * The {@code @SpringBootApplication} also declares a {@code schemeRouter}
     * {@code @Bean} needing this port; the MVC slice filters out its real
     * adapter, so mock it to let the context build.
     */
    @MockBean
    private PartnerSchemeResolver partnerSchemeResolver;

    @Test
    @DisplayName("happy path: resolver returns scheme -> 200 with chosen scheme")
    void resolvesOk() throws Exception {
        when(resolver.resolve(any()))
                .thenReturn(SchemeResolution.of(List.of("ZEROPAY")));

        mvc.perform(get("/v1/route/resolve")
                        .param("country", "KR")
                        .param("mode", "MPM")
                        .param("direction", "DOMESTIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme").value("ZEROPAY"));
    }

    @Test
    @DisplayName("PAYMENT_MODE_NOT_SUPPORTED -> 409 canonical envelope")
    void paymentModeNotSupportedIs409() throws Exception {
        when(resolver.resolve(any()))
                .thenThrow(new ApiException(ErrorCode.PAYMENT_MODE_NOT_SUPPORTED, "no CPM"));

        mvc.perform(get("/v1/route/resolve")
                        .param("country", "KH")
                        .param("mode", "CPM")
                        .param("direction", "INBOUND"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MODE_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("DIRECTION_NOT_ENABLED -> 409 canonical envelope")
    void directionNotEnabledIs409() throws Exception {
        when(resolver.resolve(any()))
                .thenThrow(new ApiException(ErrorCode.DIRECTION_NOT_ENABLED, "inbound-only"));

        mvc.perform(get("/v1/route/resolve")
                        .param("country", "VN")
                        .param("mode", "MPM")
                        .param("direction", "OUTBOUND"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECTION_NOT_ENABLED"));
    }

    @Test
    @DisplayName("NO_SCHEME_FOR_LOCATION -> 404 canonical envelope")
    void noSchemeIs404() throws Exception {
        when(resolver.resolve(any()))
                .thenThrow(new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION, "nothing wired"));

        mvc.perform(get("/v1/route/resolve")
                        .param("country", "JP")
                        .param("mode", "MPM")
                        .param("direction", "INBOUND"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SCHEME_FOR_LOCATION"));
    }

    @Test
    @DisplayName("network param present -> ordered candidate list (ADR-016 failover)")
    void networkReturnsOrderedCandidateList() throws Exception {
        PartnerSchemeView primary = new PartnerSchemeView(20L, "NEPAL", "BOTH", null, null, null,
                null, null, null, null, null, Boolean.TRUE, "NP", true, true, 0, "ACTIVE",
                "fonepay.com,nepalpay");
        PartnerSchemeView failover = new PartnerSchemeView(21L, "NEPAL_FONEPAY_DIRECT", "BOTH",
                null, null, null, null, null, null, null, null, Boolean.TRUE, "NP", true, true, 1,
                "ACTIVE", "fonepay.com");
        when(resolver.resolveCandidates(eq("fonepay.com"), any()))
                .thenReturn(List.of(primary, failover));

        mvc.perform(get("/v1/route/resolve")
                        .param("network", "fonepay.com")
                        .param("country", "NP")
                        .param("mode", "MPM")
                        .param("direction", "DOMESTIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].schemeId").value("NEPAL"))
                .andExpect(jsonPath("$[0].networkIdentifier").value("fonepay.com,nepalpay"))
                .andExpect(jsonPath("$[1].schemeId").value("NEPAL_FONEPAY_DIRECT"));
    }

    @Test
    @DisplayName("network unknown -> NO_SCHEME_FOR_LOCATION 404 canonical envelope")
    void networkUnknownIs404() throws Exception {
        when(resolver.resolveCandidates(any(), any()))
                .thenThrow(new ApiException(ErrorCode.NO_SCHEME_FOR_LOCATION, "nothing serves it"));

        mvc.perform(get("/v1/route/resolve")
                        .param("network", "com.unknown")
                        .param("country", "NP")
                        .param("mode", "MPM")
                        .param("direction", "DOMESTIC"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_SCHEME_FOR_LOCATION"));
    }

    @Test
    @DisplayName("blank network falls back to country-based ResolveResponse (existing behavior)")
    void blankNetworkFallsBackToCountryResolve() throws Exception {
        when(resolver.resolve(any()))
                .thenReturn(SchemeResolution.of(List.of("ZEROPAY")));

        mvc.perform(get("/v1/route/resolve")
                        .param("network", "")
                        .param("country", "KR")
                        .param("mode", "MPM")
                        .param("direction", "DOMESTIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme").value("ZEROPAY"));
    }

    @Test
    @DisplayName("unknown payment mode -> 400 VALIDATION_ERROR (controller-level parse)")
    void badModeIs400() throws Exception {
        mvc.perform(get("/v1/route/resolve")
                        .param("country", "KR")
                        .param("mode", "WALLET")
                        .param("direction", "DOMESTIC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
