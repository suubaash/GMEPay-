package com.gme.pay.reporting.infrastructure;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.TransactionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Production implementation of {@link TransactionClient} that calls the
 * {@code transaction-mgmt} service via Spring 6 {@link RestClient}.
 *
 * <p>Calls {@code GET /v1/transactions?from={from}&to={to}[&partnerId={id}]}
 * and pages through all results (page size 500) until all elements are fetched.
 *
 * <p>Wire contract alignment — field names consumed here must match
 * {@code transaction-mgmt}'s {@code TransactionQueryPageResponse} exactly:
 * <ul>
 *   <li>Page wrapper: {@code content}, {@code page}, {@code size}, {@code totalElements}</li>
 *   <li>Item: camelCase — {@code txnRef}, {@code sendAmount}, {@code sendCcy},
 *       {@code targetPayout}, {@code targetCcy}, {@code status}, {@code createdAt},
 *       {@code appliedFxRate}, {@code prefundingDeductedUsd}, etc.</li>
 * </ul>
 *
 * <p><b>Spring 6 rule:</b> this class has two constructors. The @Value constructor
 * MUST be annotated with {@code @Autowired} so Spring selects it for injection.
 *
 * <p><b>Direction derivation:</b> the canonical GET response has no {@code direction}
 * field; it is inferred from currencies:
 * <ul>
 *   <li>KRW-to-KRW             → {@link TransactionDirection#DOMESTIC} (Korea domestic, BOK exempt)</li>
 *   <li>"KRW".equals(targetCcy) → {@link TransactionDirection#INBOUND} (foreign-to-KRW)</li>
 *   <li>"KRW".equals(sendCcy)   → {@link TransactionDirection#OUTBOUND} (KRW-to-foreign)</li>
 *   <li>otherwise               → {@link TransactionDirection#OUTBOUND} (e.g. USD-to-USD cross-border)</li>
 * </ul>
 *
 * <p><b>BOK field derivation:</b>
 * <ul>
 *   <li>{@code crossRate}    ← {@code appliedFxRate} (targetPayout / sendAmount)</li>
 *   <li>{@code usdAmount}    ← {@code prefundingDeductedUsd} (best available USD proxy)</li>
 *   <li>{@code offerRateColl} ← not available in GET response; set to {@code null}
 *       (BOK FX1015 field #14 formula requires margin data not exposed in this endpoint)</li>
 *   <li>{@code partnerId}    ← not available in GET response; set to {@code 0}</li>
 * </ul>
 */
@Component
public class RestTransactionClient implements TransactionClient {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionClient.class);

    private static final int PAGE_SIZE = 500;

    /** Status filter: report on APPROVED transactions (terminal success). */
    private static final String STATUS_FILTER = "APPROVED";

    private final RestClient restClient;

    /**
     * Primary constructor used by Spring. The {@code @Autowired} annotation is required
     * because Spring 6 does not auto-select a single {@code @Value}-only constructor
     * when the class declares more than one constructor.
     */
    @Autowired
    public RestTransactionClient(
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Test-friendly constructor — accepts a pre-built {@link RestClient}
     * (e.g. one backed by {@code MockRestServiceServer}).
     */
    public RestTransactionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages through all results from {@code GET /v1/transactions}.
     * Returns an empty list (never null) if the remote returns no records.
     */
    @Override
    public List<CommittedTransaction> fetchCommitted(LocalDate from, LocalDate to, Long partnerId) {
        List<CommittedTransaction> result = new ArrayList<>();
        int page = 0;
        long totalElements = Long.MAX_VALUE; // updated after first response

        while ((long) page * PAGE_SIZE < totalElements) {
            String uri = buildUri(from, to, partnerId, page);
            log.debug("Fetching transactions page={} uri={}", page, uri);

            TransactionPageResponse pageResponse = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(TransactionPageResponse.class);

            if (pageResponse == null || pageResponse.getContent() == null) {
                log.warn("Received null/empty page response for page={}", page);
                break;
            }

            totalElements = pageResponse.getTotalElements();
            if (totalElements == 0) break;

            for (TransactionRecord rec : pageResponse.getContent()) {
                CommittedTransaction txn = toDomain(rec);
                if (txn != null) {
                    result.add(txn);
                }
            }

            // If we got fewer records than a full page, we're done
            if (pageResponse.getContent().size() < PAGE_SIZE) break;

            page++;
        }

        log.debug("fetchCommitted from={} to={} partnerId={} returned {} records",
                from, to, partnerId, result.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildUri(LocalDate from, LocalDate to, Long partnerId, int page) {
        StringBuilder sb = new StringBuilder("/v1/transactions");
        sb.append("?from=").append(from);
        sb.append("&to=").append(to);
        sb.append("&size=").append(PAGE_SIZE);
        sb.append("&page=").append(page);
        sb.append("&status=").append(STATUS_FILTER);
        if (partnerId != null) {
            sb.append("&partnerId=").append(partnerId);
        }
        return sb.toString();
    }

    /**
     * Maps a wire {@link TransactionRecord} (camelCase canonical fields) to the domain
     * {@link CommittedTransaction}.
     *
     * <p>Direction is inferred from currencies (see class-level Javadoc).
     * Missing fields ({@code offerRateColl}, {@code partnerId}) are defaulted.
     */
    private CommittedTransaction toDomain(TransactionRecord rec) {
        String sendCcy = rec.getSendCcy();
        String targetCcy = rec.getTargetCcy();

        if (sendCcy == null || targetCcy == null) {
            log.warn("Skipping txnRef={} with null sendCcy or targetCcy", rec.getTxnRef());
            return null;
        }

        // Derive direction from currency pair
        TransactionDirection direction = deriveDirection(sendCcy, targetCcy);

        // Parse createdAt as the committed-at timestamp
        Instant committedAt;
        try {
            committedAt = rec.getCreatedAt() != null
                    ? Instant.parse(rec.getCreatedAt())
                    : Instant.EPOCH;
        } catch (Exception e) {
            log.warn("Skipping txnRef={} with unparseable createdAt={}", rec.getTxnRef(), rec.getCreatedAt());
            return null;
        }

        // Parse BigDecimal money fields (BigDecimal-as-string contract)
        BigDecimal sendAmount    = parseBd(rec.getSendAmount(),    rec.getTxnRef(), "sendAmount");
        BigDecimal targetPayout  = parseBd(rec.getTargetPayout(),  rec.getTxnRef(), "targetPayout");
        // crossRate = appliedFxRate (targetPayout / sendAmount, computed by transaction-mgmt)
        BigDecimal crossRate     = parseBd(rec.getAppliedFxRate(), rec.getTxnRef(), "appliedFxRate");
        // usdAmount: best proxy is prefundingDeductedUsd; null when not available
        BigDecimal usdAmount     = parseBd(rec.getPrefundingDeductedUsd(), rec.getTxnRef(), "prefundingDeductedUsd");

        // offerRateColl (BOK FX1015 field #14) is not available from the canonical GET response.
        // The formula (sendAmount / (collection_usd - collection_margin_usd)) requires margin
        // data not exposed in this endpoint. Set to null; BokFxMapper handles null gracefully
        // for OUTBOUND transactions; INBOUND reports should use the locked value from the
        // rate-engine — this is a known limitation of consuming the GET endpoint rather than
        // a dedicated committed-transaction stream.
        BigDecimal offerRateColl = null;

        // partnerId: not included in the canonical GET response.
        long partnerId = 0L;

        // sameCcyShortcircuit: true only for KRW-to-KRW domestic transactions
        boolean sameCcyShortcircuit = direction == TransactionDirection.DOMESTIC;

        return new CommittedTransaction(
                0L,              // txnId: not available in GET response (use 0)
                rec.getTxnRef(),
                direction,
                sameCcyShortcircuit,
                offerRateColl,
                crossRate,
                sendAmount,
                sendCcy,
                targetPayout,
                targetCcy,
                usdAmount,
                committedAt,
                partnerId);
    }

    /**
     * Derives the transaction direction from the send/target currency pair.
     *
     * <ul>
     *   <li>Same currency → DOMESTIC</li>
     *   <li>targetCcy = KRW → INBOUND (foreign currency arriving in Korea)</li>
     *   <li>sendCcy = KRW   → OUTBOUND (KRW leaving Korea)</li>
     *   <li>neither is KRW  → OUTBOUND (cross-currency, non-KRW legs treated as outbound)</li>
     * </ul>
     */
    private static TransactionDirection deriveDirection(String sendCcy, String targetCcy) {
        // Only KRW-to-KRW is truly domestic (Korea internal, BOK exempt)
        // USD-to-USD cross-border (e.g. overseas remittance) is OUTBOUND
        if ("KRW".equals(sendCcy) && "KRW".equals(targetCcy)) {
            return TransactionDirection.DOMESTIC;
        }
        if ("KRW".equals(targetCcy)) {
            return TransactionDirection.INBOUND;
        }
        // KRW sender going abroad, non-KRW cross-ccy, or same-ccy non-KRW (e.g. USD->USD)
        return TransactionDirection.OUTBOUND;
    }

    /**
     * Parses a BigDecimal-as-string field. Returns null if the value is null or blank.
     * Logs a warning if the value is non-null but unparseable.
     */
    private static BigDecimal parseBd(String value, String txnRef, String fieldName) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            LoggerFactory.getLogger(RestTransactionClient.class)
                    .warn("Unparseable {} '{}' for txnRef={}", fieldName, value, txnRef);
            return null;
        }
    }
}
