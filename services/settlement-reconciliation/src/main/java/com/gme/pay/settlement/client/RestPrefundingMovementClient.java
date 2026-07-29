package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.settlement.corridor.PrefundingMovement;
import com.gme.pay.settlement.port.PrefundingMovementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link PrefundingMovementPort} — leg (b) of the cross-border three-way
 * tie-out.
 *
 * <p>Reads prefunding's date-ranged movement query,
 * {@code GET /v1/prefunding/{code}/movements?from=&to=&types=&page=&size=} (GAP T2-8), for the
 * settlement date's half-open window in the corridor's settlement timezone. That endpoint replaced
 * the earlier use of {@code /deductions}, which could not support a finance control:
 *
 * <ul>
 *   <li>it had <b>no date filter</b>, so this client asked for the newest 500 entries and windowed
 *       them client-side. On a day with more than ~500 movements for one partner the oldest of that
 *       day fell outside the page and were reported as MISSING_PREFUNDING breaks that never happened.
 *       All the client could do was log a WARN whenever the page came back full — a control that
 *       announces it might be wrong. The new endpoint takes the window server-side and reports
 *       {@code totalElements} / {@code hasNext}, so this client pages until the day is exhausted and
 *       <b>a truncated read is not representable</b>;</li>
 *   <li>it showed <b>deductions only</b>, so a deduct-then-reverse pair read as a plain deduct. Now
 *       {@code types=DEBIT,CREDIT,CAPTURE} brings back the credit legs too, each signed, so a
 *       reversed payment nets to zero instead of masquerading as consumed float.</li>
 * </ul>
 *
 * <p><b>Internal auth.</b> Prefunding's entire surface sits behind the service-to-service gate
 * ({@code X-Gme-Internal}, {@code com.gme.pay.internalauth}) and the service refuses to boot without
 * the shared secret, so this client must present it or every read 401s. A blank secret sends no
 * header and logs a WARN — fail-visible, never a fabricated credential.
 *
 * <p><b>Failure policy.</b> Transport failure yields an empty list (logged at ERROR, never thrown):
 * the run then surfaces MISSING_PREFUNDING breaks rather than aborting, which is visible rather than
 * silent. A failure <em>part-way</em> through paging is treated the same way — the partial pages are
 * <b>discarded</b>, because half a day of movements would produce breaks indistinguishable from real
 * ones while looking like a successful run.
 */
@Component
public class RestPrefundingMovementClient implements PrefundingMovementPort {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingMovementClient.class);

    /** Page size requested from the movements endpoint (its own maximum is 1000). */
    static final int PAGE_SIZE = 500;

    /**
     * Guard on the paging loop. At {@value #PAGE_SIZE} rows a page this allows 100,000 movements for
     * one partner on one day; more than that is a bug or an attack, not a business day, and looping
     * forever against a misbehaving upstream would hang the nightly recon.
     */
    static final int MAX_PAGES = 200;

    /**
     * The balance-moving entry types. Holds ({@code RESERVE}/{@code RELEASE}) and the AML counters
     * ({@code CUM_CHARGE}/{@code CUM_REVERSE}) are excluded because they never move float, so
     * including them would add zero-delta rows to the netting for no gain.
     */
    static final String MOVEMENT_TYPES = "DEBIT,CREDIT,CAPTURE";

    private final RestClient restClient;
    private final ZoneId settlementZone;

    /** Primary constructor — wired by Spring (two constructors ⇒ {@code @Autowired} required). */
    @Autowired
    public RestPrefundingMovementClient(
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.internal-auth.secret:}") String internalSecret,
            @Value("${gmepay.settlement.corridor.settlement-zone:Asia/Seoul}") String settlementZone) {
        this(builderFor(baseUrl, internalSecret).build(), settlementZone);
    }

    /**
     * Builds the {@link RestClient.Builder} the production constructor uses: base URL plus, when a
     * secret is configured, the {@code X-Gme-Internal} default header. Package-private so a test can
     * bind a {@code MockRestServiceServer} to the very same builder and assert the header really goes
     * on the wire instead of trusting a hand-built client.
     */
    static RestClient.Builder builderFor(String baseUrl, String internalSecret) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (internalSecret != null && !internalSecret.isBlank()) {
            b.defaultHeader(InternalAuthHeaders.INTERNAL_TOKEN, internalSecret);
        } else {
            log.warn("gmepay.internal-auth.secret is blank — the prefunding movement read will carry "
                            + "no {} header and gated prefunding will refuse it (401), so corridor "
                            + "leg (b) will be EMPTY and every line will break as MISSING_PREFUNDING. "
                            + "Set GMEPAY_INTERNAL_AUTH_SECRET.",
                    InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return b;
    }

    /** Package-private constructor used by tests that supply a pre-built {@link RestClient}. */
    RestPrefundingMovementClient(RestClient restClient, String settlementZone) {
        this.restClient = restClient;
        this.settlementZone = ZoneId.of(settlementZone);
    }

    @Override
    public List<PrefundingMovement> deductionsOn(String partnerCode, LocalDate settlementDate) {
        // Half-open window, matching the endpoint's documented [from, to) semantics: consecutive
        // settlement dates tile the timeline exactly once, so a movement stamped at midnight KST is
        // neither counted twice nor lost between two runs.
        Instant from = settlementDate.atStartOfDay(settlementZone).toInstant();
        Instant to = settlementDate.plusDays(1).atStartOfDay(settlementZone).toInstant();

        List<PrefundingMovement> movements = new ArrayList<>();
        long expectedTotal = -1;
        int rowsSeen = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            final int pageIndex = page;
            MovementsView view;
            try {
                view = restClient.get()
                        .uri(builder -> builder.path("/v1/prefunding/{code}/movements")
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .queryParam("types", MOVEMENT_TYPES)
                                .queryParam("page", pageIndex)
                                .queryParam("size", PAGE_SIZE)
                                .build(partnerCode))
                        .retrieve()
                        .body(MovementsView.class);
            } catch (Exception e) {
                log.error("prefunding unreachable while reading {} movements for {} (page {}): {}"
                                + " — DISCARDING the {} rows already read; the corridor tie-out for "
                                + "this date is INCOMPLETE",
                        partnerCode, settlementDate, page, e.getMessage(), rowsSeen);
                return List.of();
            }
            if (view == null || view.movements() == null) {
                if (page == 0) {
                    return List.of();
                }
                log.error("prefunding returned an empty body for {} movements on {} at page {} — "
                                + "DISCARDING the {} rows already read rather than reporting a partial "
                                + "day as complete", partnerCode, settlementDate, page, rowsSeen);
                return List.of();
            }
            expectedTotal = view.totalElements() == null ? -1 : view.totalElements();
            rowsSeen += view.movements().size();
            for (Movement m : view.movements()) {
                PrefundingMovement mapped = map(m, partnerCode, settlementDate);
                if (mapped != null) {
                    movements.add(mapped);
                }
            }
            if (!Boolean.TRUE.equals(view.hasNext())) {
                if (expectedTotal >= 0 && rowsSeen != expectedTotal) {
                    // The endpoint said the day is exhausted but the arithmetic disagrees. Refusing
                    // the result is the only safe answer: the alternative is inventing breaks.
                    log.error("prefunding reported totalElements={} for {} movements on {} but paging "
                                    + "yielded {} rows — DISCARDING; leg (b) cannot be trusted for "
                                    + "this date", expectedTotal, partnerCode, settlementDate, rowsSeen);
                    return List.of();
                }
                log.debug("corridor leg(b): {} float movements ({} rows over {} page(s)) for {} on {}",
                        movements.size(), rowsSeen, page + 1, partnerCode, settlementDate);
                return movements;
            }
        }
        log.error("prefunding movement paging for {} on {} exceeded {} pages ({} rows) — DISCARDING; "
                        + "an unbounded upstream must not be reported as a reconciled day",
                partnerCode, settlementDate, MAX_PAGES, rowsSeen);
        return List.of();
    }

    /**
     * Maps one wire row to the port's signed float-consumed convention.
     *
     * <p>{@code balanceDeltaUsd} is signed from the <em>balance's</em> point of view (negative = float
     * consumed), so it is negated here: leg (b) is compared against a transaction's
     * {@code prefundingDeductedUsd}, which is positive-when-deducted.
     *
     * <p>Rows with no {@code txnRef} are skipped by design: an operator top-up or manual credit
     * belongs to no transaction, so it has no reference to join on and is not part of a per-payment
     * tie-out. Rows with a zero delta (a hold, or an AML counter if the type filter is ever widened)
     * are skipped too — they moved no float.
     */
    private PrefundingMovement map(Movement m, String partnerCode, LocalDate date) {
        if (m == null || m.txnRef() == null || m.txnRef().isBlank() || m.at() == null) {
            return null;
        }
        BigDecimal delta = parseDecimalOrNull(m.balanceDeltaUsd(), "balanceDeltaUsd", m.txnRef());
        if (delta == null) {
            // Fall back to the raw magnitude + direction if the signed field is unusable, so a
            // partial contract change degrades to the old (unsigned) reading rather than to silence.
            BigDecimal amount = parseDecimalOrNull(m.amountUsd(), "amountUsd", m.txnRef());
            if (amount == null) {
                return null;
            }
            delta = "CREDIT".equalsIgnoreCase(m.direction()) ? amount : amount.negate();
        }
        if (delta.signum() == 0) {
            return null;
        }
        Instant at = parseInstantOrNull(m.at(), m.txnRef());
        if (at == null) {
            log.warn("prefunding movement {} for {} on {} has an unparseable timestamp — skipped",
                    m.txnRef(), partnerCode, date);
            return null;
        }
        return new PrefundingMovement(m.txnRef(), delta.negate(), at, m.entryType());
    }

    private static Instant parseInstantOrNull(String value, String ref) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(value.trim()).toInstant();
            } catch (Exception e2) {
                log.warn("unparseable prefunding timestamp '{}' on {} — treated as absent", value, ref);
                return null;
            }
        }
    }

    private static BigDecimal parseDecimalOrNull(String value, String field, String ref) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("unparseable prefunding {} '{}' on {} — treated as absent", field, value, ref);
            return null;
        }
    }

    /**
     * Wire shape of prefunding's {@code MovementsResponse}. Unknown fields ignored so an additive
     * change on prefunding's side cannot break the read. {@code totalElements} and {@code hasNext}
     * are the completeness contract; a boxed {@code Boolean}/{@code Long} so an absent field is
     * distinguishable from {@code false}/{@code 0}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MovementsView(String partnerCode, String from, String to, Integer page,
                                Integer size, Long totalElements, Integer totalPages,
                                Boolean hasNext, List<Movement> movements) {}

    /** One float movement as prefunding reports it; money arrives as decimal strings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Movement(Long ledgerEntryId, String txnRef, String entryType, String amountUsd,
                           String balanceDeltaUsd, String direction, String currency, String at) {}
}
