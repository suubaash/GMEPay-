package com.gme.pay.settlement.client;

import com.gme.pay.settlement.corridor.SchemeTransactionRecord;
import com.gme.pay.settlement.port.SchemeTransactionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link SchemeTransactionPort} — leg (a) of the cross-border three-way
 * tie-out. Calls transaction-mgmt's canonical paged {@code GET /v1/transactions} (never its
 * database) and keeps only the rows belonging to the requested scheme.
 *
 * <p>Uses the same endpoint and page size as {@link RestTransactionQueryClient} but a different
 * projection: this tie-out needs {@code prefundingDeductedUsd} (the USD the hub actually moved) plus
 * the send/target legs, which the KRW settlement projection drops.
 *
 * <p><b>Date semantics:</b> the request window is the settlement date and rows are then filtered on
 * {@code approvedAt} falling inside that date in the configured settlement zone — the transaction's
 * settle-able moment, consistent with how the ZeroPay lane applies its window cutoff. A row with no
 * parseable {@code approvedAt} is KEPT (fail open): dropping it would silently shrink the tie-out.
 *
 * <p>Transport failure yields an empty list (logged at ERROR, never thrown) so a recon run degrades
 * into visible MISSING_INTERNAL breaks rather than aborting the whole day.
 */
@Component
public class RestSchemeTransactionClient implements SchemeTransactionPort {

    private static final Logger log = LoggerFactory.getLogger(RestSchemeTransactionClient.class);

    /** Maximum page size accepted by transaction-mgmt (contract: max 500). */
    private static final int PAGE_SIZE = 500;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final java.time.ZoneId settlementZone;

    /**
     * Primary constructor — wired by Spring. {@code @Autowired} is required because this component
     * has a second (test) constructor.
     */
    @Autowired
    public RestSchemeTransactionClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.transaction-mgmt.base-url:http://transaction-mgmt:8082}") String baseUrl,
            @Value("${gmepay.settlement.corridor.settlement-zone:Asia/Seoul}") String settlementZone) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
        this.settlementZone = java.time.ZoneId.of(settlementZone);
    }

    /** Package-private constructor used by tests that supply a pre-built {@link RestTemplate}. */
    RestSchemeTransactionClient(RestTemplate restTemplate, String baseUrl, String settlementZone) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.settlementZone = java.time.ZoneId.of(settlementZone);
    }

    @Override
    public List<SchemeTransactionRecord> findApprovedByScheme(String schemeId, LocalDate settlementDate) {
        List<SchemeTransactionRecord> result = new ArrayList<>();
        Instant dayStart = settlementDate.atStartOfDay(settlementZone).toInstant();
        Instant dayEnd = settlementDate.plusDays(1).atStartOfDay(settlementZone).toInstant();

        int page = 0;
        long totalElements;
        do {
            String url = UriComponentsBuilder
                    .fromHttpUrl(baseUrl + "/v1/transactions")
                    .queryParam("from", settlementDate)
                    .queryParam("to", settlementDate)
                    .queryParam("status", "APPROVED")
                    .queryParam("page", page)
                    .queryParam("size", PAGE_SIZE)
                    .toUriString();
            try {
                RestTransactionQueryClient.TransactionPageResponse pageResponse =
                        restTemplate.getForObject(url, RestTransactionQueryClient.TransactionPageResponse.class);
                if (pageResponse == null || pageResponse.content() == null) {
                    break;
                }
                int seen = 0;
                for (RestTransactionQueryClient.TransactionResponse r : pageResponse.content()) {
                    seen++;
                    if (schemeId != null && !schemeId.equalsIgnoreCase(r.qrSchemeId())) {
                        continue;   // another scheme's traffic on the same day
                    }
                    Instant approvedAt = parseInstantOrNull(r.approvedAt(), r.txnRef());
                    if (approvedAt != null && (approvedAt.isBefore(dayStart) || !approvedAt.isBefore(dayEnd))) {
                        continue;   // approved outside the settlement day
                    }
                    result.add(toRecord(r, approvedAt));
                }
                totalElements = pageResponse.totalElements();
                page++;
                if (seen == 0) {
                    break;
                }
                if ((long) page * PAGE_SIZE >= totalElements) {
                    break;
                }
            } catch (Exception e) {
                log.error("transaction-mgmt unreachable while reading {} transactions for {} (page={}): {}"
                                + " — the corridor tie-out for this date is INCOMPLETE",
                        schemeId, settlementDate, page, e.getMessage());
                break;
            }
        } while (true);

        log.debug("corridor leg(a): {} APPROVED {} transactions for {}", result.size(), schemeId, settlementDate);
        return result;
    }

    private static SchemeTransactionRecord toRecord(
            RestTransactionQueryClient.TransactionResponse r, Instant approvedAt) {
        return new SchemeTransactionRecord(
                r.txnRef(),
                r.partnerRef(),
                r.merchantId(),
                r.qrSchemeId(),
                r.sendCcy(),
                parseDecimalOrNull(r.sendAmount(), "sendAmount", r.txnRef()),
                r.targetCcy(),
                parseDecimalOrNull(r.targetPayout(), "targetPayout", r.txnRef()),
                parseDecimalOrNull(r.prefundingDeductedUsd(), "prefundingDeductedUsd", r.txnRef()),
                r.status(),
                approvedAt);
    }

    /**
     * Parse an ISO-8601 instant; null/blank/malformed → null (logged), NEVER throws. Null means "no
     * approval timestamp", and the caller keeps such a row rather than silently dropping money.
     */
    private static Instant parseInstantOrNull(String value, String txnRef) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("unparseable approvedAt '{}' on txn {} — row kept, date window not applied",
                    value, txnRef);
            return null;
        }
    }

    /**
     * Parse a decimal money string; null/blank → null (an ABSENT amount, which the tie-out must be
     * able to tell apart from zero), malformed → null (logged). Never throws.
     */
    private static BigDecimal parseDecimalOrNull(String value, String field, String txnRef) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("unparseable {} '{}' on txn {} — treated as absent", field, value, txnRef);
            return null;
        }
    }
}
