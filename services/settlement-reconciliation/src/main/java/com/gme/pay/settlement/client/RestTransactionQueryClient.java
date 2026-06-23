package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client implementation of {@link TransactionQueryPort}.
 *
 * <p>Calls transaction-mgmt's canonical <strong>GET /v1/transactions</strong> endpoint
 * (paged list) to fetch approved transactions.  Never reads the transaction-mgmt database
 * directly (MSA rule).
 *
 * <p>This bean is {@link Primary} so it wins when both this and a stub are on the
 * classpath. Tests use {@code @SpringBootTest} exclusion or a {@code @TestConfiguration}
 * to override with a stub.
 *
 * <p>The Spring 6 / Spring Boot 3.x rule: a {@link Component} with two or more
 * constructors MUST annotate the one to be used by the container with
 * {@link Autowired}.
 */
@Primary
@Component
public class RestTransactionQueryClient implements TransactionQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionQueryClient.class);

    /** Maximum page size accepted by transaction-mgmt (contract: max 500). */
    private static final int PAGE_SIZE = 500;

    private final RestTemplate restTemplate;
    private final String baseUrl;

    /**
     * Primary constructor — wired by Spring.
     * {@code @Autowired} is required here because {@link RestTransactionQueryClient}
     * has two constructors (this one + the package-private test helper below).
     */
    @Autowired
    public RestTransactionQueryClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.transaction-mgmt.base-url:http://transaction-mgmt:8082}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    /** Package-private constructor used by tests that supply a pre-built {@link RestTemplate}. */
    RestTransactionQueryClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    // ---------------------------------------------------------------------------------
    // TransactionQueryPort implementation
    // ---------------------------------------------------------------------------------

    /**
     * Fetch all APPROVED transactions for the given settlement date that are not yet
     * batched, by iterating pages from transaction-mgmt's
     * {@code GET /v1/transactions?from=...&to=...&status=APPROVED} endpoint.
     *
     * <p>Field mapping (canonical TransactionResponse → internal TransactionRecord):
     * <ul>
     *   <li>{@code txnRef}       → txnRef</li>
     *   <li>{@code partnerRef}   → schemeRef (closest available field)</li>
     *   <li>{@code merchantId}   → merchantId  <strong>(ReconDiffEngine key)</strong></li>
     *   <li>{@code targetPayout} → targetPayoutKrw (BigDecimal-as-string parsed to BigDecimal)</li>
     *   <li>{@code status}       → status</li>
     *   <li>{@code createdAt}    → completedAt (parsed to OffsetDateTime)</li>
     * </ul>
     */
    @Override
    public List<TransactionRecord> findUnbatchedApproved(LocalDate settlementDate) {
        log.debug("Fetching unbatched APPROVED transactions for date={}", settlementDate);
        return fetchAllPages(settlementDate, settlementDate, "APPROVED");
    }

    /**
     * Fetch transactions by batchId is not directly supported by the canonical
     * GET /v1/transactions endpoint (no batchId filter exists in the contract).
     * Returns an empty list; callers that need batch-level queries must use an
     * alternative path or a future endpoint extension.
     */
    @Override
    public List<TransactionRecord> findByBatchId(Long batchId) {
        log.warn("findByBatchId is not supported by the canonical GET /v1/transactions endpoint; " +
                "returning empty list for batchId={}", batchId);
        return List.of();
    }

    // ---------------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------------

    private List<TransactionRecord> fetchAllPages(LocalDate from, LocalDate to, String status) {
        List<TransactionRecord> result = new ArrayList<>();
        int page = 0;
        long totalElements;

        do {
            String url = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + "/v1/transactions")
                    .queryParam("from", from)
                    .queryParam("to", to)
                    .queryParam("status", status)
                    .queryParam("page", page)
                    .queryParam("size", PAGE_SIZE)
                    .toUriString();

            log.debug("GET {} (page={})", url, page);
            try {
                TransactionPageResponse pageResponse =
                        restTemplate.getForObject(url, TransactionPageResponse.class);

                if (pageResponse == null || pageResponse.content() == null) {
                    break;
                }

                for (TransactionResponse r : pageResponse.content()) {
                    result.add(toTransactionRecord(r));
                }

                totalElements = pageResponse.totalElements();
                page++;

                // Stop when we have fetched all elements
                if ((long) result.size() >= totalElements) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Failed to fetch transactions from transaction-mgmt (page={}): {}", page, e.getMessage());
                break;
            }
        } while (true);

        log.debug("Fetched {} transactions from transaction-mgmt", result.size());
        return result;
    }

    private static TransactionRecord toTransactionRecord(TransactionResponse r) {
        BigDecimal targetPayoutKrw = r.targetPayout() != null
                ? new BigDecimal(r.targetPayout())
                : BigDecimal.ZERO;

        // settlementType: derive from sendCcy; KRW domestic = NET ('N'), else GROSS ('G')
        char settlementType = "KRW".equalsIgnoreCase(r.sendCcy()) ? 'N' : 'G';

        // merchantFeeRate: the rate snapshotted on the txn at creation (V005); null on
        // legacy/pre-resolution rows → 0 (NET calc then yields a zero fee for that row).
        BigDecimal merchantFeeRate = r.merchantFeeRate() != null
                ? new BigDecimal(r.merchantFeeRate())
                : BigDecimal.ZERO;

        return new TransactionRecord(
                null,                           // id — not available in REST response
                r.txnRef(),                     // txnRef
                r.partnerRef(),                 // schemeRef (closest available)
                r.merchantId(),                 // merchantId  ← ReconDiffEngine key
                targetPayoutKrw,                // targetPayoutKrw  ← ReconDiffEngine amount
                settlementType,                 // settlementType
                merchantFeeRate,                // merchantFeeRate — V005 snapshot from transaction
                r.status(),                     // status
                null,                           // completedAt — createdAt is ISO instant string; parse if needed
                null                            // settlementBatchId — not available via REST
        );
    }

    // ---------------------------------------------------------------------------------
    // Inner DTOs — canonical field names MUST match transaction-mgmt's response exactly
    // (Jackson deserialises by name; any mismatch = silent null).
    // ---------------------------------------------------------------------------------

    /**
     * Page wrapper returned by {@code GET /v1/transactions}.
     * Field names match transaction-mgmt's canonical contract verbatim.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionPageResponse(
            List<TransactionResponse> content,
            int page,
            int size,
            long totalElements
    ) {}

    /**
     * Single transaction item inside the page.
     * Field names match transaction-mgmt's canonical {@code TransactionResponse} verbatim.
     * Uses {@code @JsonIgnoreProperties(ignoreUnknown = true)} so unknown future fields
     * do not break deserialisation.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionResponse(
            String txnRef,
            String partnerRef,
            String sendAmount,
            String sendCcy,
            String targetPayout,
            String targetCcy,
            String status,
            String createdAt,
            String updatedAt,
            String qrSchemeId,
            String krwAmount,
            String payerCurrency,
            String payerCurrencyAmount,
            String appliedFxRate,
            String rateTimestamp,
            String prefundingDeductedUsd,
            String merchantId,
            String merchantName,
            String merchantFeeRate
    ) {}
}
