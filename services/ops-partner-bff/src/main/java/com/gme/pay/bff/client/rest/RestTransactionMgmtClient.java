package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * <p><b>partnerId handling.</b> transaction-mgmt filters by the numeric partner id;
 * the BFF {@link Filter#partnerId()} is a free-form string. We forward it as the
 * {@code partnerId} query param only when it parses as a long, otherwise we omit
 * the filter (rather than passing a value the upstream would reject) — see
 * {@link #parseLongOrNull(String)}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.transaction-mgmt.client", havingValue = "rest")
public class RestTransactionMgmtClient implements TransactionMgmtClient {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionMgmtClient.class);

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 500;

    private final RestClient restClient;

    @Autowired
    public RestTransactionMgmtClient(
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestTransactionMgmtClient(RestClient restClient) {
        this.restClient = restClient;
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
            Long numericPartner = parseLongOrNull(filter.partnerId());
            if (numericPartner != null) {
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
            BigDecimal prefundingDeductedUsd
    ) {
        TransactionSummary toSummary() {
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
                    prefundingDeductedUsd);
        }
    }

    /** transaction-mgmt's {@code TransactionQueryPageResponse} wire envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WirePage(List<WireTxn> content, int page, int size, long totalElements) {}
}
