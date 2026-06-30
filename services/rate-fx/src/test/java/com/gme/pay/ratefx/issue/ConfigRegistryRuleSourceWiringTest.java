package com.gme.pay.ratefx.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.ratefx.RateInput;
import com.gme.pay.ratefx.client.PartnerConfigPort.PartnerRule;
import com.gme.pay.ratefx.client.RestConfigRegistryClient;
import com.gme.pay.ratefx.partnerb.PartnerBQuote;
import com.gme.pay.ratefx.partnerb.PartnerBQuotePort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wave-3 wiring proof for IR-1: rate-fx now CONSUMES config-registry's per-leg rate-source from the
 * {@code GET /v1/partners/{code}/rules} response. The whole chain is exercised over a
 * {@link MockRestServiceServer} (config-registry NOT running): the real {@link RestConfigRegistryClient}
 * deserializes the wire {@code rateCollSource}/{@code ratePaySource} strings onto
 * {@link PartnerRule}, {@link RateSource#fromNullable} resolves them, and {@link QuoteIssueService}
 * dispatches a {@code PARTNER} leg to the {@link PartnerBQuotePort} while
 * {@code LIVE}/{@code MANUAL}/{@code IDENTITY}/absent take the treasury-snapshot / identity paths.
 *
 * <p>Before this wiring every leg defaulted {@code LIVE} because the source field on the rule was
 * never read; these tests are the regression guard that it is now data-driven, not hardcoded.
 */
@ExtendWith(MockitoExtension.class)
class ConfigRegistryRuleSourceWiringTest {

    private static final String BASE = "http://config-registry:8080";

    @Mock
    private CostRateResolver costRateResolver;
    @Mock
    private PartnerBQuotePort partnerBQuotePort;

    /** A real RestConfigRegistryClient backed by a MockRestServiceServer for the two GETs. */
    private RestConfigRegistryClient client(String partnerJson, String rulesJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/v1/partners/GMEREMIT"))
                .andRespond(withSuccess(partnerJson, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/partners/GMEREMIT/rules"))
                .andRespond(withSuccess(rulesJson, MediaType.APPLICATION_JSON));
        return new RestConfigRegistryClient(builder.build());
    }

    /** A client wired to answer only the /rules GET (for the client-deserialization assertions). */
    private RestConfigRegistryClient rulesOnlyClient(String rulesJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/v1/partners/GMEREMIT/rules"))
                .andRespond(withSuccess(rulesJson, MediaType.APPLICATION_JSON));
        return new RestConfigRegistryClient(builder.build());
    }

    private static final String PARTNER_USD = """
            { "settlementCurrency": "USD", "collectionCcy": "USD", "settleACcy": "USD" }
            """;
    private static final String PARTNER_KRW_COLL = """
            { "settlementCurrency": "USD", "collectionCcy": "KRW", "settleACcy": "KRW" }
            """;

    /** A single zeropay/INBOUND rule with the given per-leg source strings (null => field omitted). */
    private static String rule(String collSource, String paySource) {
        return """
                [ { "schemeId": "zeropay", "direction": "INBOUND",
                    "mA": "0.01", "mB": "0.01", "serviceChargeUsd": "0",
                    "rateCollSource": %s, "ratePaySource": %s } ]
                """.formatted(json(collSource), json(paySource));
    }

    private static final String RULE_NO_SOURCE = """
            [ { "schemeId": "zeropay", "direction": "INBOUND",
                "mA": "0.01", "mB": "0.01", "serviceChargeUsd": "0" } ]
            """;

    private static String json(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private QuoteIssueService service(RestConfigRegistryClient c) {
        return new QuoteIssueService(c, costRateResolver, partnerBQuotePort, null);
    }

    private static PartnerQuoteRequest payoutKrw() {
        return new PartnerQuoteRequest("GMEREMIT", "zeropay", "INBOUND",
                new BigDecimal("50000"), "KRW");
    }

    // ---- client deserializes the new source fields off the wire ----

    @Test
    void getRules_deserializesSourceStrings_andTheyResolveOntoRateSource() {
        RestConfigRegistryClient c = rulesOnlyClient(rule("IDENTITY", "PARTNER"));

        List<PartnerRule> rules = c.getRules("GMEREMIT");

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).rateCollSource()).isEqualTo("IDENTITY");
        assertThat(rules.get(0).ratePaySource()).isEqualTo("PARTNER");
        assertThat(RateSource.fromNullable(rules.get(0).rateCollSource())).isEqualTo(RateSource.IDENTITY);
        assertThat(RateSource.fromNullable(rules.get(0).ratePaySource())).isEqualTo(RateSource.PARTNER);
    }

    @Test
    void getRules_absentSourceFields_areNull_andResolveToLive() {
        RestConfigRegistryClient c = rulesOnlyClient(RULE_NO_SOURCE);

        PartnerRule r = c.getRules("GMEREMIT").get(0);

        assertThat(r.rateCollSource()).isNull();
        assertThat(r.ratePaySource()).isNull();
        assertThat(RateSource.fromNullable(r.rateCollSource())).isEqualTo(RateSource.LIVE);
        assertThat(RateSource.fromNullable(r.ratePaySource())).isEqualTo(RateSource.LIVE);
    }

    // ---- end-to-end: wire source field selects the leg's pricing path ----

    @Test
    void collectionLegMarkedPartner_routesThroughPartnerB_notTreasury() {
        // KRW collection + USD payout so the PARTNER (collection) leg is the non-USD leg.
        RestConfigRegistryClient c = client(PARTNER_KRW_COLL, rule("PARTNER", "IDENTITY"));
        when(partnerBQuotePort.fetchQuote("zeropay", "KRW"))
                .thenReturn(new PartnerBQuote(new BigDecimal("1395.00"), "Q-COLL", null));

        RateInput in = service(c).buildRateInput(new PartnerQuoteRequest(
                "GMEREMIT", "zeropay", "INBOUND", new BigDecimal("100"), "USD"));

        assertThat(in.costRateColl()).isEqualByComparingTo("1395.00");
        verify(costRateResolver, never()).resolve("KRW");
    }

    @Test
    void payLegMarkedLive_readsTreasurySnapshot_notPartnerB() {
        RestConfigRegistryClient c = client(PARTNER_USD, rule("IDENTITY", "LIVE"));
        when(costRateResolver.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        RateInput in = service(c).buildRateInput(payoutKrw());

        assertThat(in.costRatePay()).isEqualByComparingTo("1380");
        verify(partnerBQuotePort, never()).fetchQuote("zeropay", "KRW");
    }

    @Test
    void payLegMarkedManual_readsSnapshotStore_notPartnerB() {
        RestConfigRegistryClient c = client(PARTNER_USD, rule("IDENTITY", "MANUAL"));
        when(costRateResolver.resolve("KRW")).thenReturn(new BigDecimal("1375"));

        RateInput in = service(c).buildRateInput(payoutKrw());

        assertThat(in.costRatePay()).isEqualByComparingTo("1375");
        verify(partnerBQuotePort, never()).fetchQuote("zeropay", "KRW");
    }

    @Test
    void identityUsdLeg_yieldsNullRate_engineForcesOne() {
        // settle-A = USD => collection leg is IDENTITY regardless; never quotes anything for it.
        RestConfigRegistryClient c = client(PARTNER_USD, rule("IDENTITY", "LIVE"));
        when(costRateResolver.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        RateInput in = service(c).buildRateInput(payoutKrw());

        assertThat(in.costRateColl()).isNull();
        verify(partnerBQuotePort, never()).fetchQuote("zeropay", "USD");
    }

    @Test
    void absentSourceFields_payLegDefaultsToLiveSnapshot_endToEnd() {
        RestConfigRegistryClient c = client(PARTNER_USD, RULE_NO_SOURCE);
        when(costRateResolver.resolve("KRW")).thenReturn(new BigDecimal("1380"));

        RateInput in = service(c).buildRateInput(payoutKrw());

        assertThat(in.costRatePay()).isEqualByComparingTo("1380");
        verify(partnerBQuotePort, never()).fetchQuote("zeropay", "KRW");
    }
}
