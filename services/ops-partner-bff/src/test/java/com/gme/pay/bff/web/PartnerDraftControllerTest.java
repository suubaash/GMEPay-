package com.gme.pay.bff.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.bff.client.stub.StubPrefundingClient;
import com.gme.pay.bff.client.stub.StubRevenueLedgerClient;
import com.gme.pay.bff.client.stub.StubSettlementClient;
import com.gme.pay.bff.client.stub.StubTransactionMgmtClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 1 (1C.2) MockMvc test for the BFF's draft endpoints on
 * {@link AdminDashboardController}. Uses the real {@link StubConfigRegistryClient}
 * so the round-trip (create → patch → get → list) is exercised end-to-end
 * against the stub's in-memory draft store — the same code path the BFF runs
 * when {@code gmepay.config-registry.client} is not set to {@code rest}.
 */
class PartnerDraftControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ConfigRegistryClient configRegistry = new StubConfigRegistryClient();
        TransactionMgmtClient transactions = new StubTransactionMgmtClient();
        PrefundingClient prefunding = new StubPrefundingClient();
        RevenueLedgerClient revenue = new StubRevenueLedgerClient();
        SettlementClient settlement = new StubSettlementClient();

        AdminDashboardController controller = new AdminDashboardController(
                configRegistry, transactions, prefunding, revenue, settlement);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(om);

        mvc = standaloneSetup(controller).setMessageConverters(converter).build();
    }

    @Test
    @DisplayName("POST /v1/admin/partners/draft creates a draft and returns 201")
    void createDraft_returns201WithCanonicalView() throws Exception {
        String body = """
                {
                  "partnerCode": "draft_partner_001",
                  "type": "OVERSEAS",
                  "settlementCurrency": "EUR",
                  "settlementRoundingMode": "DOWN"
                }
                """;
        mvc.perform(post("/v1/admin/partners/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partnerCode").value("draft_partner_001"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.type").value("OVERSEAS"))
                .andExpect(jsonPath("$.settlementCurrency").value("EUR"))
                .andExpect(jsonPath("$.settlementRoundingMode").value("DOWN"))
                .andExpect(jsonPath("$.status").value("ONBOARDING"));
    }

    @Test
    @DisplayName("POST /v1/admin/partners/draft + PATCH .../step-1 writes identity fields")
    void patchStep1_updatesIdentityColumns() throws Exception {
        // create the draft first so the PATCH has a target
        String createBody = """
                {
                  "partnerCode": "draft_partner_002",
                  "type": "LOCAL",
                  "settlementCurrency": "KRW",
                  "settlementRoundingMode": "HALF_UP"
                }
                """;
        mvc.perform(post("/v1/admin/partners/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        // PATCH step-1 with full Identity payload
        String patchBody = """
                {
                  "type": "LOCAL",
                  "settlementCurrency": "KRW",
                  "settlementRoundingMode": "HALF_UP",
                  "legalNameLocal": "㈜식회사 지에이",
                  "legalNameRomanized": "GME Co., Ltd.",
                  "taxId": "1234567890",
                  "taxIdType": "KR_BRN",
                  "countryOfIncorporation": "KR",
                  "legalForm": "CORP",
                  "registeredAddress": {
                    "street1": "1 Test Avenue",
                    "street2": null,
                    "city": "Seoul",
                    "state": "Seoul",
                    "postcode": "04524",
                    "country": "KR"
                  },
                  "operatingAddress": {
                    "street1": "1 Test Avenue",
                    "street2": null,
                    "city": "Seoul",
                    "state": "Seoul",
                    "postcode": "04524",
                    "country": "KR"
                  },
                  "lei": "549300X3KH0PUE6N4K48"
                }
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{partnerCode}/step-1", "draft_partner_002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("draft_partner_002"))
                .andExpect(jsonPath("$.legalNameRomanized").value("GME Co., Ltd."))
                .andExpect(jsonPath("$.taxId").value("1234567890"))
                .andExpect(jsonPath("$.taxIdType").value("KR_BRN"))
                .andExpect(jsonPath("$.countryOfIncorporation").value("KR"))
                .andExpect(jsonPath("$.legalForm").value("CORP"))
                .andExpect(jsonPath("$.lei").value("549300X3KH0PUE6N4K48"))
                .andExpect(jsonPath("$.registeredAddress.city").value("Seoul"))
                .andExpect(jsonPath("$.operatingAddress.country").value("KR"))
                .andExpect(jsonPath("$.status").value("ONBOARDING"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/draft/{partnerCode} returns the draft")
    void getDraft_returns200() throws Exception {
        String body = """
                {"partnerCode":"draft_partner_003","type":"OVERSEAS","settlementCurrency":"USD","settlementRoundingMode":"HALF_UP"}
                """;
        mvc.perform(post("/v1/admin/partners/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/admin/partners/draft/{partnerCode}", "draft_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("draft_partner_003"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/draft/{partnerCode} returns 404 when unknown")
    void getDraft_unknownReturns404() throws Exception {
        mvc.perform(get("/v1/admin/partners/draft/{partnerCode}", "ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /v1/admin/partners/drafts lists drafts created via POST")
    void listDrafts_returnsCreatedDrafts() throws Exception {
        String body = """
                {"partnerCode":"draft_partner_004","type":"OVERSEAS","settlementCurrency":"USD","settlementRoundingMode":"HALF_UP"}
                """;
        mvc.perform(post("/v1/admin/partners/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/admin/partners/drafts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[?(@.partnerCode == 'draft_partner_004')].status")
                        .value(org.hamcrest.Matchers.hasItem("ONBOARDING")));
    }
}
