package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.RevenueLedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Production {@link RevenueLedgerClient}. Talks to revenue-ledger over HTTP via
 * Spring 6 {@link RestClient}. Active when {@code gmepay.revenue-ledger.client=rest};
 * otherwise the in-memory {@link com.gme.pay.bff.client.stub.StubRevenueLedgerClient}
 * wins so the BFF still boots standalone for tests / local dev.
 *
 * <p>Endpoint mapping (revenue-ledger/RevenueController.java):
 * <pre>
 *   GET /v1/revenue?partnerId={long}&startDate={ISO}&endDate={ISO}
 *     -> RevenueSummaryResponse {
 *          partnerId, schemeId, startDate, endDate, txnCount,
 *          totalFxMarginUsd, totalServiceChargeAmount, serviceChargeCcy }
 * </pre>
 *
 * <p><b>Field mapping.</b> The upstream is a per-partner aggregate. We map
 * {@code totalServiceChargeAmount} -> {@code feeRevenueUsd},
 * {@code totalFxMarginUsd} -> {@code marginRevenueUsd}, and their sum ->
 * {@code totalRevenueUsd}. (See INTEGRATION REQUEST below on the service-charge
 * currency and {@code total_rounding_usd}.)
 *
 * <p><b>Why the system-wide methods degrade.</b> The BFF's
 * {@link #getSummary(LocalDate)} / {@link #summaryRange(LocalDate, LocalDate)} /
 * {@link #breakdown(LocalDate, LocalDate)} are SYSTEM-WIDE (no partner), but the
 * only upstream endpoint is per-(numeric)partnerId. To produce a system-wide
 * figure the client needs ONE configured partner to query — set
 * {@code gmepay.revenue-ledger.aggregate-partner-id} to a numeric partner id and
 * this client returns that partner's revenue for the requested range. When the
 * property is unset (the realistic default, since no system-wide endpoint exists
 * yet) the summary methods return a zero triple and {@code breakdown} returns
 * empty maps — an HONEST empty rather than synthetic data. The proper fix is an
 * upstream system-wide / multi-axis endpoint (see INTEGRATION REQUESTS #1–#3 in
 * the build report). Numeric partner ids are required by upstream; the BFF holds
 * partner CODES, so even per-partner aggregation is blocked on a code->id map
 * (INTEGRATION REQUEST #2).
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.revenue-ledger.client", havingValue = "rest")
public class RestRevenueLedgerClient implements RevenueLedgerClient {

    private static final Logger log = LoggerFactory.getLogger(RestRevenueLedgerClient.class);

    private final RestClient restClient;

    /** Optional numeric partner id used to back the system-wide summary methods. */
    private final Long aggregatePartnerId;

    @Autowired
    public RestRevenueLedgerClient(
            @Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}") String baseUrl,
            @Value("${gmepay.revenue-ledger.aggregate-partner-id:}") String aggregatePartnerId) {
        this(RestClient.builder().baseUrl(baseUrl).build(), parseLongOrNull(aggregatePartnerId));
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestRevenueLedgerClient(RestClient restClient, Long aggregatePartnerId) {
        this.restClient = restClient;
        this.aggregatePartnerId = aggregatePartnerId;
    }

    @Override
    public RevenueSummary getSummary(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now() : date;
        return summaryRange(d, d);
    }

    @Override
    public RevenueSummary summaryRange(LocalDate from, LocalDate to) {
        LocalDate fromD = from == null ? LocalDate.now() : from;
        LocalDate toD = to == null ? fromD : to;
        if (aggregatePartnerId == null) {
            // No partner to query and no system-wide endpoint: honest zero.
            return zeroSummary(toD);
        }
        WireRevenue r = fetch(aggregatePartnerId, fromD, toD);
        if (r == null) {
            return zeroSummary(toD);
        }
        BigDecimal fee = nz(r.totalServiceChargeAmount());
        BigDecimal margin = nz(r.totalFxMarginUsd());
        return new RevenueSummary(toD, fee.add(margin), fee, margin);
    }

    @Override
    public RevenueBreakdown breakdown(LocalDate from, LocalDate to) {
        // A per-partner endpoint cannot produce a by-partner/by-scheme/by-currency
        // breakdown across the system. Return empty maps until an upstream
        // multi-axis endpoint exists (INTEGRATION REQUEST #3).
        return new RevenueBreakdown(Map.of(), Map.of(), Map.of());
    }

    private WireRevenue fetch(long partnerId, LocalDate from, LocalDate to) {
        try {
            String uri = UriComponentsBuilder.fromPath("/v1/revenue")
                    .queryParam("partnerId", partnerId)
                    .queryParam("startDate", from)
                    .queryParam("endDate", to)
                    .build()
                    .toUriString();
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WireRevenue.class);
        } catch (RestClientResponseException e) {
            log.warn("revenue-ledger error on summaryRange (status={}): {}", e.getStatusCode(), e.getMessage());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("revenue-ledger unreachable on summaryRange: {}", e.getMessage());
            return null;
        }
    }

    private static RevenueSummary zeroSummary(LocalDate date) {
        return new RevenueSummary(date, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * revenue-ledger's {@code RevenueSummaryResponse} wire shape (snake_case is NOT
     * used — the upstream record serializes camelCase field names). Money rides as
     * decimal strings/numbers; Jackson reads into {@link BigDecimal}. Unknown fields
     * (e.g. a future {@code totalRoundingUsd}) are ignored so this client tolerates
     * the upstream adding the rounding field without a code change here.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireRevenue(
            long partnerId,
            long schemeId,
            LocalDate startDate,
            LocalDate endDate,
            long txnCount,
            BigDecimal totalFxMarginUsd,
            BigDecimal totalServiceChargeAmount,
            String serviceChargeCcy,
            // Surfaced once upstream adds it (INTEGRATION REQUEST #1); ignored until then.
            BigDecimal totalRoundingUsd
    ) {}
}
