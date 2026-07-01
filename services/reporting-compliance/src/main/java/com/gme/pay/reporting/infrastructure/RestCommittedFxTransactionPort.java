package com.gme.pay.reporting.infrastructure;

import com.gme.pay.contracts.CommittedFxView;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.CommittedFxTransactionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production REST adapter for {@link CommittedFxTransactionPort}: calls transaction-mgmt's
 * committed-FX projection endpoint
 * {@code GET /v1/transactions/fx-committed?from={from}&to={to}[&partnerId={id}]}, which
 * returns a {@code List<}{@link CommittedFxView}{@code >} carrying the rate-locked margin
 * fields — including {@code offerRateColl} (BOK FX1015 field #14) and {@code crossRate} —
 * that the canonical {@code GET /v1/transactions} endpoint omits.
 *
 * <p><b>Gating:</b> activated only when {@code gmepay.transaction-mgmt.fx-committed.enabled=true}
 * (mirrors the rest-client gating convention). When absent/false, the in-process
 * {@link FixtureCommittedFxTransactionPort} ({@code @ConditionalOnMissingBean}) remains the
 * default/test fallback, so local boots and tests stay clean and offline.
 *
 * <p><b>Spring 6 rule:</b> this class declares two constructors, so the {@code @Value}
 * constructor MUST carry {@code @Autowired} for Spring to select it (the second constructor
 * is test-only, accepting a pre-built {@link RestClient}).
 *
 * <p><b>Mapping ({@link CommittedFxView} → {@link CommittedTransaction}):</b> the wire
 * {@code direction} is a String; it is mapped to {@link TransactionDirection} via
 * {@code valueOf} (case-normalised). Every other field maps 1:1 by name. No field is
 * synthesised — {@code offerRateColl}/{@code crossRate}/{@code partnerId} flow straight
 * through from the projection, which is what finally populates FX1015 #14 from real data.
 */
@Component
@ConditionalOnProperty(prefix = "gmepay.transaction-mgmt.fx-committed", name = "enabled",
        havingValue = "true")
public class RestCommittedFxTransactionPort implements CommittedFxTransactionPort {

    private static final Logger log = LoggerFactory.getLogger(RestCommittedFxTransactionPort.class);

    private static final ParameterizedTypeReference<List<CommittedFxView>> VIEW_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    /**
     * Primary Spring constructor. {@code @Autowired} is required because this class
     * declares more than one constructor (Spring 6 does not auto-select a single
     * {@code @Value}-only constructor in that case).
     */
    @Autowired
    public RestCommittedFxTransactionPort(
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Test-friendly constructor — accepts a pre-built {@link RestClient}
     * (e.g. one pointed at a MockWebServer / WireMock base URL).
     */
    public RestCommittedFxTransactionPort(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<CommittedTransaction> fetchCommittedFx(
            java.time.LocalDate from, java.time.LocalDate to, Long partnerId) {

        String uri = buildUri(from, to, partnerId);
        log.debug("Fetching committed-FX projection uri={}", uri);

        List<CommittedFxView> views = restClient.get()
                .uri(uri)
                .retrieve()
                .body(VIEW_LIST);

        if (views == null) {
            log.warn("Received null body from committed-FX projection for uri={}", uri);
            return new ArrayList<>();
        }

        List<CommittedTransaction> result = new ArrayList<>(views.size());
        for (CommittedFxView view : views) {
            CommittedTransaction txn = toDomain(view);
            if (txn != null) {
                result.add(txn);
            }
        }

        log.debug("fetchCommittedFx from={} to={} partnerId={} returned {} records",
                from, to, partnerId, result.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildUri(java.time.LocalDate from, java.time.LocalDate to, Long partnerId) {
        StringBuilder sb = new StringBuilder("/v1/transactions/fx-committed");
        sb.append("?from=").append(from);
        sb.append("&to=").append(to);
        if (partnerId != null) {
            sb.append("&partnerId=").append(partnerId);
        }
        return sb.toString();
    }

    /**
     * Maps a wire {@link CommittedFxView} to the domain {@link CommittedTransaction}.
     *
     * <p>The wire {@code direction} is a String (contract mismatch #5) and is converted
     * to {@link TransactionDirection} via {@code valueOf} (upper-cased, trimmed). An
     * unknown/blank direction skips the record (logged) rather than aborting the batch.
     * All rate-locked FX fields are carried verbatim so FX1015 #14 sources from real data.
     */
    private CommittedTransaction toDomain(CommittedFxView v) {
        TransactionDirection direction = parseDirection(v.direction(), v.txnRef());
        if (direction == null) {
            return null;
        }
        return new CommittedTransaction(
                v.txnId(),
                v.txnRef(),
                direction,
                v.sameCcyShortcircuit(),
                v.offerRateColl(),   // BOK FX1015 field #14 — carried verbatim from the projection
                v.crossRate(),
                v.collectionAmount(),
                v.collectionCcy(),
                v.payoutAmount(),
                v.payoutCcy(),
                v.usdAmount(),
                v.committedAt(),
                v.partnerId());
    }

    private static TransactionDirection parseDirection(String wire, String txnRef) {
        if (wire == null || wire.isBlank()) {
            log.warn("Skipping txnRef={} with null/blank direction", txnRef);
            return null;
        }
        try {
            return TransactionDirection.valueOf(wire.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping txnRef={} with unknown direction '{}'", txnRef, wire);
            return null;
        }
    }
}
