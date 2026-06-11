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
import com.gme.pay.bff.client.stub.StubConfigRegistryClient;
import com.gme.pay.contracts.PartnerCommand;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice 4 (4A.1) MockMvc test for the BFF's bank-account pass-throughs on
 * {@link PartnerBankAccountsController} — {@code PATCH .../draft/{code}/step-4}
 * (bulk replace), {@code GET .../{code}/bank-accounts} and
 * {@code POST .../{code}/bank-accounts/{id}/verify}. Uses the real
 * {@link StubConfigRegistryClient} (the same wiring the BFF runs when
 * {@code gmepay.config-registry.client} is not {@code rest}) so the round-trip
 * create-draft → replace accounts → verify → read accounts is exercised
 * end-to-end, mirroring {@link PartnerContactsControllerTest} /
 * {@link PartnerKybControllerTest}.
 */
class PartnerBankAccountsControllerTest {

    private MockMvc mvc;
    private StubConfigRegistryClient configRegistry;

    @BeforeEach
    void setUp() {
        configRegistry = new StubConfigRegistryClient();

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = standaloneSetup(new PartnerBankAccountsController(configRegistry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** Seed a draft straight through the stub (the draft endpoints live on another controller). */
    private void createDraft(String partnerCode) {
        configRegistry.createDraft(new PartnerCommand.CreateDraft(
                partnerCode, PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP,
                null, "GME Partner Co Ltd", null, null, "KR", null, null, null, null));
    }

    /** One KR payout account (raw account number — NOT IBAN) + one GBP IBAN account. */
    private static final String STEP4_BODY = """
            {
              "bankAccounts": [
                {"currency":"KRW","bankName":"Shinhan Bank","bicSwift":"SHBKKRSE",
                 "ibanOrAccountNumber":"110-123-456789","accountHolderName":"GME Partner Co Ltd",
                 "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                 "primary":true,"swiftChargeBearer":null,"purpose":"PAYOUT"},
                {"currency":"GBP","bankName":"NatWest","bicSwift":"NWBKGB2L",
                 "ibanOrAccountNumber":"GB82WEST12345698765432","accountHolderName":"GME Partner Co Ltd",
                 "bankCountry":"GB","intermediaryBic":"CHASUS33XXX","verificationEvidenceDocId":null,
                 "primary":true,"swiftChargeBearer":"SHA","purpose":"PAYOUT"}
              ]
            }
            """;

    @Test
    @DisplayName("PATCH /v1/admin/partners/draft/{code}/step-4 bulk-replaces and returns the new set")
    void patchStep4_replacesBankAccounts() throws Exception {
        createDraft("bank_partner_001");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].currency").value("KRW"))
                .andExpect(jsonPath("$[0].ibanOrAccountNumber").value("110-123-456789"))
                .andExpect(jsonPath("$[0].verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[1].currency").value("GBP"))
                .andExpect(jsonPath("$[1].swiftChargeBearer").value("SHA"));

        // Second PATCH replaces (not appends): one account remains.
        String secondSet = """
                {"bankAccounts":[
                  {"currency":"USD","bankName":"Citibank Korea","bicSwift":"CITIKRSX",
                   "ibanOrAccountNumber":"987654321012","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":true,"swiftChargeBearer":"OUR","purpose":"FLOAT_TOPUP"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondSet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].purpose").value("FLOAT_TOPUP"));

        mvc.perform(get("/v1/admin/partners/{code}/bank-accounts", "bank_partner_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    @DisplayName("POST .../bank-accounts/{id}/verify stamps KFTC_VERIFIED for a KR account on a fresh row id")
    void verify_stampsKftcVerifiedForKoreanAccount() throws Exception {
        createDraft("bank_partner_002");
        MvcResult saved = mvc.perform(
                        patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andReturn();
        com.fasterxml.jackson.databind.JsonNode set = new ObjectMapper()
                .readTree(saved.getResponse().getContentAsString());
        long krId = set.get(0).get("id").asLong();
        long gbId = set.get(1).get("id").asLong();

        mvc.perform(post("/v1/admin/partners/{code}/bank-accounts/{id}/verify",
                        "bank_partner_002", krId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("KFTC_VERIFIED"))
                .andExpect(jsonPath("$.verificationDate").isNotEmpty())
                // SCD-6 upstream mints a fresh row for the verdict.
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not((int) krId)));

        // Overseas account → BANK_LETTER (the stub's deterministic dispatch).
        mvc.perform(post("/v1/admin/partners/{code}/bank-accounts/{id}/verify",
                        "bank_partner_002", gbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("BANK_LETTER"));

        // The read view reflects the stamped verdicts.
        mvc.perform(get("/v1/admin/partners/{code}/bank-accounts", "bank_partner_002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verificationStatus").value("KFTC_VERIFIED"))
                .andExpect(jsonPath("$[1].verificationStatus").value("BANK_LETTER"));
    }

    @Test
    @DisplayName("GET /v1/admin/partners/{code}/bank-accounts returns [] for a draft with no accounts yet")
    void getBankAccounts_emptyForFreshDraft() throws Exception {
        createDraft("bank_partner_003");

        mvc.perform(get("/v1/admin/partners/{code}/bank-accounts", "bank_partner_003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("PATCH step-4 against an unknown draft returns 404")
    void patchStep4_unknownDraftReturns404() throws Exception {
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "ghost_partner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET / verify for an unknown partner or account returns 404")
    void unknownPartnerOrAccountReturns404() throws Exception {
        mvc.perform(get("/v1/admin/partners/{code}/bank-accounts", "ghost_partner"))
                .andExpect(status().isNotFound());

        createDraft("bank_partner_004");
        mvc.perform(post("/v1/admin/partners/{code}/bank-accounts/{id}/verify",
                        "bank_partner_004", 424242L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH step-4 with a bad BIC returns 400 with the offending index")
    void patchStep4_badBicReturns400() throws Exception {
        createDraft("bank_partner_005");

        String body = """
                {"bankAccounts":[
                  {"currency":"KRW","bankName":"Shinhan Bank","bicSwift":"NOT-A-BIC",
                   "ibanOrAccountNumber":"110-123-456789","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":false,"swiftChargeBearer":null,"purpose":"PAYOUT"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-4 with an IBAN failing mod-97 returns 400")
    void patchStep4_badIbanChecksumReturns400() throws Exception {
        createDraft("bank_partner_006");

        String body = """
                {"bankAccounts":[
                  {"currency":"GBP","bankName":"NatWest","bicSwift":"NWBKGB2L",
                   "ibanOrAccountNumber":"GB82WEST12345698765431","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"GB","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":false,"swiftChargeBearer":"SHA","purpose":"PAYOUT"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-4 with two primary accounts in one currency returns 400")
    void patchStep4_twoPrimariesSameCurrencyReturns400() throws Exception {
        createDraft("bank_partner_007");

        String body = """
                {"bankAccounts":[
                  {"currency":"KRW","bankName":"Shinhan Bank","bicSwift":null,
                   "ibanOrAccountNumber":"110-123-456789","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":true,"swiftChargeBearer":null,"purpose":"PAYOUT"},
                  {"currency":"KRW","bankName":"Kookmin Bank","bicSwift":null,
                   "ibanOrAccountNumber":"456-789-012345","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":true,"swiftChargeBearer":null,"purpose":"REFUND"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_007")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH step-4 with a null bankAccounts list returns 400; empty list clears")
    void patchStep4_nullListReturns400_emptyListClears() throws Exception {
        createDraft("bank_partner_008");

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_008")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankAccounts\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/v1/admin/partners/{code}/bank-accounts", "bank_partner_008"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Verification carries forward across a replace when the account number is unchanged")
    void patchStep4_carriesVerificationForward() throws Exception {
        createDraft("bank_partner_009");
        MvcResult saved = mvc.perform(
                        patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_009")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(STEP4_BODY))
                .andExpect(status().isOk())
                .andReturn();
        long krId = new ObjectMapper().readTree(saved.getResponse().getContentAsString())
                .get(0).get("id").asLong();
        mvc.perform(post("/v1/admin/partners/{code}/bank-accounts/{id}/verify",
                        "bank_partner_009", krId))
                .andExpect(status().isOk());

        // Same (currency, account number), edited bank name → verdict survives.
        String renamed = """
                {"bankAccounts":[
                  {"currency":"KRW","bankName":"Shinhan Bank (renamed)","bicSwift":"SHBKKRSE",
                   "ibanOrAccountNumber":"110-123-456789","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":true,"swiftChargeBearer":null,"purpose":"PAYOUT"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_009")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verificationStatus").value("KFTC_VERIFIED"));

        // Different account number → verification resets.
        String swapped = """
                {"bankAccounts":[
                  {"currency":"KRW","bankName":"Shinhan Bank","bicSwift":"SHBKKRSE",
                   "ibanOrAccountNumber":"999-999-999999","accountHolderName":"GME Partner Co Ltd",
                   "bankCountry":"KR","intermediaryBic":null,"verificationEvidenceDocId":null,
                   "primary":true,"swiftChargeBearer":null,"purpose":"PAYOUT"}
                ]}
                """;
        mvc.perform(patch("/v1/admin/partners/draft/{code}/step-4", "bank_partner_009")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verificationStatus").value("UNVERIFIED"));
    }
}
