package com.gme.pay.prefunding.api;

import com.gme.pay.contracts.BalanceAlertView;
import com.gme.pay.contracts.BalanceView;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 5 (5B.1) balance provisioning + read surface for the prefunding service.
 *
 * <ul>
 *   <li>{@code POST /v1/prefunding/provision} — create the {@code partner_balance} row for a
 *       newly onboarded partner (wizard step "Prefunding"). Idempotency: a second provision
 *       for the same partnerCode returns 409 — top-ups go through {@code /credit}.</li>
 *   <li>{@code GET /v1/prefunding/{partnerCode}/balance} — canonical {@link BalanceView}
 *       (money as decimal strings per {@code docs/MONEY_CONVENTION.md}; pctOfThreshold is the
 *       tier-alert gauge). Replaces the old {@code {partnerId, balance}} shape that lived on
 *       {@code PrefundingController}.</li>
 *   <li>{@code GET /v1/prefunding/{partnerCode}/alerts} — raised tier alerts, newest first,
 *       relayed by the BFF's {@code GET /v1/admin/partners/{code}/balance-alerts}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/prefunding")
public class BalanceProvisioningController {

    /** Prefunding balances are USD by convention (OVERSEAS partners prepay in USD). */
    static final String PREFUNDING_CURRENCY = "USD";

    private final PartnerBalanceRepository balances;
    private final BalanceAlertRepository alerts;

    public BalanceProvisioningController(PartnerBalanceRepository balances,
                                         BalanceAlertRepository alerts) {
        this.balances = balances;
        this.alerts = alerts;
    }

    /**
     * Provision a partner's opening balance. 201 with the fresh {@link BalanceView};
     * 409 when the partner already has a balance row (idempotency guard);
     * 400 on missing/invalid fields.
     */
    @PostMapping("/provision")
    @Transactional
    public ResponseEntity<BalanceView> provision(@RequestBody ProvisionRequest body) {
        if (body == null || body.partnerCode() == null || body.partnerCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerCode is required");
        }
        if (body.openingBalanceUsd() == null || body.openingBalanceUsd().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "openingBalanceUsd is required and must be >= 0");
        }
        if (body.lowBalanceThresholdUsd() == null || body.lowBalanceThresholdUsd().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lowBalanceThresholdUsd is required and must be > 0");
        }
        String partnerCode = body.partnerCode().trim();
        if (balances.existsById(partnerCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + partnerCode + "' already has a prefunding balance"
                            + " — use /v1/prefunding/" + partnerCode + "/credit to top up");
        }
        PartnerBalanceEntity entity = new PartnerBalanceEntity(
                partnerCode, PREFUNDING_CURRENCY,
                body.openingBalanceUsd(), body.lowBalanceThresholdUsd(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        // Seed the credit headroom from the provision request (config-registry's credit_limit_usd);
        // null ⇒ 0 = strict prepaid, hard-decline at zero.
        entity.setCreditLimit(body.creditLimitUsd() == null ? BigDecimal.ZERO : body.creditLimitUsd());
        PartnerBalanceEntity saved = balances.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /** The current balance as the canonical {@link BalanceView}. 404 when unknown. */
    @GetMapping("/{partnerCode}/balance")
    public BalanceView getBalance(@PathVariable String partnerCode) {
        PartnerBalanceEntity row = balances.findById(partnerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no prefunding balance for partner '" + partnerCode + "'"));
        return toView(row);
    }

    /** Raised tier alerts for the partner, newest first. 404 when the partner is unknown. */
    @GetMapping("/{partnerCode}/alerts")
    public List<BalanceAlertView> getAlerts(@PathVariable String partnerCode) {
        if (!balances.existsById(partnerCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner '" + partnerCode + "'");
        }
        return alerts.findByPartnerCodeOrderByIdDesc(partnerCode).stream()
                .map(a -> new BalanceAlertView(a.getId(), a.getPartnerCode(), a.getTier(),
                        a.getBalanceUsd(), a.getThresholdUsd(), a.getRaisedAt(),
                        a.isAcknowledged()))
                .toList();
    }

    static BalanceView toView(PartnerBalanceEntity row) {
        return BalanceView.of(
                row.getPartnerId(),
                row.getCurrency(),
                row.getBalance(),
                row.getLowBalanceThreshold(),
                pctOfThreshold(row.getBalance(), row.getLowBalanceThreshold()));
    }

    /** {@code balance / threshold * 100}, scale 2 HALF_UP; null when no positive threshold. */
    static BigDecimal pctOfThreshold(BigDecimal balance, BigDecimal threshold) {
        if (balance == null || threshold == null || threshold.signum() <= 0) {
            return null;
        }
        return balance.multiply(new BigDecimal("100"))
                .divide(threshold, 2, RoundingMode.HALF_UP);
    }

    /**
     * Body for {@link #provision}. Money fields arrive as decimal strings (Jackson binds
     * string → {@link BigDecimal} losslessly) per {@code docs/MONEY_CONVENTION.md}.
     */
    public record ProvisionRequest(
            String partnerCode,
            BigDecimal openingBalanceUsd,
            BigDecimal lowBalanceThresholdUsd,
            BigDecimal creditLimitUsd) {
    }
}
