package com.gme.pay.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import com.gme.pay.ledger.revenue.RevenueRecord;
import com.gme.pay.ledger.revenue.RevenueRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for {@code POST /v1/revenue/capture}: a fresh capture persists (201),
 * a duplicate txnRef replays (200, no second save), and a missing txnRef is rejected (400).
 */
class RevenueCaptureControllerTest {

    private static final String BODY = """
            {"txnRef":"TXN-1","partnerId":7,"schemeId":1,"revenueDate":"2026-06-15",
             "collectionMarginUsd":"1.00","payoutMarginUsd":"0.50",
             "serviceChargeAmount":"500","serviceChargeCcy":"KRW","feeSharePct":"0.70"}
            """;

    private RevenueRecordStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(RevenueRecordStore.class);
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new RevenueCaptureController(new RevenueCaptureService(store)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void freshCapture_persistsAndReturns201() throws Exception {
        when(store.findByTxnRef("TXN-1")).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/v1/revenue/capture")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.txnRef").value("TXN-1"))
                .andExpect(jsonPath("$.fxMarginUsd").value("1.50"))      // 1.00 + 0.50
                .andExpect(jsonPath("$.serviceChargeAmount").value("500"))
                .andExpect(jsonPath("$.serviceChargeCcy").value("KRW"));

        verify(store).save(any());
    }

    @Test
    void duplicateTxnRef_replaysWithoutSaving() throws Exception {
        RevenueRecord existing = RevenueRecord.of("TXN-1", 7L, 1L, LocalDate.of(2026, 6, 15),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                new BigDecimal("500"), "KRW", new BigDecimal("0.70"));
        when(store.findByTxnRef("TXN-1")).thenReturn(Optional.of(existing));

        mvc.perform(post("/v1/revenue/capture")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnRef").value("TXN-1"));

        verify(store, never()).save(any());
    }

    @Test
    void missingTxnRef_returns400() throws Exception {
        String noRef = """
                {"partnerId":7,"schemeId":1,"revenueDate":"2026-06-15",
                 "collectionMarginUsd":"1.00","payoutMarginUsd":"0.50",
                 "serviceChargeAmount":"500","serviceChargeCcy":"KRW","feeSharePct":"0.70"}
                """;
        mvc.perform(post("/v1/revenue/capture")
                        .contentType(MediaType.APPLICATION_JSON).content(noRef))
                .andExpect(status().isBadRequest());

        verify(store, never()).findByTxnRef(anyString());
    }
}
