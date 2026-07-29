package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.bff.client.TransactionMgmtClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Production {@link TransactionMgmtClient}. Talks to transaction-mgmt over HTTP
 * via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.transaction-mgmt.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubTransactionMgmtClient} wins so the BFF
 * still boots standalone for tests / local dev.
 *
 * <p>Endpoint mapping (transaction-mgmt/TransactionController.java):
 * <ul>
 *   <li>{@code GET /v1/transactions/{txnRef}} -> {@link #getTransaction(String)}</li>
 *   <li>{@code GET /v1/transactions?page&size&from&to&status&partnerId}
 *       -> {@link #recent(String, int)} and {@link #list(Filter)}</li>
 * </ul>
 *
 * <p>The wire shape is transaction-mgmt's {@code TransactionResponse} (camelCase,
 * money as decimal strings, {@code status} as the enum name, instants as ISO-8601).
 * We deserialize the subset we surface to the Admin UI / Partner Portal and map it
 * onto the BFF's {@link TransactionSummary} (whose {@code committedAt} we populate
 * from {@code createdAt}, and whose {@code amount}/{@code currency} are the payout
 * leg {@code targetPayout}/{@code targetCcy}). Unknown wire fields are ignored.
 *
 * <p><b>partnerId handling (partner-scoping, security-critical).</b> transaction-mgmt filters by the
 * NUMERIC partner id, while the BFF {@link Filter#partnerId()} is a free-form string that may carry
 * either that surrogate (the Admin surface) or the business CODE — the Partner Portal's path segment
 * and the token's {@code partner_id} claim are seeded with codes like {@code "GMEREMIT"}. A supplied
 * partnerId is therefore resolved in two steps:
 *
 * <ol>
 *   <li>already numeric → forwarded as-is;</li>
 *   <li>otherwise looked up through {@link PartnerDirectory}, which reads config-registry's
 *       {@code GET /v1/partners/{code}} for the surrogate id.</li>
 * </ol>
 *
 * <p>If neither resolves we <b>fail closed</b> — an empty page, never an unfiltered query that would
 * leak every partner's transactions. Only a truly absent (null/blank) partnerId means "all partners".
 *
 * <p><b>Gap register T1-3:</b> step 2 is new. Previously a non-numeric partnerId failed closed
 * outright, so the (correct) security rule silently made the Portal's Transactions page and CSV
 * statement come back EMPTY for every real partner, while {@code partner_test_00*} appeared to work
 * against the stub. The fail-closed guarantee is unchanged — an unresolvable code still yields no
 * rows; it simply now resolves the codes that config-registry knows about.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.transaction-mgmt.client", havingValue = "rest")
public class RestTransactionMgmtClient implements TransactionMgmtClient {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionMgmtClient.class);

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 500;

    private final RestClient restClient;

    /**
     * Bridges a partner business CODE to config-registry's numeric surrogate. Nullable: the
     * package-private test constructor leaves it unset, in which case only already-numeric partner
     * ids resolve and everything else fails closed exactly as before.
     */
    private final PartnerDirectory partners;

    @Autowired
    public RestTransactionMgmtClient(
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl,
            PartnerDirectory partners) {
        this(RestClient.builder().baseUrl(baseUrl).build(), partners);
    }

    /**
     * Package-private constructor for tests to inject a pre-built RestClient. Resolves no partner
     * codes (see {@link #partners}); use {@link #RestTransactionMgmtClient(RestClient,
     * PartnerDirectory)} to exercise code resolution.
     */
    RestTransactionMgmtClient(RestClient restClient) {
        this(restClient, null);
    }

    /** Package-private constructor for tests that also need code -> surrogate resolution. */
    RestTransactionMgmtClient(RestClient restClient, PartnerDirectory partners) {
        this.restClient = restClient;
        this.partners = partners;
    }

    /**
     * Resolves a caller-supplied partner id to transaction-mgmt's numeric filter value, or
     * {@code null} when it cannot be resolved (caller must then fail closed).
     */
    private Long resolvePartner(String partnerId) {
        Long numeric = parseLongOrNull(partnerId);
        if (numeric != null) {
            return numeric;
        }
        if (partners == null) {
            return null;
        }
        return partners.numericIdOf(partnerId).orElse(null);
    }

    @Override
    public TransactionSummary getTransaction(String txnId) {
        try {
            WireTxn t = restClient.get()
                    .uri("/v1/transactions/{ref}", txnId)
                    .retrieve()
                    .body(WireTxn.class);
            return t == null ? null : t.toSummary();
        } catch (HttpClientErrorException e) {
            // 404 (unknown) or any 4xx -> the BFF contract is "null when unknown".
            return null;
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on getTransaction({}): {}", txnId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<TransactionSummary> recent(String partnerId, int limit) {
        int size = limit <= 0 ? DEFAULT_SIZE : Math.min(limit, MAX_SIZE);
        Filter filter = new Filter(partnerId, null, null, null, null, 0, size);
        return list(filter).content();
    }

    @Override
    public Page<TransactionSummary> list(Filter filter) {
        int page = Math.max(0, filter.page());
        int size = filter.size() <= 0 ? DEFAULT_SIZE : Math.min(filter.size(), MAX_SIZE);
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/v1/transactions")
                    .queryParam("page", page)
                    .queryParam("size", size);
            if (filter.fromDate() != null) {
                uri.queryParam("from", filter.fromDate());
            }
            if (filter.toDate() != null) {
                uri.queryParam("to", filter.toDate());
            }
            if (filter.state() != null && !filter.state().isBlank()) {
                uri.queryParam("status", filter.state());
            }
            // Scheme-statement: forward the QR-scheme corridor filter (scheme_id) so a scheme's
            // reconciliation statement scopes to just its own transactions. transaction-mgmt maps
            // this to the scheme_id column (additive filter added there).
            if (filter.schemeId() != null && !filter.schemeId().isBlank()) {
                uri.queryParam("schemeId", filter.schemeId());
            }
            // Partner-scoping (security-critical): a supplied partnerId that resolves to no
            // surrogate must NOT degrade to an unfiltered (all-partners) query — fail closed.
            if (hasText(filter.partnerId())) {
                Long numericPartner = resolvePartner(filter.partnerId());
                if (numericPartner == null) {
                    log.warn("list: partnerId '{}' resolves to no config-registry surrogate — "
                            + "failing closed (empty page) rather than querying transaction-mgmt "
                            + "unscoped", filter.partnerId());
                    return new Page<>(List.of(), page, size, 0L);
                }
                uri.queryParam("partnerId", numericPartner);
            }

            WirePage resp = restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(WirePage.class);

            if (resp == null || resp.content() == null) {
                return new Page<>(List.of(), page, size, 0L);
            }
            List<TransactionSummary> items = resp.content().stream()
                    .map(WireTxn::toSummary)
                    .toList();
            return new Page<>(items, resp.page(), resp.size(), resp.totalElements());
        } catch (RestClientResponseException e) {
            log.warn("transaction-mgmt error on list (status={}): {}", e.getStatusCode(), e.getMessage());
            return new Page<>(List.of(), page, size, 0L);
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on list: {}", e.getMessage());
            return new Page<>(List.of(), page, size, 0L);
        }
    }

    @Override
    public Page<TransactionSummary> search(SearchQuery query) {
        int page = Math.max(0, query.page());
        int size = query.size() <= 0 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/v1/transactions/search")
                    .queryParam("page", page)
                    .queryParam("size", size);
            if (query.q() != null && !query.q().isBlank()) {
                uri.queryParam("q", query.q());
            }
            if (query.status() != null && !query.status().isBlank()) {
                uri.queryParam("status", query.status());
            }
            // CS support-read: forward the customer's wallet id and the partner's own reference
            // so a support agent can search by either. transaction-mgmt matches them as filters.
            if (query.userRef() != null && !query.userRef().isBlank()) {
                uri.queryParam("userRef", query.userRef());
            }
            if (query.reference() != null && !query.reference().isBlank()) {
                uri.queryParam("reference", query.reference());
            }
            // Partner-scoping (security-critical): same fail-closed rule as list() — a supplied
            // partnerId that resolves to nothing never widens the search to all partners.
            if (hasText(query.partnerId())) {
                Long numericPartner = resolvePartner(query.partnerId());
                if (numericPartner == null) {
                    log.warn("search: partnerId '{}' resolves to no config-registry surrogate — "
                            + "failing closed (empty page)", query.partnerId());
                    return new Page<>(List.of(), page, size, 0L);
                }
                uri.queryParam("partnerId", numericPartner);
            }
            WirePage resp = restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(WirePage.class);
            if (resp == null || resp.content() == null) {
                return new Page<>(List.of(), page, size, 0L);
            }
            List<TransactionSummary> items = resp.content().stream()
                    .map(WireTxn::toSummary)
                    .toList();
            return new Page<>(items, resp.page(), resp.size(), resp.totalElements());
        } catch (RestClientResponseException e) {
            log.warn("transaction-mgmt error on search (status={}): {}", e.getStatusCode(), e.getMessage());
            return new Page<>(List.of(), page, size, 0L);
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on search: {}", e.getMessage());
            return new Page<>(List.of(), page, size, 0L);
        }
    }

    @Override
    public DeliveryStats stats(Instant from, Instant to) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/v1/transactions/stats");
            if (from != null) {
                uri.queryParam("from", from.toString());
            }
            if (to != null) {
                uri.queryParam("to", to.toString());
            }
            DeliveryStats resp = restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(DeliveryStats.class);
            return resp == null ? DeliveryStats.empty() : resp;
        } catch (RestClientResponseException e) {
            log.warn("transaction-mgmt error on stats (status={}): {}", e.getStatusCode(), e.getMessage());
            return DeliveryStats.empty();
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on stats: {}", e.getMessage());
            return DeliveryStats.empty();
        }
    }

    @Override
    public PayerStats payerStats(Instant from, Instant to) {
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/v1/transactions/payer-stats");
            if (from != null) {
                uri.queryParam("from", from.toString());
            }
            if (to != null) {
                uri.queryParam("to", to.toString());
            }
            return restClient.get()
                    .uri(uri.build().toUriString())
                    .retrieve()
                    .body(PayerStats.class);
        } catch (RestClientResponseException e) {
            log.warn("transaction-mgmt error on payer-stats (status={}): {}",
                    e.getStatusCode(), e.getMessage());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on payer-stats: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public java.util.Map<String, Instant> firstApprovedByPartner() {
        try {
            java.util.Map<String, Instant> resp = restClient.get()
                    .uri("/v1/transactions/first-approved")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<
                            java.util.Map<String, Instant>>() {});
            return resp == null ? java.util.Map.of() : resp;
        } catch (RestClientResponseException e) {
            log.warn("transaction-mgmt error on first-approved (status={}): {}",
                    e.getStatusCode(), e.getMessage());
            return java.util.Map.of();
        } catch (ResourceAccessException e) {
            log.warn("transaction-mgmt unreachable on first-approved: {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    @Override
    public TransactionSummary resolve(String txnRef, String resolution, String actor, String reason) {
        try {
            WireTxn t = restClient.post()
                    .uri("/v1/transactions/{ref}/resolve", txnRef)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of(
                            "resolution", resolution == null ? "" : resolution,
                            "actor", actor == null ? "" : actor,
                            "reason", reason == null ? "" : reason))
                    .retrieve()
                    .body(WireTxn.class);
            return t == null ? null : t.toSummary();
        } catch (RestClientResponseException e) {
            // Unknown ref (404) / illegal state (409) -> propagate upstream status + message.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatusCode.valueOf(e.getStatusCode().value()), e.getMessage());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
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
     * Subset of transaction-mgmt's {@code TransactionResponse} we map to the BFF
     * view. Money rides as decimal strings on the wire; Jackson reads them into
     * {@link BigDecimal}. {@code status} is the enum name. Unknown fields ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireTxn(
            String txnRef,
            String partnerRef,
            String status,
            BigDecimal targetPayout,
            String targetCcy,
            Instant createdAt,
            String qrSchemeId,
            BigDecimal krwAmount,
            String payerCurrency,
            BigDecimal payerCurrencyAmount,
            BigDecimal appliedFxRate,
            Instant rateTimestamp,
            BigDecimal prefundingDeductedUsd,
            // scheme-confirmation + merchant fields from transaction-mgmt's TransactionResponse
            String schemeTxnRef,
            String schemeApprovalCode,
            String merchantId,
            Instant approvedAt,
            // CS support-read fields from transaction-mgmt's TransactionResponse
            String failureReason,
            String statusLabel,
            String declineReasonText,
            List<WireStatusEntry> statusHistory
    ) {
        TransactionSummary toSummary() {
            List<StatusEntry> history = statusHistory == null ? null
                    : statusHistory.stream().map(WireStatusEntry::toEntry).toList();
            return new TransactionSummary(
                    txnRef,
                    partnerRef,
                    status,
                    targetPayout,
                    targetCcy,
                    createdAt,
                    qrSchemeId,
                    krwAmount,
                    payerCurrency,
                    payerCurrencyAmount,
                    appliedFxRate,
                    rateTimestamp,
                    prefundingDeductedUsd,
                    schemeTxnRef,
                    schemeApprovalCode,
                    merchantId,
                    approvedAt,
                    failureReason,
                    statusLabel,
                    declineReasonText,
                    history);
        }
    }

    /** transaction-mgmt's status-history entry wire shape ({@code {status,statusLabel,at,note}}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireStatusEntry(String status, String statusLabel, Instant at, String note) {
        StatusEntry toEntry() {
            return new StatusEntry(status, statusLabel, at, note);
        }
    }

    /** transaction-mgmt's {@code TransactionQueryPageResponse} wire envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WirePage(List<WireTxn> content, int page, int size, long totalElements) {}
}
