package com.gme.pay.qr.controller;

import com.gme.pay.qr.domain.cpm.CpmGenerateService;
import com.gme.pay.qr.domain.cpm.CpmToken;
import com.gme.pay.qr.exception.DuplicatePartnerTxnRefException;
import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MockMvc tests for the CPM generate endpoint status mapping (WBS 5.3-T08). */
@WebMvcTest(CpmController.class)
class CpmControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CpmGenerateService generateService;

    private static final String BODY = """
            {"schemeId":"zeropay","direction":"inbound","customerRef":"cust_9f8e",
             "partnerTxnRef":"SENDMN-CPM-0042","countryCode":"KR"}""";

    @Test
    void validRequestReturns201WithToken() throws Exception {
        Instant now = Instant.now();
        when(generateService.createSession(any(), any(), any(), any(), any()))
                .thenReturn(new CpmToken("TOK", "PMT-1", "ZP-CPM-ABC", "QR:ZP-CPM-ABC",
                        "ZEROPAY", "SENDMN-CPM-0042", now, now.plusSeconds(60)));

        mvc.perform(post("/v1/qr/cpm/generate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prepareToken").value("ZP-CPM-ABC"))
                .andExpect(jsonPath("$.paymentId").value("PMT-1"))
                .andExpect(jsonPath("$.schemeId").value("ZEROPAY"));
    }

    @Test
    void duplicatePartnerTxnRefReturns409() throws Exception {
        when(generateService.createSession(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicatePartnerTxnRefException("SENDMN-CPM-0042"));

        mvc.perform(post("/v1/qr/cpm/generate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_PARTNER_TXN_REF"));
    }

    @Test
    void noSchemeForLocationReturns422() throws Exception {
        when(generateService.createSession(any(), any(), any(), any(), any()))
                .thenThrow(new QRParseException(QRErrorCode.NO_SCHEME_FOR_LOCATION, "no scheme"));

        mvc.perform(post("/v1/qr/cpm/generate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NO_SCHEME_FOR_LOCATION"));
    }

    @Test
    void missingCountryCodeReturns400() throws Exception {
        String bad = """
                {"schemeId":"zeropay","direction":"inbound","customerRef":"c","partnerTxnRef":"R1"}""";
        mvc.perform(post("/v1/qr/cpm/generate").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }
}
