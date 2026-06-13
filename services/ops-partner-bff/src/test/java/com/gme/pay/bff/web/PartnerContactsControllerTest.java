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
 * Slice 2 (2A.1) MockMvc test for the BFF's contact pass-throughs on
 * {@link AdminDashboardController} — {@code PATCH .../draft/{code}/step-2}
 * (bulk replace) and {@code GET .../{code}/contacts}. Uses the real
 * {@link StubConfigRegistryClient} (the same wiring the BFF runs when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the round-trip
 * create-draft → replace contacts → read contacts is exercised end-to-end,
 * mirroring {@link PartnerDraftControllerTest}.
 */
class PartnerContactsControllerTest {

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

    /** Create a draft so the step-2 PATCH has a target. */
    private void createDraft(String partnerCode) throws Exception {
        String body = """
                {"partnerCode":"%s","type":"OVERSEAS","settlementCurrency":"USD","settlementRoundingMode":"HALF_UP"}
                """.formatted(partnerCode);
        mvc.perform(post("/v1/admin/partners/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-2 bulk-replaces and returns the new set")
    void patchStep2_replacesContacts() throws Exception {
        createDraft("contact_partner_001");

        String firstSet = """
                {
                  "contacts": [
                    {"role":"OPS_24X7","name":"Ops Desk","email":"ops@partner.example",
                     "phoneE164":"+821012345678","authorizedSignatory":false,"notes":"24x7 hotline"},
                    {"role":"FINANCE","name":"Fin Lee","email":"finance@partner.example",
                     "phoneE164":null,"authorizedSignatory":true,"notes":null}
                  ]
                }
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstSet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].role").value("OPS_24X7"))
                .andExpect(jsonPath("$[0].phoneE164").value("+821012345678"))
                .andExpect(jsonPath("$[0].authorizedSignatory").value(false))
                .andExpect(jsonPath("$[1].role").value("FINANCE"))
                .andExpect(jsonPath("$[1].authorizedSignatory").value(true));

        // Second PATCH replaces (not appends): one contact remains.
        String secondSet = """
                {
                  "contacts": [
                    {"role":"COMPLIANCE_MLRO","name":"MLRO Park","email":"mlro@partner.example",
                     "phoneE164":"+8429876543","authorizedSignatory":true,"notes":null}
                  ]
                }
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondSet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("COMPLIANCE_MLRO"));

        mvc.perform(get("/v1/admin/partners/{code}/contacts", "contact_partner_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("COMPLIANCE_MLRO"))
                .andExpect(jsonPath("$[0].email").value("mlro@partner.example"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/contacts returns [] for a draft with no contacts yet")
    void getContacts_emptyForFreshDraft() throws Exception {
        createDraft("contact_partner_002");

        mvc.perform(get("/v1/admin/partners/{code}/contacts", "contact_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("PATCH step-2 against an unknown draft returns 404")
    void patchStep2_unknownDraftReturns404() throws Exception {
        String body = """
                {"contacts":[{"role":"TECH","name":"Kim","email":"kim@x.example",
                 "phoneE164":null,"authorizedSignatory":false,"notes":null}]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET contacts for an unknown partner returns 404")
    void getContacts_unknownPartnerReturns404() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/contacts", "ghost_partner"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH step-2 with a non-E.164 phone returns 400")
    void patchStep2_badPhoneReturns400() throws Exception {
        createDraft("contact_partner_003");

        String body = """
                {"contacts":[{"role":"TECH","name":"Kim","email":"kim@x.example",
                 "phoneE164":"010-1234-5678","authorizedSignatory":false,"notes":null}]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-2 with an invalid email returns 400")
    void patchStep2_badEmailReturns400() throws Exception {
        createDraft("contact_partner_004");

        String body = """
                {"contacts":[{"role":"TECH","name":"Kim","email":"not-an-email",
                 "phoneE164":null,"authorizedSignatory":false,"notes":null}]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-2 with a null contacts list returns 400")
    void patchStep2_nullContactsReturns400() throws Exception {
        createDraft("contact_partner_005");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-2 with an empty list clears the contact set")
    void patchStep2_emptyListClears() throws Exception {
        createDraft("contact_partner_006");

        String seed = """
                {"contacts":[{"role":"LEGAL","name":"Law Kwon","email":"legal@partner.example",
                 "phoneE164":null,"authorizedSignatory":false,"notes":null}]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-2", "contact_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contacts\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/v1/admin/partners/{code}/contacts", "contact_partner_006"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
