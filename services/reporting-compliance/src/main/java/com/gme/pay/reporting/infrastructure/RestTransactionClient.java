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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Production implementation of {@link TransactionClient} that calls the
 * {@code transaction-mgmt} service via Spring 6 {@link RestClient}.
 *
 * <p>Calls {@code GET /v1/transactions?from={from}&to={to}[&partnerId={id}]}
 * and pages through all results (page size 500) until {@code total_pages} is reached.
 *
 * <p><b>Spring 6 rule:</b> this class has two constructors. The @Value constructor
 * MUST be annotated with {@code @Autowired} so Spring selects it for injection.
 *
 * <p>No real transaction-mgmt credentials are needed locally; the base-url defaults
 * to {@code http://transaction-mgmt:8080} and can be overridden in tests via
 * {@code gmepay.transaction-mgmt.base-url}.
 */
@Component
public class RestTransactionClient implements TransactionClient {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionClient.class);

    private static final int PAGE_SIZE = 500;

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
        int totalPages = 1; // will be updated after first response

        while (page < totalPages) {
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

            totalPages = pageResponse.getTotalPages();
            for (TransactionRecord rec : pageResponse.getContent()) {
                CommittedTransaction txn = toDomain(rec);
                if (txn != null) {
                    result.add(txn);
                }
            }

            page++;
            // Guard against infinite loops with a malformed totalPages=0
            if (totalPages <= 0) break;
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
        sb.append("&status=COMMITTED");
        if (partnerId != null) {
            sb.append("&partnerId=").append(partnerId);
        }
        return sb.toString();
    }

    private CommittedTransaction toDomain(TransactionRecord rec) {
        if (rec.getDirection() == null) {
            log.warn("Skipping txnId={} with null direction", rec.getTxnId());
            return null;
        }
        TransactionDirection direction;
        try {
            direction = TransactionDirection.valueOf(rec.getDirection());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping txnId={} with unknown direction={}", rec.getTxnId(), rec.getDirection());
            return null;
        }

        Instant committedAt;
        try {
            committedAt = rec.getCommittedAt() != null
                    ? Instant.parse(rec.getCommittedAt())
                    : Instant.EPOCH;
        } catch (Exception e) {
            log.warn("Skipping txnId={} with unparseable committedAt={}", rec.getTxnId(), rec.getCommittedAt());
            return null;
        }

        return new CommittedTransaction(
                rec.getTxnId(),
                rec.getTxnRef(),
                direction,
                rec.isSameCcyShortcircuit(),
                rec.getOfferRateColl(),
                rec.getCrossRate(),
                rec.getCollectionAmount(),
                rec.getCollectionCcy(),
                rec.getPayoutAmount(),
                rec.getPayoutCcy(),
                rec.getUsdAmount(),
                committedAt,
                rec.getPartnerId());
    }
}
