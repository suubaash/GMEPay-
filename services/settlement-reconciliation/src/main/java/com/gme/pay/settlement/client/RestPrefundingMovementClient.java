package com.gme.pay.settlement.client;

import com.gme.pay.contracts.BalanceDeductionEntry;
import com.gme.pay.contracts.PrefundingDeductionHistoryView;
import com.gme.pay.settlement.corridor.PrefundingMovement;
import com.gme.pay.settlement.port.PrefundingMovementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link PrefundingMovementPort} — leg (b) of the cross-border three-way
 * tie-out. Reads prefunding's existing {@code GET /v1/prefunding/{code}/deductions} (the only read
 * surface it publishes for float movements) and keeps the entries whose timestamp falls on the
 * settlement date. Response is deserialised into the canonical
 * {@link PrefundingDeductionHistoryView} contract from {@code lib-api-contracts} — no re-declared
 * wire shape to drift from prefunding's.
 *
 * <p><b>Known limitations of the existing endpoint</b> (it was built for a "recent history" widget,
 * and this task may not modify the prefunding service):
 * <ul>
 *   <li>no date filter — the client asks for the newest {@code limit} entries and windows them
 *       client-side. {@code limit} is capped at 500 by the endpoint, so on a day with more than
 *       ~500 deductions for one partner the oldest movements of that day can fall outside the page
 *       and be reported as MISSING_PREFUNDING. The client logs a WARN whenever the page comes back
 *       full, i.e. whenever truncation is possible;</li>
 *   <li>deductions only — reversals are not exposed, so a deduct-then-reverse pair reads as a plain
 *       deduct. Reversed payments do not reach this tie-out anyway (they never become APPROVED
 *       transactions), so the effect is a possible MISSING_INTERNAL line rather than a wrong amount.</li>
 * </ul>
 * Both disappear with a date-ranged movement endpoint on prefunding — recorded as the follow-up in
 * the T2-2 register note rather than papered over here.
 *
 * <p>Transport failure yields an empty list (logged at ERROR, never thrown): the run then surfaces
 * MISSING_PREFUNDING breaks instead of aborting, which is visible rather than silent.
 */
@Component
public class RestPrefundingMovementClient implements PrefundingMovementPort {

    private static final Logger log = LoggerFactory.getLogger(RestPrefundingMovementClient.class);

    /** Maximum the prefunding endpoint accepts (it clamps to 500). */
    static final int MAX_LIMIT = 500;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ZoneId settlementZone;

    /** Primary constructor — wired by Spring (two constructors ⇒ {@code @Autowired} required). */
    @Autowired
    public RestPrefundingMovementClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}") String baseUrl,
            @Value("${gmepay.settlement.corridor.settlement-zone:Asia/Seoul}") String settlementZone) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
        this.settlementZone = ZoneId.of(settlementZone);
    }

    /** Package-private constructor used by tests that supply a pre-built {@link RestTemplate}. */
    RestPrefundingMovementClient(RestTemplate restTemplate, String baseUrl, String settlementZone) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.settlementZone = ZoneId.of(settlementZone);
    }

    @Override
    public List<PrefundingMovement> deductionsOn(String partnerCode, LocalDate settlementDate) {
        Instant dayStart = settlementDate.atStartOfDay(settlementZone).toInstant();
        Instant dayEnd = settlementDate.plusDays(1).atStartOfDay(settlementZone).toInstant();

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/v1/prefunding/" + partnerCode + "/deductions")
                .queryParam("limit", MAX_LIMIT)
                .toUriString();

        PrefundingDeductionHistoryView view;
        try {
            view = restTemplate.getForObject(url, PrefundingDeductionHistoryView.class);
        } catch (Exception e) {
            log.error("prefunding unreachable while reading {} deductions for {}: {}"
                            + " — the corridor tie-out for this date is INCOMPLETE",
                    partnerCode, settlementDate, e.getMessage());
            return List.of();
        }
        if (view == null || view.entries() == null) {
            return List.of();
        }
        if (view.entries().size() >= MAX_LIMIT) {
            log.warn("prefunding returned a FULL page of {} deductions for {} — older movements of {} "
                            + "may be truncated (the endpoint has no date filter); a MISSING_PREFUNDING "
                            + "break on this date may be an artefact of that cap",
                    MAX_LIMIT, partnerCode, settlementDate);
        }

        List<PrefundingMovement> movements = new ArrayList<>();
        for (BalanceDeductionEntry entry : view.entries()) {
            if (entry == null || entry.at() == null || entry.amountUsd() == null
                    || entry.txnRef() == null) {
                continue;
            }
            if (entry.at().isBefore(dayStart) || !entry.at().isBefore(dayEnd)) {
                continue;
            }
            movements.add(new PrefundingMovement(entry.txnRef(), entry.amountUsd(), entry.at()));
        }
        log.debug("corridor leg(b): {} prefunding deductions for {} on {}",
                movements.size(), partnerCode, settlementDate);
        return movements;
    }
}
