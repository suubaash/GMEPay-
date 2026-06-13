package com.gme.pay.prefunding.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gme.pay.prefunding.persistence.BalanceAlertEntity;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice 5 (5B.1) MockMvc test for {@link BalanceProvisioningController} against the
 * Flyway-managed H2 database:
 *
 * <ol>
 *   <li>POST /v1/prefunding/provision creates the partner_balance row (201) and the
 *       persisted row matches the request;</li>
 *   <li>provisioning is idempotency-guarded — a second provision for the same code is 409
 *       and the original balance is untouched;</li>
 *   <li>GET /{partnerCode}/balance returns the canonical BalanceView with money as decimal
 *       STRINGS (docs/MONEY_CONVENTION.md) and pctOfThreshold at scale 2;</li>
 *   <li>GET /{partnerCode}/alerts returns the alert feed newest-first; 404 for unknown
 *       partners on both GETs; 400 on bad provision bodies.</li>
 * </ol>
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BalanceProvisioningApiTest {

    @Autowired private MockMvc mvc;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private LedgerEntryRepository ledger;

    @BeforeEach
    void cleanSlate() {
        alerts.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
    }

    private static final String PROVISION_BODY = """
            {
              "partnerCode": "PROV_P1",
              "openingBalanceUsd": "2500.0000",
              "lowBalanceThresholdUsd": "1000.0000"
            }
            """;

    @Test
    @DisplayName("POST /v1/prefunding/provision creates the balance row and returns 201")
    void provision_createsRow() throws Exception {
        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROVISION_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partnerCode").value("PROV_P1"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value("2500.0000"))
                .andExpect(jsonPath("$.threshold").value("1000.0000"))
                .andExpect(jsonPath("$.pctOfThreshold").value("250.00"));

        PartnerBalanceEntity row = balances.findById("PROV_P1").orElseThrow();
        assertEquals(0, row.getBalance().compareTo(new BigDecimal("2500.0000")));
        assertEquals(0, row.getLowBalanceThreshold().compareTo(new BigDecimal("1000.0000")));
        assertEquals("USD", row.getCurrency());
        // Persisted instants are truncated to MICROS.
        assertEquals(row.getUpdatedAt(), row.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("provisioning the same partner twice returns 409 and leaves the row untouched")
    void provision_isIdempotencyGuarded() throws Exception {
        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROVISION_BODY))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "partnerCode": "PROV_P1",
                                  "openingBalanceUsd": "999999.0000",
                                  "lowBalanceThresholdUsd": "1.0000"
                                }
                                """))
                .andExpect(status().isConflict());

        PartnerBalanceEntity row = balances.findById("PROV_P1").orElseThrow();
        assertEquals(0, row.getBalance().compareTo(new BigDecimal("2500.0000")),
                "second provision must not overwrite the original balance");
        assertEquals(1, balances.count());
    }

    @Test
    @DisplayName("GET /v1/prefunding/{code}/balance returns the canonical BalanceView")
    void getBalance_returnsView() throws Exception {
        balances.save(new PartnerBalanceEntity("PROV_P2", "USD",
                new BigDecimal("680.0000"), new BigDecimal("1000.0000"), Instant.now()));

        // partner_balance is NUMERIC(20,8): the DB round-trip yields scale-8 strings.
        mvc.perform(get("/v1/prefunding/{code}/balance", "PROV_P2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerCode").value("PROV_P2"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value("680.00000000"))
                .andExpect(jsonPath("$.threshold").value("1000.00000000"))
                .andExpect(jsonPath("$.pctOfThreshold").value("68.00"));
    }

    @Test
    @DisplayName("GET /v1/prefunding/{code}/alerts returns alerts newest-first")
    void getAlerts_newestFirst() throws Exception {
        balances.save(new PartnerBalanceEntity("PROV_P3", "USD",
                new BigDecimal("680.0000"), new BigDecimal("1000.0000"), Instant.now()));
        alerts.save(new BalanceAlertEntity("PROV_P3", "TIER_95",
                new BigDecimal("940.0000"), new BigDecimal("1000.0000"),
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        alerts.save(new BalanceAlertEntity("PROV_P3", "TIER_70",
                new BigDecimal("680.0000"), new BigDecimal("1000.0000"),
                Instant.now().truncatedTo(ChronoUnit.MICROS)));

        mvc.perform(get("/v1/prefunding/{code}/alerts", "PROV_P3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tier").value("TIER_70"))
                .andExpect(jsonPath("$[0].balanceUsd").value("680.0000"))
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[1].tier").value("TIER_95"));
    }

    @Test
    @DisplayName("unknown partner returns 404 on balance and alerts")
    void unknownPartner_404() throws Exception {
        mvc.perform(get("/v1/prefunding/{code}/balance", "GHOST"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/prefunding/{code}/alerts", "GHOST"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("invalid provision bodies return 400")
    void invalidProvision_400() throws Exception {
        // missing partnerCode
        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalanceUsd\":\"10\",\"lowBalanceThresholdUsd\":\"5\"}"))
                .andExpect(status().isBadRequest());
        // negative opening balance
        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerCode\":\"X\",\"openingBalanceUsd\":\"-1\","
                                + "\"lowBalanceThresholdUsd\":\"5\"}"))
                .andExpect(status().isBadRequest());
        // zero threshold
        mvc.perform(post("/v1/prefunding/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerCode\":\"X\",\"openingBalanceUsd\":\"10\","
                                + "\"lowBalanceThresholdUsd\":\"0\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(balances.findAll().isEmpty(), "no row written on validation failure");
    }
}
