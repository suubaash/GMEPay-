package com.gme.pay.txn.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T4-4 wire contract: {@code merchantName} on POST /v1/transactions survives to
 * GET /v1/transactions/{ref}.
 *
 * <p>This is deliberately an HTTP-level test rather than a service one. The field has to travel
 * payment-executor → transaction-mgmt by JSON NAME (Jackson binds by name, and both sides declare
 * their own record), so the failure mode worth defending against is a rename/typo that binds to null
 * and silently reproduces the em-dash gap — something no in-process test would catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@Transactional
class MerchantNameContractIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode createThenGet(String merchantNameJsonLine, String partnerTxnRef) throws Exception {
        String body = """
                {
                  "partnerId": 42,
                  "partnerTxnRef": "%s",
                  "schemeId": "sendmn",
                  "direction": "OVERSEAS",
                  "paymentMode": "MPM",
                  "targetPayout": "350000.00000000",
                  "payoutCurrency": "MNT",
                  "collectionAmount": "100000.00000000",
                  "collectionCurrency": "KRW",
                  "merchantId": "MERCH-778"%s
                }
                """.formatted(partnerTxnRef, merchantNameJsonLine);

        MvcResult created = mockMvc.perform(post("/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String txnRef = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("txnRef").asText();

        MvcResult fetched = mockMvc.perform(get("/v1/transactions/{ref}", txnRef))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(fetched.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("POST merchantName → GET returns the SAME name (no longer a hardcoded null)")
    void merchantNameSurvivesTheRoundTrip() throws Exception {
        JsonNode txn = createThenGet(",\n  \"merchantName\": \"Ulaanbaatar Central Store\"",
                "T44-REF-001");

        assertNotNull(txn.get("merchantName"),
                "merchantName must be present on the read — this field was previously always null");
        assertEquals("Ulaanbaatar Central Store", txn.get("merchantName").asText());
        // The id keeps its own identity: the two are separate facts about the payment.
        assertEquals("MERCH-778", txn.get("merchantId").asText());
    }

    @Test
    @DisplayName("omitted merchantName → the read carries NO name (NON_NULL omits it); id is not substituted")
    void omittedMerchantNameIsNotSubstituted() throws Exception {
        JsonNode txn = createThenGet("", "T44-REF-002");

        assertTrue(txn.get("merchantName") == null || txn.get("merchantName").isNull(),
                "a corridor that could not resolve a name must yield no name at all");
        assertEquals("MERCH-778", txn.get("merchantId").asText(),
                "and merchantId must NOT be quietly promoted into the name slot");
    }

    @Test
    @DisplayName("blank merchantName is normalised to absent, not stored as an empty name")
    void blankMerchantNameIsNormalisedToNull() throws Exception {
        JsonNode txn = createThenGet(",\n  \"merchantName\": \"   \"", "T44-REF-003");

        assertTrue(txn.get("merchantName") == null || txn.get("merchantName").isNull(),
                "an upstream that answered with whitespace means 'unknown', not 'a merchant with no name'");
    }
}
