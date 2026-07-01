package com.gme.sim.nepalqr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc tests for the Nepal QR simulator.
 *  T01 validate fonepay merchant shape
 *  T02 validate khalti JSON shape
 *  T03 validate mobank JSON shape
 *  T04 validate missing token -> 403
 *  T05 parse returns merchant fields (raw {qs} body)
 *  T06 pay creates txn + stores record + APPROVED
 *  T07 pay duplicate reference -> 400 validation_error
 *  T08 status returns APPROVED for created reference
 *  T09 status returns Error for unknown reference
 *  T10 records inspection returns stored req/resp incl decoded payload
 *  T11 same-origin UI pay creates txn + records it (used by the web console)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class NepalQrControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final String FONEPAY_QR =
            "00020101021126350011fonepay.com071640897200000017835204541253035245802NP"
          + "5914SudanMerchant6015AathraiTriveni62060702316304d60f";

    private String signedBody(Map<String, Object> payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        String data = Base64.getEncoder().encodeToString(json.getBytes());
        return mapper.writeValueAsString(Map.of("data", data, "signature", "fake-sig=="));
    }

    // T01
    @Test
    void t01_validateFonepay() throws Exception {
        mvc.perform(post("/api/qr/validate/")
                        .header("Authorization", "Token abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qr\":\"" + FONEPAY_QR + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("fonepay"))
                .andExpect(jsonPath("$.merchant_id").isNotEmpty())
                .andExpect(jsonPath("$.currency").value("NPR"))
                .andExpect(jsonPath("$.extra.merchant_city").value("AathraiTriveni"))
                .andExpect(jsonPath("$.extra.country").value("NP"));
    }

    // T02
    @Test
    void t02_validateKhalti() throws Exception {
        String qr = "{\\\"network\\\":\\\"khalti\\\",\\\"name\\\":\\\"Ram\\\",\\\"mobile\\\":\\\"9812345678\\\"}";
        mvc.perform(post("/api/v2/qr/validate/")
                        .header("Authorization", "Token abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qr\":\"" + qr + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("khalti"))
                .andExpect(jsonPath("$.name").value("Ram"))
                .andExpect(jsonPath("$.mobile").value("9812345678"));
    }

    // T03
    @Test
    void t03_validateMobank() throws Exception {
        String qr = "{\\\"network\\\":\\\"mobank\\\",\\\"name\\\":\\\"Sita\\\",\\\"account_number\\\":\\\"1234567890\\\","
                + "\\\"bank\\\":{\\\"swift_code\\\":\\\"NICENPKA\\\",\\\"name\\\":\\\"NIC Asia Bank\\\"}}";
        mvc.perform(post("/api/qr/validate/")
                        .header("Authorization", "Token abc123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qr\":\"" + qr + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("mobank"))
                .andExpect(jsonPath("$.account_number").value("1234567890"))
                .andExpect(jsonPath("$.bank.swift_code").value("NICENPKA"));
    }

    // T04
    @Test
    void t04_validateMissingToken403() throws Exception {
        mvc.perform(post("/api/qr/validate/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qr\":\"" + FONEPAY_QR + "\"}"))
                .andExpect(status().isForbidden());
    }

    // T05 — parse accepts a raw {qs} body (not encrypted)
    @Test
    void t05_parseReturnsMerchantFields() throws Exception {
        mvc.perform(post("/qrscan-thirdparty/parse/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qs\":\"" + FONEPAY_QR + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantName").value("SudanMerchant"))
                .andExpect(jsonPath("$.merchantCity").value("AathraiTriveni"))
                .andExpect(jsonPath("$.merchantCountry").value("NP"))
                .andExpect(jsonPath("$.trxCurrency").value("NPR"))
                .andExpect(jsonPath("$.merchantCategoryCode").value("5412"));
    }

    // T06 — pay creates txn + stores record
    @Test
    void t06_payCreatesTxn() throws Exception {
        String ref = "PAY-T06-" + System.nanoTime();
        String body = signedBody(Map.of(
                "nonce", "1234567890", "qs", FONEPAY_QR, "amount", "1000",
                "mobile", "9800000000", "reference", ref,
                "purpose", "ServicePayment", "remarks", "NetTV"));
        mvc.perform(post("/qrscan-thirdparty/pay/")
                        .header("Authorization", "Key testkey")
                        .header("X-KhaltiNonce", "1234567890")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idx").isNotEmpty())
                .andExpect(jsonPath("$.amount").value("1000"))
                .andExpect(jsonPath("$.type").value("ScanandPay"))
                .andExpect(jsonPath("$.meta.balance.on_hold").value("1000"));

        // txn stored under reference
        mvc.perform(get("/sim/nepal-qr/txns/{ref}", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.amountPaisa").value(1000));
    }

    // T07 — duplicate reference
    @Test
    void t07_duplicateReference() throws Exception {
        String ref = "PAY-T07-" + System.nanoTime();
        String body = signedBody(Map.of(
                "nonce", "1", "qs", FONEPAY_QR, "amount", "500",
                "reference", ref, "purpose", "P"));
        mvc.perform(post("/qrscan-thirdparty/pay/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/qrscan-thirdparty/pay/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_key").value("validation_error"))
                .andExpect(jsonPath("$.reference").value("Duplicate reference." + ref));
    }

    // T08 — status APPROVED
    @Test
    void t08_statusApproved() throws Exception {
        String ref = "PAY-T08-" + System.nanoTime();
        mvc.perform(post("/qrscan-thirdparty/pay/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(Map.of("nonce", "1", "amount", "700",
                                "reference", ref, "purpose", "P"))))
                .andExpect(status().isOk());
        mvc.perform(post("/qrscan-thirdparty/status/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(Map.of("nonce", "1", "amount", 700, "reference", ref))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
    }

    // T09 — status unknown reference -> Error
    @Test
    void t09_statusUnknownReference() throws Exception {
        mvc.perform(post("/qrscan-thirdparty/status/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(Map.of("nonce", "1", "amount", 1, "reference", "NOPE-XYZ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Error"));
    }

    // T10 — records inspection incl. decoded payload
    @Test
    void t10_recordsStoreDecodedPayload() throws Exception {
        String ref = "PAY-T10-" + System.nanoTime();
        mvc.perform(post("/qrscan-thirdparty/pay/").header("Authorization", "Key k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(Map.of("nonce", "1", "amount", "1500",
                                "reference", ref, "purpose", "P", "qs", FONEPAY_QR))))
                .andExpect(status().isOk());

        mvc.perform(get("/sim/nepal-qr/records").param("reference", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endpoint").value("/qrscan-thirdparty/pay/"))
                .andExpect(jsonPath("$[0].reference").value(ref))
                .andExpect(jsonPath("$[0].idx").isNotEmpty())
                .andExpect(jsonPath("$[0].responseStatus").value(200))
                .andExpect(jsonPath("$[0].decodedPayload.reference").value(ref))
                .andExpect(jsonPath("$[0].decodedPayload.amount").value("1500"));
    }

    // T11 — same-origin UI pay (web console) creates txn + records req/resp
    @Test
    void t11_uiPayCreatesTxnAndRecords() throws Exception {
        String ref = "UI-T11-" + System.nanoTime();
        String body = mapper.writeValueAsString(Map.of(
                "qs", FONEPAY_QR, "amountPaisa", "1000", "reference", ref,
                "mobile", "9800000000", "purpose", "ServicePayment", "remarks", "console"));
        mvc.perform(post("/sim/nepal-qr/ui/pay")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idx").isNotEmpty())
                .andExpect(jsonPath("$.amount").value("1000"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.meta.balance.on_hold").value("1000"));

        // txn stored under reference
        mvc.perform(get("/sim/nepal-qr/txns/{ref}", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.amountPaisa").value(1000));

        // request/response recorded with decoded payload
        mvc.perform(get("/sim/nepal-qr/records").param("reference", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].endpoint").value("/sim/nepal-qr/ui/pay"))
                .andExpect(jsonPath("$[0].reference").value(ref))
                .andExpect(jsonPath("$[0].idx").isNotEmpty())
                .andExpect(jsonPath("$[0].responseStatus").value(200))
                .andExpect(jsonPath("$[0].decodedPayload.reference").value(ref));
    }
}
