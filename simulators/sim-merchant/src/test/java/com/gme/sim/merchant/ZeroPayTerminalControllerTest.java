package com.gme.sim.merchant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gme.sim.merchant.model.ShopRecord;
import com.gme.sim.merchant.model.ShopStore;
import com.gme.sim.merchant.service.SchemeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for the spec-faithful ZeroPay terminal endpoints. SchemeClient is mocked so the
 * context loads without sim-scheme — these endpoints are fully local.
 *
 *  Z01 – static QR (QR구분 1, no amount)
 *  Z02 – dynamic charge QR (QR구분 2) + 420000 전문 wire
 *  Z03 – static-result registration (500000 전문)
 *  Z04 – unknown shop → 404
 *  Z05 – fractional KRW amount → 400
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ZeroPayTerminalControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ShopStore shopStore;
    @MockBean  SchemeClient schemeClient; // unused here; mocked so no sim-scheme needed

    private static final String MID = "ZP-TESTABCD";

    @BeforeEach
    void seed() {
        shopStore.save(new ShopRecord(
                MID, "Seoul Noodle House", "Seoul", "5812",
                null, null, null, "GENERAL", "0.0080"));
    }

    @Test
    void z01_staticQr_division1_noAmount() throws Exception {
        mvc.perform(get("/v1/merchant/zeropay/{mid}/static-qr", MID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MPM_STATIC"))
                .andExpect(jsonPath("$.qrDivision").value("1"))
                .andExpect(jsonPath("$.currencyNumeric").value("410"))
                .andExpect(jsonPath("$.merchantId").value(MID))
                .andExpect(jsonPath("$.amount").doesNotExist());
    }

    @Test
    void z02_dynamicCharge_division2_with420000Wire() throws Exception {
        mvc.perform(post("/v1/merchant/zeropay/{mid}/dynamic-qr", MID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"12500\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qr.qrDivision").value("2"))
                .andExpect(jsonPath("$.qr.amount").value(12500))
                .andExpect(jsonPath("$.qr.checkChar").isNotEmpty())
                .andExpect(jsonPath("$.wire.txnDivision").value("420000"))
                .andExpect(jsonPath("$.wire.messageType").value("0200"))
                .andExpect(jsonPath("$.wire.lengthBytes").value(1000))
                .andExpect(jsonPath("$.wire.charset").value("EUC-KR"))
                .andExpect(jsonPath("$.wire.fields[?(@.no == 34)].value").value(hasItem("2")))
                .andExpect(jsonPath("$.wire.fields[?(@.no == 31)].value").value(hasItem("12500")))
                // 해외페이: prepaid-combo code (field 47) must be "O"
                .andExpect(jsonPath("$.wire.fields[?(@.no == 47)].value").value(hasItem("O")));
    }

    @Test
    void z03_staticResult_500000Wire() throws Exception {
        mvc.perform(post("/v1/merchant/zeropay/{mid}/static-result", MID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"5000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnDivision").value("500000"))
                .andExpect(jsonPath("$.lengthBytes").value(1000))
                .andExpect(jsonPath("$.fields[?(@.no == 34)].value").value(hasItem("1")))
                .andExpect(jsonPath("$.fields[?(@.no == 51)].value").isArray());
    }

    @Test
    void z04_unknownShop_returns404() throws Exception {
        mvc.perform(get("/v1/merchant/zeropay/{mid}/static-qr", "NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_FOUND"));
    }

    @Test
    void z05_fractionalAmount_returns400() throws Exception {
        mvc.perform(post("/v1/merchant/zeropay/{mid}/dynamic-qr", MID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"100.50\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }
}
